package co.edu.escuelaing.webframework;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RequestTest {

    @Test
    void returnsAPresentQueryParam() {
        Request request = new Request("GET", "/hello", Map.of("name", "Pedro", "language", "en"));

        assertEquals("Pedro", request.getValue("name"));
        assertEquals("en", request.getValue("language"));
    }

    @Test
    void returnsNullForAMissingQueryParamInsteadOfFailing() {
        Request request = new Request("GET", "/hello", Map.of());

        assertNull(request.getValue("name"));
    }
}
