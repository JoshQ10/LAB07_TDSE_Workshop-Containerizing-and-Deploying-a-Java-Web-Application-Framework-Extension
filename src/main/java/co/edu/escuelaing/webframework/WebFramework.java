package co.edu.escuelaing.webframework;

import java.io.IOException;

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
    private static final HttpServer server = new HttpServer(router, staticFileService);

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

    /** Starts the server on an explicit port. The call blocks until {@link #stop()} is invoked. */
    public static void start(int port) throws IOException {
        server.start(port);
    }

    /** Requests a graceful shutdown: the in-flight request still gets its response before the server exits. */
    public static void stop() {
        server.stop();
    }

    private static int resolvePort() {
        String portValue = System.getenv("PORT");
        return (portValue == null || portValue.isBlank()) ? 8080 : Integer.parseInt(portValue);
    }
}
