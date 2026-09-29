package co.edu.escuelaing.webframework;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class StaticFileServiceTest {

    private final StaticFileService service = new StaticFileService();

    StaticFileServiceTest() {
        service.setBaseFolder("/webroot");
    }

    @Test
    void readsAnExistingResource() throws IOException {
        byte[] bytes = service.read("/index.html");

        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
    }

    @Test
    void defaultsTheRootPathToIndexHtml() throws IOException {
        assertNotNull(service.read("/"));
    }

    @Test
    void returnsNullForAMissingResource() throws IOException {
        assertNull(service.read("/does-not-exist.html"));
    }

    @Test
    void rejectsPathTraversalAttempts() throws IOException {
        assertNull(service.read("/../pom.xml"));
    }

    @Test
    void resolvesContentTypesByExtension() {
        assertEquals("text/html; charset=UTF-8", StaticFileService.contentTypeFor("/index.html"));
        assertEquals("application/javascript; charset=UTF-8", StaticFileService.contentTypeFor("/app.js"));
        assertEquals("image/png", StaticFileService.contentTypeFor("/images/logo.png"));
    }

    @Test
    void resolvesTheRootPathToIndexHtmlForContentTypePurposes() {
        // Regression test: the content type must be computed from the
        // resolved file name, not the raw "/" request path, or browsers
        // download the page instead of rendering it.
        String resolved = service.resolvePath("/");

        assertEquals("index.html", resolved);
        assertEquals("text/html; charset=UTF-8", StaticFileService.contentTypeFor(resolved));
    }
}
