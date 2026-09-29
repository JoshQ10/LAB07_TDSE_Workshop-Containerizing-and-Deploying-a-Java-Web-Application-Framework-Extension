package co.edu.escuelaing.webframework;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The accept loop. The calling thread only accepts connections; each
 * accepted socket is handed to a fixed-size worker pool, so several
 * clients are served in parallel. Stopping closes the listening socket
 * (no new connections) and then drains the pool so in-flight requests
 * still receive their response. Registering a route never requires
 * touching this class.
 */
public class HttpServer {

    private static final int READ_TIMEOUT_MILLIS = 10_000;

    private final Router router;
    private final StaticFileService staticFileService;
    private final int poolSize;
    private final long shutdownTimeoutSeconds;
    private final CountDownLatch terminated = new CountDownLatch(1);
    private volatile boolean running = false;
    private volatile ServerSocket serverSocket;

    public HttpServer(Router router, StaticFileService staticFileService) {
        this(router, staticFileService, 10, 8);
    }

    public HttpServer(Router router, StaticFileService staticFileService, int poolSize, long shutdownTimeoutSeconds) {
        this.router = router;
        this.staticFileService = staticFileService;
        this.poolSize = poolSize;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    }

    /** Blocks accepting connections until {@link #stop()} is called, then drains the worker pool. */
    public void start(int port) throws IOException {
        ExecutorService workers = Executors.newFixedThreadPool(poolSize, workerThreadFactory());
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            System.out.println("Server listening on port " + port + " with " + poolSize + " worker threads");
            while (running) {
                Socket clientSocket;
                try {
                    clientSocket = serverSocket.accept();
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Error accepting connection: " + e.getMessage());
                    }
                    continue;
                }
                workers.execute(() -> serve(clientSocket));
            }
        } finally {
            running = false;
            closeListeningSocket();
            drain(workers);
            terminated.countDown();
        }
    }

    /**
     * Stops accepting new connections. Requests already being handled
     * still complete; {@link #start(int)} returns once they have. Safe to
     * call from a route handler, from another thread or from a JVM
     * shutdown hook, and more than once.
     */
    public void stop() {
        if (running) {
            System.out.println("Shutting down: closing listening socket and draining worker pool...");
        }
        running = false;
        closeListeningSocket();
    }

    /** Waits until the server has fully stopped (pool drained). Returns false on timeout. */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return terminated.await(timeout, unit);
    }

    public boolean isRunning() {
        return running;
    }

    private void closeListeningSocket() {
        ServerSocket socket = serverSocket;
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("Error closing listening socket: " + e.getMessage());
            }
        }
    }

    private void drain(ExecutorService workers) {
        workers.shutdown();
        try {
            if (workers.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                System.out.println("Worker pool drained cleanly.");
            } else {
                System.err.println("Timed out after " + shutdownTimeoutSeconds + "s; interrupting remaining requests.");
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("Server stopped gracefully.");
    }

    private void serve(Socket clientSocket) {
        try (clientSocket) {
            clientSocket.setSoTimeout(READ_TIMEOUT_MILLIS);
            handleConnection(clientSocket);
        } catch (IOException e) {
            System.err.println("Error handling connection: " + e.getMessage());
        }
    }

    private static ThreadFactory workerThreadFactory() {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> new Thread(runnable, "worker-" + counter.getAndIncrement());
    }

    private void handleConnection(Socket clientSocket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = clientSocket.getOutputStream();

        String requestLine = in.readLine();
        if (requestLine == null || requestLine.isBlank()) {
            return;
        }

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            writeResponse(out, 400, "text/plain; charset=UTF-8", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
            return;
        }

        String method = parts[0];
        String rawTarget = parts[1];

        // Consume and discard headers; this server only needs the request line.
        String header;
        while ((header = in.readLine()) != null && !header.isEmpty()) {
            // no-op
        }

        if (!"GET".equalsIgnoreCase(method)) {
            writeResponse(out, 405, "text/plain; charset=UTF-8", "405 Method Not Allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }

        String rawPath;
        Map<String, String> queryParams;
        int queryIndex = rawTarget.indexOf('?');
        if (queryIndex >= 0) {
            rawPath = rawTarget.substring(0, queryIndex);
            queryParams = parseQueryString(rawTarget.substring(queryIndex + 1));
        } else {
            rawPath = rawTarget;
            queryParams = new HashMap<>();
        }

        String path;
        try {
            path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            writeResponse(out, 400, "text/plain; charset=UTF-8", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
            return;
        }

        GetService service = router.resolve(path);
        if (service != null) {
            handleDynamicRoute(service, method, path, queryParams, out);
            return;
        }

        String resolvedStaticPath = staticFileService.resolvePath(path);
        if (resolvedStaticPath != null) {
            byte[] resource = staticFileService.read(path);
            if (resource != null) {
                writeResponse(out, 200, StaticFileService.contentTypeFor(resolvedStaticPath), resource);
                return;
            }
        }

        writeResponse(out, 404, "text/plain; charset=UTF-8", "404 Not Found".getBytes(StandardCharsets.UTF_8));
    }

    private void handleDynamicRoute(GetService service, String method, String path,
                                     Map<String, String> queryParams, OutputStream out) throws IOException {
        Request request = new Request(method, path, queryParams);
        Response response = new Response();
        String body;
        try {
            body = service.handle(request, response);
        } catch (Exception e) {
            String message = "500 Internal Server Error: " + e.getMessage();
            writeResponse(out, 500, "text/plain; charset=UTF-8", message.getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        writeResponse(out, response.getStatus(), response.getContentType(), bytes);
    }

    private Map<String, String> parseQueryString(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isBlank()) {
            return params;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            key = URLDecoder.decode(key, StandardCharsets.UTF_8);
            value = URLDecoder.decode(value, StandardCharsets.UTF_8);
            params.put(key, value);
        }
        return params;
    }

    private void writeResponse(OutputStream out, int status, String contentType, byte[] body) throws IOException {
        String statusText = statusText(status);
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 ").append(status).append(' ').append(statusText).append("\r\n");
        headers.append("Content-Type: ").append(contentType).append("\r\n");
        headers.append("Content-Length: ").append(body.length).append("\r\n");
        headers.append("Connection: close\r\n");
        headers.append("\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private String statusText(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            default -> "Unknown";
        };
    }
}
