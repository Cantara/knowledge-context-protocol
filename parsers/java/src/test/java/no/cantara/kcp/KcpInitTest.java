package no.cantara.kcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KcpInitTest {

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    private void createProjectDir(Path dir) throws IOException {
        Files.writeString(dir.resolve("README.md"),
                "# My Test Project\n\nThis is a test project for KCP.\n\n## Getting Started\n\nRun it.\n");
        Path docs = dir.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("architecture.md"),
                "# Architecture\n\nThe system uses a layered architecture.\n");
    }

    // --- discoverArtifacts ---

    @Test
    void discoverArtifacts_findsReadme(@TempDir Path dir) throws IOException {
        createProjectDir(dir);
        List<Path> artifacts = KcpInit.discoverArtifacts(dir);
        List<String> relPaths = artifacts.stream()
                .map(a -> dir.relativize(a).toString().replace('\\', '/'))
                .toList();
        assertTrue(relPaths.contains("README.md"));
    }

    @Test
    void discoverArtifacts_findsDocs(@TempDir Path dir) throws IOException {
        createProjectDir(dir);
        List<Path> artifacts = KcpInit.discoverArtifacts(dir);
        List<String> relPaths = artifacts.stream()
                .map(a -> dir.relativize(a).toString().replace('\\', '/'))
                .toList();
        assertTrue(relPaths.stream().anyMatch(p -> p.contains("architecture.md")));
    }

    @Test
    void discoverArtifacts_emptyDir(@TempDir Path dir) {
        List<Path> artifacts = KcpInit.discoverArtifacts(dir);
        assertTrue(artifacts.isEmpty());
    }

    // --- detectProjectName ---

    @Test
    void detectProjectName_fromPackageJson(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("package.json"), "{\"name\": \"my-npm-project\"}");
        assertEquals("my-npm-project", KcpInit.detectProjectName(dir));
    }

    @Test
    void detectProjectName_fromPomXml(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"),
                "<project><artifactId>my-maven-project</artifactId></project>");
        assertEquals("my-maven-project", KcpInit.detectProjectName(dir));
    }

    @Test
    void detectProjectName_fallbackToDirName(@TempDir Path dir) {
        String name = KcpInit.detectProjectName(dir);
        assertNotNull(name);
        assertFalse(name.isEmpty());
    }

    // --- extractDescription ---

    @Test
    void extractDescription_fromReadme(@TempDir Path dir) throws IOException {
        createProjectDir(dir);
        String desc = KcpInit.extractDescription(dir);
        assertTrue(desc.toLowerCase().contains("test project"));
    }

    @Test
    void extractDescription_noReadme(@TempDir Path dir) {
        assertEquals("", KcpInit.extractDescription(dir));
    }

    // --- generateManifest Level 1 ---

    @Test
    @SuppressWarnings("unchecked")
    void generateManifest_level1_validYaml(@TempDir Path dir) throws IOException {
        createProjectDir(dir);
        List<Path> artifacts = KcpInit.discoverArtifacts(dir);
        String content = KcpInit.generateManifest(dir, artifacts, 1);

        Map<String, Object> data = YAML.load(content);
        assertEquals("0.11", data.get("kcp_version"));
        assertEquals("0.1.0", data.get("version"));

        List<Map<String, Object>> units = (List<Map<String, Object>>) data.get("units");
        assertNotNull(units);
        assertEquals(artifacts.size(), units.size());
        for (Map<String, Object> unit : units) {
            assertNotNull(unit.get("id"));
            assertNotNull(unit.get("path"));
            assertNotNull(unit.get("intent"));
            assertNotNull(unit.get("scope"));
            assertNotNull(unit.get("audience"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void generateManifest_level1_noValidatedOrHints(@TempDir Path dir) throws IOException {
        createProjectDir(dir);
        List<Path> artifacts = KcpInit.discoverArtifacts(dir);
        String content = KcpInit.generateManifest(dir, artifacts, 1);

        Map<String, Object> data = YAML.load(content);
        List<Map<String, Object>> units = (List<Map<String, Object>>) data.get("units");
        for (Map<String, Object> unit : units) {
            assertNull(unit.get("validated"), "Level 1 should not have validated");
            assertNull(unit.get("hints"), "Level 1 should not have hints");
        }
    }

    // --- generateManifest Level 2 ---

    @Test
    @SuppressWarnings("unchecked")
    void generateManifest_level2_hasValidatedAndHints(@TempDir Path dir) throws IOException {
        createProjectDir(dir);
        List<Path> artifacts = KcpInit.discoverArtifacts(dir);
        String content = KcpInit.generateManifest(dir, artifacts, 2);

        Map<String, Object> data = YAML.load(content);
        List<Map<String, Object>> units = (List<Map<String, Object>>) data.get("units");
        for (Map<String, Object> unit : units) {
            assertNotNull(unit.get("validated"), "Level 2 should have validated");
            assertNotNull(unit.get("hints"), "Level 2 should have hints");
            Map<String, Object> hints = (Map<String, Object>) unit.get("hints");
            assertNotNull(hints.get("token_estimate"));
        }
    }

    // --- generateManifest Level 3 ---

    @Test
    @SuppressWarnings("unchecked")
    void generateManifest_level3_hasTriggers(@TempDir Path dir) throws IOException {
        createProjectDir(dir);
        List<Path> artifacts = KcpInit.discoverArtifacts(dir);
        String content = KcpInit.generateManifest(dir, artifacts, 3);

        Map<String, Object> data = YAML.load(content);
        List<Map<String, Object>> units = (List<Map<String, Object>>) data.get("units");
        // At least one unit should have triggers
        boolean hasTriggers = units.stream().anyMatch(u -> u.get("triggers") != null);
        assertTrue(hasTriggers, "Level 3 should have triggers on at least one unit");
    }

    // --- extractTriggers ---

    @Test
    void extractTriggers_fromFilename(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("architecture.md");
        Files.writeString(f, "# Architecture Overview\n\nSome content.\n");
        List<String> triggers = KcpInit.extractTriggers(f);
        assertTrue(triggers.contains("architecture"));
    }

    @Test
    void extractTriggers_limitsToThree(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("very-long-name-with-many-parts.md");
        Files.writeString(f, "# One Two Three Four Five\n");
        List<String> triggers = KcpInit.extractTriggers(f);
        assertTrue(triggers.size() <= 3);
    }

    // --- run (integration) ---

    @Test
    void run_createsKnowledgeYaml(@TempDir Path dir) throws IOException {
        createProjectDir(dir);
        int result = KcpInit.run(dir, 1, false, false);
        assertEquals(0, result);
        assertTrue(Files.exists(dir.resolve("knowledge.yaml")));
    }

    @Test
    void run_refusesToOverwrite(@TempDir Path dir) throws IOException {
        createProjectDir(dir);
        Files.writeString(dir.resolve("knowledge.yaml"), "existing: content\n");
        int result = KcpInit.run(dir, 1, false, false);
        assertEquals(1, result);
        assertEquals("existing: content\n", Files.readString(dir.resolve("knowledge.yaml")));
    }

    @Test
    void run_forceOverwrites(@TempDir Path dir) throws IOException {
        createProjectDir(dir);
        Files.writeString(dir.resolve("knowledge.yaml"), "existing: content\n");
        int result = KcpInit.run(dir, 1, false, true);
        assertEquals(0, result);
        String content = Files.readString(dir.resolve("knowledge.yaml"));
        assertTrue(content.contains("kcp_version"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void run_outputIsParseable(@TempDir Path dir) throws IOException {
        createProjectDir(dir);
        KcpInit.run(dir, 2, false, false);
        String content = Files.readString(dir.resolve("knowledge.yaml"));
        Map<String, Object> data = YAML.load(content);
        assertEquals("0.11", data.get("kcp_version"));
        List<Map<String, Object>> units = (List<Map<String, Object>>) data.get("units");
        assertFalse(units.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void run_uniqueIds(@TempDir Path dir) throws IOException {
        // Two files with the same base name should get unique ids
        Files.writeString(dir.resolve("README.md"), "# Top-level\n");
        Path docs = dir.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("README.md"), "# Docs readme\n");

        KcpInit.run(dir, 1, false, false);
        String content = Files.readString(dir.resolve("knowledge.yaml"));
        Map<String, Object> data = YAML.load(content);
        List<Map<String, Object>> units = (List<Map<String, Object>>) data.get("units");
        List<String> ids = units.stream().map(u -> (String) u.get("id")).toList();
        assertEquals(ids.size(), ids.stream().distinct().count(), "All ids should be unique: " + ids);
    }
}
