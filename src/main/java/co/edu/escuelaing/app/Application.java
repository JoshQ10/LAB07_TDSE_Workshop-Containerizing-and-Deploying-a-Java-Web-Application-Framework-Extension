package co.edu.escuelaing.app;

import static co.edu.escuelaing.webframework.WebFramework.*;

import java.time.LocalDateTime;

/**
 * Example application built on top of the webframework: static
 * resources plus a handful of lambda-based GET services.
 */
public class Application {

    public static void main(String[] args) throws Exception {

        staticfiles("/webroot");

        get("/hello", (req, resp) -> {
            String name = req.getValue("name");
            if (name == null || name.isBlank()) {
                name = "world";
            }
            String greetingPrefix = System.getenv().getOrDefault("GREETING_PREFIX", "Hello");
            return greetingPrefix + " " + name;
        });

        // Same contract as the workshop's Spring Boot endpoint.
        get("/greeting", (req, resp) -> {
            String name = req.getValue("name");
            return "Hello, " + (name == null || name.isBlank() ? "World" : name) + "!";
        });

        // Deliberately slow: used to show that requests run in parallel
        // and that a shutdown waits for in-flight requests to finish.
        get("/slow", (req, resp) -> {
            String seconds = req.getValue("seconds");
            int delay = (seconds == null || seconds.isBlank()) ? 3 : Integer.parseInt(seconds);
            Thread.sleep(delay * 1000L);
            return "Finished after " + delay + "s on " + Thread.currentThread().getName();
        });

        get("/pi", (req, resp) -> String.valueOf(Math.PI));

        get("/date", (req, resp) -> LocalDateTime.now().toString());

        String environment = System.getenv().getOrDefault("APP_ENV", "development");
        if (environment.equals("development")) {
            get("/shutdown", (req, resp) -> {
                stop();
                return "Server will stop after this response.";
            });
        }

        start();
    }
}
