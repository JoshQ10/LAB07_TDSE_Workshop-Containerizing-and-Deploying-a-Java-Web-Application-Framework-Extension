package co.edu.escuelaing.webframework;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RouterTest {

    @Test
    void resolvesARegisteredRoute() throws Exception {
        Router router = new Router();
        router.addGetRoute("/hello", (req, resp) -> "hi");

        GetService resolved = router.resolve("/hello");

        assertNotNull(resolved);
        assertEquals("hi", resolved.handle(null, null));
    }

    @Test
    void returnsNullForAnUnregisteredRoute() {
        Router router = new Router();
        router.addGetRoute("/hello", (req, resp) -> "hi");

        assertNull(router.resolve("/unknown"));
    }
}
