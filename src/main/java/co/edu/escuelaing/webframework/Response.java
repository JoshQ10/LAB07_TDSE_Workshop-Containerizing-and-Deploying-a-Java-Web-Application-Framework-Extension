package co.edu.escuelaing.webframework;

/**
 * Mutable response metadata a route handler can adjust before returning
 * its body. The body itself is the lambda's return value, not a field
 * here.
 */
public class Response {

    private String contentType = "text/plain; charset=UTF-8";
    private int status = 200;

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getContentType() {
        return contentType;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
