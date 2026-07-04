package no.cantara.kcp.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** C20 (v0.22): attestation gating. attest-hub sets require_attestation with a restricted
 *  ("secret") and a public ("public-doc") unit. The bridge refuses restricted content unless
 *  a credential is presented — it never verifies the credential. */
class KcpAttestationGatingTest {

    private static Path fixture(String name) {
        URL url = KcpAttestationGatingTest.class.getClassLoader().getResource("fixtures/" + name + "/knowledge.yaml");
        assertNotNull(url, "fixture not found: " + name);
        return Paths.get(url.getPath());
    }

    private static KcpServer.ResourceSet rs() throws Exception {
        return KcpServer.buildResources(fixture("attestation"), false);
    }

    private static String toolText(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }

    @Test void getUnitRefusesRestrictedWithoutAttestation() throws Exception {
        McpSchema.CallToolResult r = KcpServer.handleGetUnit(
            new McpSchema.CallToolRequest("get_unit", Map.of("unit_id", "secret")), rs(), "attest-hub");
        assertTrue(r.isError());
        String t = toolText(r);
        assertTrue(t.contains("attestation_required"), t);
        assertTrue(t.contains("https://acme.com/v1/attest"), t);
    }

    @Test void getUnitServesRestrictedWithAttestation() throws Exception {
        McpSchema.CallToolResult r = KcpServer.handleGetUnit(
            new McpSchema.CallToolRequest("get_unit", Map.of("unit_id", "secret", "attestation", "spiffe://acme.internal/agent")),
            rs(), "attest-hub");
        assertTrue(toolText(r).contains("Restricted design notes"), toolText(r));
    }

    @Test void getUnitServesPublicWithoutAttestation() throws Exception {
        McpSchema.CallToolResult r = KcpServer.handleGetUnit(
            new McpSchema.CallToolRequest("get_unit", Map.of("unit_id", "public-doc")), rs(), "attest-hub");
        assertTrue(toolText(r).contains("Anyone may read this"), toolText(r));
    }

    @Test void resourceReadRefusesRestricted() throws Exception {
        KcpServer.ResourceSet rs = rs();
        String uri = KcpMapper.unitUri("attest-hub", "secret");
        KcpServer.ResourceHandler h = rs.handlers().get(uri);
        assertNotNull(h, "handler should exist for restricted unit");
        Exception ex = assertThrows(Exception.class, () -> h.handle(uri));
        assertTrue(ex.getMessage().contains("requires agent attestation"), ex.getMessage());
    }

    @Test void searchMarksRestrictedDroppedWhenAttested() throws Exception {
        String unmarked = toolText(KcpServer.handleSearchKnowledge(
            new McpSchema.CallToolRequest("search_knowledge", Map.of("query", "secret design")), rs(), "attest-hub"));
        String attested = toolText(KcpServer.handleSearchKnowledge(
            new McpSchema.CallToolRequest("search_knowledge", Map.of("query", "secret design", "attestation", "spiffe://acme.internal/agent")),
            rs(), "attest-hub"));
        assertTrue(unmarked.contains("\"requires_attestation\":true"), unmarked);
        assertFalse(attested.contains("\"requires_attestation\":true"), attested);
    }
}
