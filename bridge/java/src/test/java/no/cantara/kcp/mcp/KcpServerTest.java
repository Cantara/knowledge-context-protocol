package no.cantara.kcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for KcpServer — invoke buildResources() and read handlers
 * directly, without a real MCP transport.
 */
class KcpServerTest {

    private static Path fixture(String name) {
        URL url = KcpServerTest.class.getClassLoader()
            .getResource("fixtures/" + name + "/knowledge.yaml");
        assertNotNull(url, "fixture not found: " + name);
        return Paths.get(url.getPath());
    }

    // ── create_server ─────────────────────────────────────────────────────────────

    @Test void buildResourcesReturnsResourcesAndHandlers() throws Exception {
        KcpServer.ResourceSet rs = KcpServer.buildResources(fixture("minimal"), false);
        assertFalse(rs.resources().isEmpty());
        assertFalse(rs.handlers().isEmpty());
    }

    @Test void buildResourcesThrowsOnMissingManifest() {
        assertThrows(Exception.class,
            () -> KcpServer.buildResources(Path.of("/nonexistent/knowledge.yaml"), false));
    }

    // ── list_resources ────────────────────────────────────────────────────────────

    @Test void minimalHasManifestPlusOneUnit() throws Exception {
        KcpServer.ResourceSet rs = KcpServer.buildResources(fixture("minimal"), false);
        // manifest + 1 unit
        assertEquals(2, rs.resources().size());
        List<String> names = rs.resources().stream().map(McpSchema.Resource::name).toList();
        assertTrue(names.contains("manifest"));
        assertTrue(names.contains("overview"));
    }

    @Test void fullHasAllUnits() throws Exception {
        KcpServer.ResourceSet rs = KcpServer.buildResources(fixture("full"), false);
        // manifest + 3 units
        assertEquals(4, rs.resources().size());
    }

    @Test void agentOnlyFiltersHumanUnits() throws Exception {
        // full fixture: spec (human+agent+developer), api-schema (developer+agent), guide (human+developer)
        // agent-only: spec + api-schema included; guide excluded
        KcpServer.ResourceSet rs = KcpServer.buildResources(fixture("full"), true);
        List<String> names = rs.resources().stream().map(McpSchema.Resource::name).toList();
        assertTrue(names.contains("manifest"));
        assertTrue(names.contains("spec"));
        assertTrue(names.contains("api-schema"));
        assertFalse(names.contains("guide"));
    }

    @Test void manifestHasPriority1() throws Exception {
        KcpServer.ResourceSet rs = KcpServer.buildResources(fixture("minimal"), false);
        McpSchema.Resource manifest = rs.resources().stream()
            .filter(r -> r.name().equals("manifest"))
            .findFirst().orElseThrow();
        assertEquals(1.0, manifest.annotations().priority());
    }

    @Test void unitUrisUseKnowledgeScheme() throws Exception {
        KcpServer.ResourceSet rs = KcpServer.buildResources(fixture("minimal"), false);
        McpSchema.Resource unit = rs.resources().stream()
            .filter(r -> r.name().equals("overview"))
            .findFirst().orElseThrow();
        assertTrue(unit.uri().startsWith("knowledge://"));
    }

    // ── read_resource ─────────────────────────────────────────────────────────────

    @Test void readManifestReturnsJson() throws Exception {
        KcpServer.ResourceSet rs = KcpServer.buildResources(fixture("minimal"), false);
        String mUri = rs.resources().stream()
            .filter(r -> r.name().equals("manifest"))
            .findFirst().orElseThrow().uri();

        McpSchema.ReadResourceResult result = rs.handlers().get(mUri).handle(mUri);
        assertEquals(1, result.contents().size());

        McpSchema.TextResourceContents text = (McpSchema.TextResourceContents) result.contents().get(0);
        assertEquals("application/json", text.mimeType());

        ObjectMapper om = new ObjectMapper();
        JsonNode body = om.readTree(text.text());
        assertEquals("my-project", body.get("project").asText());
        assertEquals(1, body.get("unit_count").asInt());
    }

    @Test void readUnitReturnsFileContent() throws Exception {
        KcpServer.ResourceSet rs = KcpServer.buildResources(fixture("minimal"), false);
        McpSchema.Resource unitRes = rs.resources().stream()
            .filter(r -> r.name().equals("overview"))
            .findFirst().orElseThrow();
        String uri = unitRes.uri();

        McpSchema.ReadResourceResult result = rs.handlers().get(uri).handle(uri);
        assertEquals(1, result.contents().size());
        McpSchema.TextResourceContents text = (McpSchema.TextResourceContents) result.contents().get(0);
        assertTrue(text.text().contains("My Project"));
    }

    @Test void readJsonUnitHasCorrectMimeType() throws Exception {
        KcpServer.ResourceSet rs = KcpServer.buildResources(fixture("full"), false);
        McpSchema.Resource apiRes = rs.resources().stream()
            .filter(r -> r.name().equals("api-schema"))
            .findFirst().orElseThrow();
        String uri = apiRes.uri();

        McpSchema.ReadResourceResult result = rs.handlers().get(uri).handle(uri);
        McpSchema.TextResourceContents text = (McpSchema.TextResourceContents) result.contents().get(0);
        assertEquals("application/schema+json", text.mimeType());

        ObjectMapper om = new ObjectMapper();
        JsonNode body = om.readTree(text.text());
        assertTrue(body.has("$schema"));
    }

    @Test void readUnknownHandlerReturnsNull() throws Exception {
        KcpServer.ResourceSet rs = KcpServer.buildResources(fixture("minimal"), false);
        // No handler for unknown URI — map returns null (SDK would reject this)
        assertNull(rs.handlers().get("knowledge://my-project/nonexistent"));
    }
}
