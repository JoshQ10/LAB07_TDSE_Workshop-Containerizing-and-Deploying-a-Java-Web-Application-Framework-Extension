package co.edu.escuelaing.webframework;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Serves the static resources configured through
 * {@code WebFramework.staticfiles(...)}. Resources are read from the
 * classpath by default (so they travel bundled inside the jar), or from
 * an external filesystem folder when the {@code STATIC_FILES_PATH}
 * environment variable is set.
 */
public class StaticFileService {

    private String baseFolder = "/webroot";

    public void setBaseFolder(String folder) {
        if (folder == null || folder.isBlank()) {
            throw new IllegalArgumentException("Static files folder cannot be null or blank");
        }
        this.baseFolder = folder.startsWith("/") ? folder : "/" + folder;
    }

    /**
     * Reads the resource for the given, already URL-decoded, request
     * path. Returns {@code null} when the resource does not exist or the
     * path attempts to escape the configured folder.
     */
    public byte[] read(String requestPath) throws IOException {
        String relative = normalize(requestPath);
        if (relative == null) {
            return null;
        }

        String externalPath = System.getenv("STATIC_FILES_PATH");
        if (externalPath != null && !externalPath.isBlank()) {
            Path file = Paths.get(externalPath, relative.split("/"));
            if (!Files.exists(file) || Files.isDirectory(file)) {
                return null;
            }
            return Files.readAllBytes(file);
        }

        String resourcePath = (baseFolder + "/" + relative).replace("//", "/");
        try (InputStream is = StaticFileService.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                return null;
            }
            return is.readAllBytes();
        }
    }

    public static String contentTypeFor(String path) {
        return ContentTypes.forPath(path);
    }

    /**
     * Resolves a request path to the relative resource name that will
     * actually be read (defaulting "/" to "index.html"), or {@code null}
     * if the path attempts to escape the configured folder. Callers use
     * this to compute the Content-Type from the *resolved* name — using
     * the raw request path ("/") would never match a file extension.
     */
    public String resolvePath(String requestPath) {
        return normalize(requestPath);
    }

    /**
     * Defaults "/" to "index.html" and resolves "." / ".." segments on
     * a stack so a traversal attempt can never resolve outside the
     * configured folder. The caller is expected to have already
     * URL-decoded the path, so a percent-encoded ".." is caught too.
     */
    private String normalize(String requestPath) {
        String path = requestPath.isEmpty() || requestPath.equals("/") ? "/index.html" : requestPath;

        Deque<String> stack = new ArrayDeque<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (stack.isEmpty()) {
                    return null;
                }
                stack.removeLast();
            } else {
                stack.addLast(segment);
            }
        }
        return String.join("/", stack);
    }
}
