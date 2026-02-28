package no.cantara.kcp.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import no.cantara.kcp.KcpParser;
import no.cantara.kcp.model.KnowledgeUnit;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pure unit tests for KcpMapper — no I/O, no MCP transport. */
class KcpMapperTest {

    // ── helpers ───────────────────────────────────────────────────────────────────

    private static KnowledgeUnit unit(Map<String, Object> overrides) {
        Map<String, Object> defaults = new java.util.HashMap<>();
        defaults.put("id",       "spec");
        defaults.put("path",     "SPEC.md");
        defaults.put("intent",   "What are the rules?");
        defaults.put("scope",    "global");
        defaults.put("audience", List.of("human", "agent"));
        defaults.putAll(overrides);
        return KcpParser.fromMap(Map.of(
            "project", "test", "version", "1.0.0",
            "units", List.of(defaults)
        )).units().get(0);
    }

    private static KnowledgeUnit unit() {
        return unit(Map.of());
    }

    // ── projectSlug ───────────────────────────────────────────────────────────────

    @Test void slugLowercasesAndHyphenates() {
        assertEquals("my-project", KcpMapper.projectSlug("My Project"));
    }

    @Test void slugCollapsesSpaces() {
        assertEquals("knowledge-context-protocol",
            KcpMapper.projectSlug("Knowledge Context Protocol"));
    }

    @Test void slugAlreadyClean() {
        assertEquals("kcp", KcpMapper.projectSlug("kcp"));
    }

    @Test void slugRemovesSpecialChars() {
        assertEquals("wikitottoorg", KcpMapper.projectSlug("wiki.totto.org"));
    }

    // ── unit_uri / manifest_uri ───────────────────────────────────────────────────

    @Test void unitUri() {
        assertEquals("knowledge://my-project/spec",
            KcpMapper.unitUri("my-project", "spec"));
    }

    @Test void manifestUri() {
        assertEquals("knowledge://my-project/manifest",
            KcpMapper.manifestUri("my-project"));
    }

    // ── isBinaryMime ──────────────────────────────────────────────────────────────

    @Test void pdfIsBinary() {
        assertTrue(KcpMapper.isBinaryMime("application/pdf"));
    }

    @Test void imageIsBinary() {
        assertTrue(KcpMapper.isBinaryMime("image/png"));
    }

    @Test void markdownIsNotBinary() {
        assertFalse(KcpMapper.isBinaryMime("text/markdown"));
    }

    @Test void jsonIsNotBinary() {
        assertFalse(KcpMapper.isBinaryMime("application/json"));
    }

    // ── resolveMime ───────────────────────────────────────────────────────────────

    @Test void contentTypeWins() {
        KnowledgeUnit u = unit(Map.of("content_type", "application/schema+json", "format", "markdown"));
        assertEquals("application/schema+json", KcpMapper.resolveMime(u));
    }

    @Test void formatLookup() {
        KnowledgeUnit u = unit(Map.of("format", "openapi"));
        assertEquals("application/vnd.oai.openapi+yaml", KcpMapper.resolveMime(u));
    }

    @Test void extensionFallbackMd() {
        KnowledgeUnit u = unit(Map.of("path", "docs/guide.md"));
        assertEquals("text/markdown", KcpMapper.resolveMime(u));
    }

    @Test void extensionFallbackYaml() {
        KnowledgeUnit u = unit(Map.of("path", "schema.yaml"));
        assertEquals("application/yaml", KcpMapper.resolveMime(u));
    }

    @Test void extensionFallbackJson() {
        KnowledgeUnit u = unit(Map.of("path", "api.json"));
        assertEquals("application/json", KcpMapper.resolveMime(u));
    }

    @Test void unknownExtensionDefaultsPlain() {
        KnowledgeUnit u = unit(Map.of("path", "file.xyz"));
        assertEquals("text/plain", KcpMapper.resolveMime(u));
    }

    // ── mapAudience ───────────────────────────────────────────────────────────────

    @Test void agentMapsToAssistant() {
        assertTrue(KcpMapper.mapAudience(List.of("agent")).contains(McpSchema.Role.ASSISTANT));
    }

    @Test void humanMapsToUser() {
        assertTrue(KcpMapper.mapAudience(List.of("human")).contains(McpSchema.Role.USER));
    }

    @Test void mixedAudienceGetsBoth() {
        List<McpSchema.Role> roles = KcpMapper.mapAudience(List.of("human", "agent"));
        assertTrue(roles.contains(McpSchema.Role.USER));
        assertTrue(roles.contains(McpSchema.Role.ASSISTANT));
    }

    @Test void emptyAudienceDefaultsToUser() {
        assertEquals(List.of(McpSchema.Role.USER), KcpMapper.mapAudience(List.of()));
    }

    @Test void developerMapsToUser() {
        assertTrue(KcpMapper.mapAudience(List.of("developer")).contains(McpSchema.Role.USER));
    }

    // ── buildDescription ─────────────────────────────────────────────────────────

    @Test void descriptionContainsTriggers() {
        KnowledgeUnit u = unit(Map.of("triggers", List.of("spec", "rules")));
        assertTrue(KcpMapper.buildDescription(u).contains("Triggers: spec, rules"));
    }

    @Test void descriptionContainsDependsOn() {
        KnowledgeUnit u = unit(Map.of("depends_on", List.of("overview")));
        assertTrue(KcpMapper.buildDescription(u).contains("Depends on: overview"));
    }

    // ── buildUnitResource ─────────────────────────────────────────────────────────

    @Test void unitResourceUri() {
        McpSchema.Resource r = KcpMapper.buildUnitResource("slug", unit());
        assertEquals("knowledge://slug/spec", r.uri());
    }

    @Test void unitResourceName() {
        McpSchema.Resource r = KcpMapper.buildUnitResource("slug", unit());
        assertEquals("spec", r.name());
    }

    @Test void unitResourceGlobalPriority() {
        McpSchema.Resource r = KcpMapper.buildUnitResource("slug", unit(Map.of("scope", "global")));
        assertEquals(1.0, r.annotations().priority());
    }

    @Test void unitResourceModulePriority() {
        McpSchema.Resource r = KcpMapper.buildUnitResource("slug", unit(Map.of("scope", "module")));
        assertEquals(0.5, r.annotations().priority());
    }

    @Test void unitResourceLastModified() {
        KnowledgeUnit u = unit(Map.of("validated", "2026-02-27"));
        McpSchema.Resource r = KcpMapper.buildUnitResource("slug", u);
        assertEquals("2026-02-27T00:00:00Z", r.annotations().lastModified());
    }

    @Test void unitResourceNoLastModifiedWhenAbsent() {
        McpSchema.Resource r = KcpMapper.buildUnitResource("slug", unit());
        assertNull(r.annotations().lastModified());
    }

    // ── buildManifestResource ─────────────────────────────────────────────────────

    @Test void manifestResourceName() {
        McpSchema.Resource r = KcpMapper.buildManifestResource("test");
        assertEquals("manifest", r.name());
    }

    @Test void manifestResourceUri() {
        McpSchema.Resource r = KcpMapper.buildManifestResource("test");
        assertEquals("knowledge://test/manifest", r.uri());
    }

    @Test void manifestResourceMimeType() {
        McpSchema.Resource r = KcpMapper.buildManifestResource("test");
        assertEquals("application/json", r.mimeType());
    }

    @Test void manifestResourcePriority() {
        McpSchema.Resource r = KcpMapper.buildManifestResource("test");
        assertEquals(1.0, r.annotations().priority());
    }

    // ── buildManifestJson ─────────────────────────────────────────────────────────

    @Test void manifestJsonStructure() throws Exception {
        var manifest = KcpParser.fromMap(Map.of(
            "project", "test",
            "version", "1.0.0",
            "units", List.of(Map.of(
                "id", "spec", "path", "SPEC.md",
                "intent", "What is this?",
                "scope", "global",
                "audience", List.of("agent"),
                "triggers", List.of("spec")
            )),
            "relationships", List.of(
                Map.of("from", "spec", "to", "spec", "type", "context")
            )
        ));
        String json = KcpMapper.buildManifestJson(manifest, "test");

        // parse with Jackson for assertions
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        var body = om.readTree(json);

        assertEquals("test", body.get("project").asText());
        assertEquals(1, body.get("unit_count").asInt());
        assertEquals("spec", body.get("units").get(0).get("id").asText());
        assertEquals("knowledge://test/spec", body.get("units").get(0).get("uri").asText());
        assertEquals("spec", body.get("relationships").get(0).get("from").asText());
    }
}
