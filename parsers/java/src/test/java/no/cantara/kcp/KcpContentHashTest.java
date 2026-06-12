package no.cantara.kcp;

import no.cantara.kcp.model.ContentHash;
import no.cantara.kcp.model.KnowledgeManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for per-unit content hashes (RFC-0019, draft).
 *
 * <p>The directory-digest test re-implements the §3.2 rule independently so the
 * Java validator is cross-checked against the normative definition the same way
 * the TypeScript harness (experiments/rfc-0018-render, case A11) and the Python
 * test suite cross-check their implementations.
 */
class KcpContentHashTest {

    private static Map<String, Object> manifestWithContentHash(Object contentHash) {
        Map<String, Object> unit = new HashMap<>();
        unit.put("id", "setup");
        unit.put("path", "docs/setup.md");
        unit.put("intent", "Development environment description");
        unit.put("scope", "project");
        unit.put("audience", List.of("agent"));
        if (contentHash != null) unit.put("content_hash", contentHash);
        return Map.of(
                "kcp_version", "0.17",
                "project", "test",
                "version", "1.0.0",
                "units", List.of(unit)
        );
    }

    private static String sha256Hex(byte[] bytes) throws NoSuchAlgorithmException {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    void parsesContentHashBlock() {
        KnowledgeManifest manifest = KcpParser.fromMap(manifestWithContentHash(
                Map.of("algorithm", "sha256", "value", "ab".repeat(32))));
        assertEquals(new ContentHash("sha256", "ab".repeat(32)),
                manifest.units().get(0).contentHash());
    }

    @Test
    void malformedBlockParsesEmptyAndFailsValidation() {
        KnowledgeManifest manifest = KcpParser.fromMap(manifestWithContentHash("not-a-mapping"));
        assertEquals(new ContentHash(null, null), manifest.units().get(0).contentHash());
        KcpValidator.ValidationResult result = KcpValidator.validate(manifest);
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("content_hash.algorithm")));
    }

    @Test
    void shapeErrors() {
        KcpValidator.ValidationResult badAlgorithm = KcpValidator.validate(
                KcpParser.fromMap(manifestWithContentHash(Map.of("algorithm", "md5", "value", "abc123"))));
        assertTrue(badAlgorithm.errors().stream().anyMatch(e -> e.contains("content_hash.algorithm")));

        KcpValidator.ValidationResult badValue = KcpValidator.validate(
                KcpParser.fromMap(manifestWithContentHash(Map.of("algorithm", "sha256", "value", "not hex!"))));
        assertTrue(badValue.errors().stream().anyMatch(e -> e.contains("hex digest")));
    }

    @Test
    void recomputesAgainstDisk(@TempDir Path tmp) throws IOException, NoSuchAlgorithmException {
        Files.createDirectories(tmp.resolve("docs"));
        Files.write(tmp.resolve("docs/setup.md"), "content\n".getBytes(StandardCharsets.UTF_8));
        String digest = sha256Hex("content\n".getBytes(StandardCharsets.UTF_8));

        KcpValidator.ValidationResult fresh = KcpValidator.validate(
                KcpParser.fromMap(manifestWithContentHash(Map.of("algorithm", "sha256", "value", digest))), tmp);
        assertTrue(fresh.errors().stream().noneMatch(e -> e.contains("content_hash")));

        Files.write(tmp.resolve("docs/setup.md"), "edited\n".getBytes(StandardCharsets.UTF_8));
        KcpValidator.ValidationResult stale = KcpValidator.validate(
                KcpParser.fromMap(manifestWithContentHash(Map.of("algorithm", "sha256", "value", digest))), tmp);
        assertTrue(stale.errors().stream().anyMatch(e -> e.contains("does not match content on disk")));
    }

    @Test
    void directoryDigestMatchesIndependentImplementation(@TempDir Path tmp)
            throws IOException, NoSuchAlgorithmException {
        Path tree = tmp.resolve("tree");
        Files.createDirectories(tree.resolve("nested/deeper"));
        Files.write(tree.resolve("README.md"), "docs\n".getBytes(StandardCharsets.UTF_8));
        Files.write(tree.resolve(".hidden"), "dotfiles count too\n".getBytes(StandardCharsets.UTF_8));
        Files.write(tree.resolve("nested/empty.txt"), new byte[0]);
        try {
            // unicode filename exercises bytewise (not locale) ordering; skipped on
            // JVMs whose sun.jnu.encoding cannot represent it (POSIX-locale CI),
            // where the TS harness (A11) and Python suite keep the coverage
            Files.write(tree.resolve("nested/deeper/å-utf8.md"),
                    "unicode name\n".getBytes(StandardCharsets.UTF_8));
        } catch (java.nio.file.InvalidPathException ignored) {
            // proceed with the ASCII-only tree
        }

        // independent re-implementation of the §3.2 rule
        List<String> files = new ArrayList<>();
        try (var stream = Files.walk(tree)) {
            stream.filter(Files::isRegularFile)
                    .forEach(f -> files.add(tree.relativize(f).toString().replace('\\', '/')));
        }
        files.sort((a, b) -> Arrays.compare(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));
        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        for (String rel : files) {
            String fileHex = sha256Hex(Files.readAllBytes(tree.resolve(rel)));
            expected.update((rel + "\0" + fileHex + "\n").getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder expectedHex = new StringBuilder();
        for (byte b : expected.digest()) expectedHex.append(String.format("%02x", b));

        assertEquals(expectedHex.toString(), KcpValidator.computeContentDigest(tree, "sha256"));
    }

    @Test
    void missingTargetIsUnreadable(@TempDir Path tmp) {
        assertNull(KcpValidator.computeContentDigest(tmp.resolve("absent"), "sha256"));
    }
}
