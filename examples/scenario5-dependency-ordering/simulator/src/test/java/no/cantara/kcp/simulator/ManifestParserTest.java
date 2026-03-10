package no.cantara.kcp.simulator;

import no.cantara.kcp.simulator.model.KnowledgeUnit;
import no.cantara.kcp.simulator.model.Relationship;
import no.cantara.kcp.simulator.parser.ManifestParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManifestParserTest {

    private ManifestParser.ParsedManifest parseYaml(String yaml) {
        return ManifestParser.parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesUnitsAndRelationships() {
        String yaml = """
                kcp_version: "0.9"
                project: test
                version: "1.0.0"
                units:
                  - id: A
                    path: docs/a.md
                    intent: "Unit A"
                    depends_on: [B]
                  - id: B
                    path: docs/b.md
                    intent: "Unit B"
                relationships:
                  - from: A
                    to: B
                    type: enables
                """;
        var result = parseYaml(yaml);
        assertEquals(2, result.units().size());
        assertEquals(1, result.relationships().size());
        assertEquals("enables", result.relationships().get(0).type());
    }

    @Test
    void parsesSupersedes() {
        String yaml = """
                kcp_version: "0.9"
                project: test
                version: "1.0.0"
                units:
                  - id: new-api
                    path: docs/new.md
                    intent: "New API"
                    supersedes: old-api
                  - id: old-api
                    path: docs/old.md
                    intent: "Old API"
                    deprecated: true
                """;
        var result = parseYaml(yaml);
        assertEquals("old-api", result.units().get(0).supersedes());
        assertTrue(result.units().get(1).deprecated());
    }

    @Test
    void parsesAllRelationshipTypes() {
        String yaml = """
                kcp_version: "0.9"
                project: test
                version: "1.0.0"
                units:
                  - id: A
                    path: a.md
                    intent: "A"
                  - id: B
                    path: b.md
                    intent: "B"
                relationships:
                  - from: A
                    to: B
                    type: depends_on
                  - from: A
                    to: B
                    type: enables
                  - from: A
                    to: B
                    type: supersedes
                  - from: A
                    to: B
                    type: contradicts
                  - from: A
                    to: B
                    type: context
                """;
        var result = parseYaml(yaml);
        assertEquals(5, result.relationships().size());
        var types = result.relationships().stream().map(Relationship::type).toList();
        assertTrue(types.contains("depends_on"));
        assertTrue(types.contains("enables"));
        assertTrue(types.contains("supersedes"));
        assertTrue(types.contains("contradicts"));
        assertTrue(types.contains("context"));
    }

    @Test
    void parsesAccessAndSensitivity() {
        String yaml = """
                kcp_version: "0.9"
                project: test
                version: "1.0.0"
                units:
                  - id: restricted
                    path: docs/restricted.md
                    intent: "Restricted"
                    access: restricted
                    sensitivity: confidential
                """;
        var result = parseYaml(yaml);
        assertEquals("restricted", result.units().get(0).access());
        assertEquals("confidential", result.units().get(0).sensitivity());
    }

    @Test
    void parsesRealManifest() throws Exception {
        Path manifestPath = Path.of("../knowledge.yaml");
        if (!manifestPath.toFile().exists()) {
            manifestPath = Path.of("/src/cantara/knowledge-context-protocol/examples/scenario5-dependency-ordering/knowledge.yaml");
        }
        if (!manifestPath.toFile().exists()) return;

        var result = ManifestParser.parse(manifestPath);
        assertEquals(8, result.units().size());
        assertTrue(result.relationships().size() >= 10);

        // Check all 5 types present
        var types = result.relationships().stream().map(Relationship::type).distinct().toList();
        assertTrue(types.contains("depends_on"));
        assertTrue(types.contains("enables"));
        assertTrue(types.contains("supersedes"));
        assertTrue(types.contains("contradicts"));
        assertTrue(types.contains("context"));
    }
}
