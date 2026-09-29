package co.edu.escuelaing.webframework;

import java.util.Collections;
import java.util.Map;

/**
 * Read-only view of an incoming HTTP request: method, path and
 * query-string parameters.
 */
public class Request {

    private final String method;
    private final String path;
    private final Map<String, String> queryParams;

    public Request(String method, String path, Map<String, String> queryParams) {
        this.method = method;
        this.path = path;
        this.queryParams = Collections.unmodifiableMap(queryParams);
    }

    /**
     * Returns the value of a query-string parameter, or {@code null}
     * if it was not present in the request.
     */
    public String getValue(String name) {
        return queryParams.get(name);
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public Map<String, String> getQueryParams() {
        return queryParams;
    }
}
