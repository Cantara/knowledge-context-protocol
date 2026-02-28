package no.cantara.kcp.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Reads knowledge unit files with path-traversal protection.
 */
public final class KcpContent {

    private KcpContent() {}

    /** Thrown when the resolved path escapes the manifest directory. */
    public static class PathTraversalException extends IllegalArgumentException {
        public PathTraversalException(String msg) { super(msg); }
    }

    /** Thrown when the resource file does not exist. */
    public static class ResourceNotFoundException extends IllegalArgumentException {
        public ResourceNotFoundException(String msg) { super(msg); }
    }

    /**
     * Result from reading a resource file.
     *
     * @param text   UTF-8 text content (binary=false), or base64 string (binary=true)
     * @param binary true if the file was read as binary
     */
    public record ContentResult(String text, boolean binary) {}

    /**
     * Reads the content of a unit file.
     *
     * @param manifestDir root directory of the knowledge.yaml
     * @param unitPath    relative path to the file (from the manifest root)
     * @param mime        resolved MIME type (used to decide binary vs text)
     * @return ContentResult with text or base64-encoded binary content
     * @throws PathTraversalException    if unitPath escapes manifestDir
     * @throws ResourceNotFoundException if the file does not exist
     * @throws IOException               on I/O error
     */
    public static ContentResult read(Path manifestDir, String unitPath, String mime)
            throws IOException {
        Path root     = manifestDir.toRealPath();
        Path resolved = root.resolve(unitPath).normalize();

        if (!resolved.startsWith(root)) {
            throw new PathTraversalException(
                "Path traversal attempt: '" + unitPath + "' escapes manifest directory");
        }

        if (!Files.exists(resolved)) {
            throw new ResourceNotFoundException("File not found: " + unitPath);
        }

        if (KcpMapper.isBinaryMime(mime)) {
            byte[] bytes  = Files.readAllBytes(resolved);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            return new ContentResult(base64, true);
        } else {
            String text = Files.readString(resolved);
            return new ContentResult(text, false);
        }
    }
}
