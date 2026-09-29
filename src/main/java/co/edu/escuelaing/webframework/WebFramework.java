package co.edu.escuelaing.webframework;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Public entry point of the framework. An application uses only this
 * class (typically via a static import) and never touches
 * {@link HttpServer}, {@link Router} or {@link StaticFileService}
 * directly:
 *
 * <pre>{@code
 * import static co.edu.escuelaing.webframework.WebFramework.*;
 *
 * staticfiles("/webroot");
 * get("/hello", (req, resp) -> "Hello " + req.getValue("name"));
 * start();
 * }</pre>
 */
public final class WebFramework {

    private static final Router router = new Router();
    private static final StaticFileService staticFileService = new StaticFileService();
    private static final int POOL_SIZE = intFromEnv("THREAD_POOL_SIZE", 10);
    private static final int SHUTDOWN_TIMEOUT_SECONDS = intFromEnv("SHUTDOWN_TIMEOUT_SECONDS", 8);
    private static final HttpServer server =
            new HttpServer(router, staticFileService, POOL_SIZE, SHUTDOWN_TIMEOUT_SECONDS);

    private WebFramework() {
    }

    /** Configures the classpath folder (or STATIC_FILES_PATH override) resources are served from. */
    public static void staticfiles(String folder) {
        staticFileService.setBaseFolder(folder);
    }

    /** Registers a lambda to handle GET requests for the given path. */
    public static void get(String path, GetService service) {
        router.addGetRoute(path, service);
    }

    /** Starts the server on the port read from the PORT environment variable (default 8080). */
    public static void start() throws IOException {
        start(resolvePort());
    }

    /**
     * Starts the server on an explicit port. The call blocks until
     * {@link #stop()} is invoked or the JVM receives SIGTERM/SIGINT
     * (e.g. {@code docker stop} or Ctrl+C), which triggers the same
     * graceful shutdown through a shutdown hook.
     */
    public static void start(int port) throws IOException {
        Runtime.getRuntime().addShutdownHook(new Thread(WebFramework::shutdownFromSignal, "shutdown-hook"));
        server.start(port);
    }

    /** Requests a graceful shutdown: in-flight requests still get their response before the server exits. */
    public static void stop() {
        server.stop();
    }

    /**
     * The JVM exits as soon as shutdown hooks return, so the hook must
     * wait for the pool to drain — otherwise in-flight requests would be
     * cut off.
     */
    private static void shutdownFromSignal() {
        server.stop();
        try {
            if (server.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS + 2L, TimeUnit.SECONDS)) {
                System.out.println("Shutdown complete.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int resolvePort() {
        return intFromEnv("PORT", 8080);
    }

    private static int intFromEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? defaultValue : Integer.parseInt(value.trim());
    }
}
