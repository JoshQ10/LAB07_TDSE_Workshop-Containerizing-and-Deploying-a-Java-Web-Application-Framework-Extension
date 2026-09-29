package co.edu.escuelaing.webframework;

/**
 * Lambda contract for a GET route handler, e.g.
 * {@code get("/hello", (req, resp) -> "Hello " + req.getValue("name"));}
 */
@FunctionalInterface
public interface GetService {
    String handle(Request request, Response response) throws Exception;
}
