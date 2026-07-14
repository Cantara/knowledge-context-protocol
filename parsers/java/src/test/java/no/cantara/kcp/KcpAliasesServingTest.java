package no.cantara.kcp;

import no.cantara.kcp.model.KnowledgeManifest;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** v0.26: unit aliases (RFC-0023 §4.2a) + serving endpoint binding (RFC-0024 §3.12). */
class KcpAliasesServingTest {

    @SuppressWarnings("unchecked")
    private static KnowledgeManifest parse(String yaml) {
        return KcpParser.fromMap((Map<String, Object>) new Yaml().load(yaml));
    }

    @Test void parsesAliasesAndServing() {
        KnowledgeManifest m = parse("""
            kcp_version: "0.26"
            project: v26
            version: 1.0.0
            serving:
              manifest:
                - https://wiki.example.com/knowledge.yaml
                - https://mirror.example.org/knowledge.yaml
              mcp:
                - https://mcp.example.com/mcp
            units:
              - id: reg-art-021
                path: articles/art-021.txt
                intent: "What security measures are required?"
                scope: global
                audience: [agent]
                aliases: [reg-art-21-2a, reg-art-21-2b, reg-art-21-2c]
              - id: other
                path: b.txt
                intent: y
                scope: global
                audience: [agent]
            """);
        assertEquals(List.of("reg-art-21-2a", "reg-art-21-2b", "reg-art-21-2c"), m.units().get(0).aliases());
        assertNull(m.units().get(1).aliases());
        assertNotNull(m.serving());
        assertEquals(List.of("https://wiki.example.com/knowledge.yaml", "https://mirror.example.org/knowledge.yaml"),
                m.serving().manifest());
        assertEquals(List.of("https://mcp.example.com/mcp"), m.serving().mcp());

        KcpValidator.ValidationResult r = KcpValidator.validate(m);
        assertTrue(r.isValid(), () -> "unexpected errors: " + r.errors());
        assertFalse(r.warnings().stream().anyMatch(w -> w.contains("alias")),
                () -> "unexpected alias warnings: " + r.warnings());
    }

    @Test void warnsOnAliasCollisionAndBadChar() {
        KnowledgeManifest m = parse("""
            kcp_version: "0.26"
            project: bad
            version: 1.0.0
            units:
              - id: a
                path: a.txt
                intent: x
                scope: global
                audience: [agent]
                aliases: [b, "BAD Alias"]
              - id: b
                path: b.txt
                intent: y
                scope: global
                audience: [agent]
            """);
        List<String> w = KcpValidator.validate(m).warnings();
        assertTrue(w.stream().anyMatch(x -> x.contains("collides with an existing unit id")),
                () -> "expected collision warning, got: " + w);
        assertTrue(w.stream().anyMatch(x -> x.contains("must match")),
                () -> "expected bad-char warning, got: " + w);
    }

    @Test void servingRequiresHttps() {
        KnowledgeManifest m = parse("""
            kcp_version: "0.26"
            project: bad2
            version: 1.0.0
            serving:
              manifest: ["http://insecure/knowledge.yaml"]
            units:
              - {id: u, path: u.md, intent: x, scope: project, audience: [agent]}
            """);
        KcpValidator.ValidationResult r = KcpValidator.validate(m);
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(
                e -> e.contains("serving.manifest entry 'http://insecure/knowledge.yaml' must be an HTTPS URL")),
                () -> "expected HTTPS error, got: " + r.errors());
    }
}
