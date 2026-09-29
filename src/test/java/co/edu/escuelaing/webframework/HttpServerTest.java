package co.edu.escuelaing.webframework;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HttpServerTest {

    private static final long SLOW_MILLIS = 1000;

    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        Router router = new Router();
        router.addGetRoute("/slow", (req, resp) -> {
            Thread.sleep(SLOW_MILLIS);
            return Thread.currentThread().getName();
        });
        router.addGetRoute("/fast", (req, resp) -> "ok");

        server = new HttpServer(router, new StaticFileService(), 4, 5);
        port = freePort();
        Thread serverThread = new Thread(() -> {
            try {
                server.start(port);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        serverThread.start();
        waitUntilListening();
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        server.stop();
        server.awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    void handlesSlowRequestsInParallelOnDifferentWorkers() throws Exception {
        long begin = System.nanoTime();
        List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(client.sendAsync(get("/slow"), HttpResponse.BodyHandlers.ofString()));
        }
        Set<String> workers = new HashSet<>();
        for (CompletableFuture<HttpResponse<String>> call : calls) {
            HttpResponse<String> response = call.get(10, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode());
            workers.add(response.body());
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        // Sequentially this would take ~3 x SLOW_MILLIS.
        assertTrue(elapsedMillis < 2 * SLOW_MILLIS, "took " + elapsedMillis + " ms");
        assertEquals(3, workers.size(), "each request should run on its own worker: " + workers);
    }

    @Test
    void aSlowRequestDoesNotBlockAFastOne() throws Exception {
        CompletableFuture<HttpResponse<String>> slow = client.sendAsync(get("/slow"), HttpResponse.BodyHandlers.ofString());
        Thread.sleep(100);

        long begin = System.nanoTime();
        HttpResponse<String> fast = client.send(get("/fast"), HttpResponse.BodyHandlers.ofString());
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertEquals("ok", fast.body());
        assertTrue(elapsedMillis < SLOW_MILLIS / 2, "fast request waited " + elapsedMillis + " ms");
        assertEquals(200, slow.get(10, TimeUnit.SECONDS).statusCode());
    }

    @Test
    void gracefulShutdownLetsInFlightRequestsFinishAndRefusesNewOnes() throws Exception {
        CompletableFuture<HttpResponse<String>> inFlight = client.sendAsync(get("/slow"), HttpResponse.BodyHandlers.ofString());
        Thread.sleep(200);

        server.stop();

        assertThrows(ConnectException.class, () -> new Socket("localhost", port).close());
        HttpResponse<String> response = inFlight.get(10, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().startsWith("worker-"));
        assertTrue(server.awaitTermination(10, TimeUnit.SECONDS));
        assertFalse(server.isRunning());
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void waitUntilListening() throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            try (Socket ignored = new Socket("localhost", port)) {
                return;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }
        fail("server did not start on port " + port);
    }
}
