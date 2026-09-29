package co.edu.escuelaing.webframework;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps a request path to the lambda registered for it. Adding a route
 * only touches this map — never the server's connection loop.
 */
public class Router {

    private final Map<String, GetService> getRoutes = new HashMap<>();

    public void addGetRoute(String path, GetService service) {
        getRoutes.put(path, service);
    }

    /**
     * Returns the handler registered for the given path, or
     * {@code null} if no dynamic route matches it.
     */
    public GetService resolve(String path) {
        return getRoutes.get(path);
    }
}
