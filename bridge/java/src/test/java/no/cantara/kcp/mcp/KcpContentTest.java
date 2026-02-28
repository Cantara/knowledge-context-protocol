package no.cantara.kcp.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for KcpContent — file reading and path-traversal guard. */
class KcpContentTest {

    @TempDir Path tempDir;

    @Test void readsTextFile(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("README.md"), "# Hello");
        KcpContent.ContentResult r = KcpContent.read(dir, "README.md", "text/markdown");
        assertFalse(r.binary());
        assertEquals("# Hello", r.text());
    }

    @Test void readsBinaryFileAsBase64(@TempDir Path dir) throws IOException {
        byte[] bytes = {0x50, 0x4E, 0x47}; // PNG magic bytes prefix
        Files.write(dir.resolve("logo.png"), bytes);
        KcpContent.ContentResult r = KcpContent.read(dir, "logo.png", "image/png");
        assertTrue(r.binary());
        assertArrayEquals(bytes, Base64.getDecoder().decode(r.text()));
    }

    @Test void readsFileInSubdirectory(@TempDir Path dir) throws IOException {
        Path sub = dir.resolve("docs");
        Files.createDirectory(sub);
        Files.writeString(sub.resolve("guide.md"), "# Guide");
        KcpContent.ContentResult r = KcpContent.read(dir, "docs/guide.md", "text/markdown");
        assertEquals("# Guide", r.text());
    }

    @Test void throwsForMissingFile(@TempDir Path dir) {
        assertThrows(KcpContent.ResourceNotFoundException.class,
            () -> KcpContent.read(dir, "missing.md", "text/markdown"));
    }

    @Test void throwsForPathTraversal(@TempDir Path dir) throws IOException {
        // Create a real parent and child temp dirs
        Path parent = dir.resolve("parent");
        Path child  = dir.resolve("parent/child");
        Files.createDirectories(child);
        Files.writeString(parent.resolve("secret.txt"), "secret");

        assertThrows(KcpContent.PathTraversalException.class,
            () -> KcpContent.read(child, "../secret.txt", "text/plain"));
    }

    @Test void jsonFileIsNotBinary(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("api.json"), "{\"hello\":\"world\"}");
        KcpContent.ContentResult r = KcpContent.read(dir, "api.json", "application/json");
        assertFalse(r.binary());
        assertTrue(r.text().contains("hello"));
    }
}
