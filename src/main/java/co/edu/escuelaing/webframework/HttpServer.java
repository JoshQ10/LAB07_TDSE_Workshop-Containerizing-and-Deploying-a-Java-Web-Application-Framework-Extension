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

/**
 * The accept loop. Handles exactly one connection at a time: read the
 * request line, resolve it against the router or the static-file
 * service, write one response, close the socket, accept the next one.
 * Registering a route never requires touching this class.
 */
public class HttpServer {

    private final Router router;
    private final StaticFileService staticFileService;
    private volatile boolean running = false;

    public HttpServer(Router router, StaticFileService staticFileService) {
        this.router = router;
        this.staticFileService = staticFileService;
    }

    public void start(int port) throws IOException {
        running = true;
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server listening on port " + port);
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    try {
                        handleConnection(clientSocket);
                    } finally {
                        clientSocket.close();
                    }
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Error handling connection: " + e.getMessage());
                    }
                }
            }
        }
        System.out.println("Server stopped gracefully.");
    }

    /** Marks the server as no longer running; the current request still completes normally. */
    public void stop() {
        running = false;
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
