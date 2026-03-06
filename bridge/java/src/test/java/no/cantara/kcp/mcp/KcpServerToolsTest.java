package no.cantara.kcp.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the v0.6.0 tool and prompt handlers in KcpServer.
 * Invokes the package-private handler methods directly — no MCP transport needed.
 */
class KcpServerToolsTest {

    private static KcpServer.ResourceSet fullRs;
    private static String fullSlug;
    private static Map<String, KcpCommands.CommandManifest> commandManifests;

    private static Path fixture(String name) {
        URL url = KcpServerToolsTest.class.getClassLoader()
            .getResource("fixtures/" + name + "/knowledge.yaml");
        assertNotNull(url, "fixture not found: " + name);
        return Paths.get(url.getPath());
    }

    private static Path commandsFixture() {
        URL url = KcpServerToolsTest.class.getClassLoader()
            .getResource("fixtures/commands/git-commit.yaml");
        assertNotNull(url, "commands fixture not found");
        return Paths.get(url.getPath()).getParent();
    }

    @BeforeAll static void setUp() throws Exception {
        fullRs = KcpServer.buildResources(fixture("full"), false);
        fullSlug = KcpMapper.projectSlug(fullRs.primaryManifest().project());
        commandManifests = KcpCommands.loadCommandManifests(commandsFixture());
    }

    // ── search_knowledge ──────────────────────────────────────────────────────

    @Test void searchKnowledgeReturnsMatchingResults() {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_knowledge",
            Map.of("query", "spec rules"));
        McpSchema.CallToolResult result = KcpServer.handleSearchKnowledge(request, fullRs, fullSlug);

        assertFalse(result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("\"id\":\"spec\""));
        assertTrue(text.contains("\"score\":"));
    }

    @Test void searchKnowledgeReturnsEmptyForNoMatch() {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_knowledge",
            Map.of("query", "zzz-nonexistent-zzz"));
        McpSchema.CallToolResult result = KcpServer.handleSearchKnowledge(request, fullRs, fullSlug);

        assertFalse(result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("No units matched"));
        assertTrue(text.contains("Available units:"));
    }

    @Test void searchKnowledgeEmptyQueryReturnsMessage() {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_knowledge",
            Map.of("query", "  "));
        McpSchema.CallToolResult result = KcpServer.handleSearchKnowledge(request, fullRs, fullSlug);

        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertEquals("Please provide a search query.", text);
    }

    @Test void searchKnowledgeTriggerMatchScoresHigher() {
        // "spec" is a trigger for the spec unit
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_knowledge",
            Map.of("query", "spec"));
        McpSchema.CallToolResult result = KcpServer.handleSearchKnowledge(request, fullRs, fullSlug);

        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        // spec unit should be first (triggers match = 5 pts + intent/id/path points)
        assertTrue(text.indexOf("\"id\":\"spec\"") < text.indexOf("\"id\":\"api-schema\"")
            || !text.contains("\"id\":\"api-schema\""));
    }

    @Test void searchKnowledgeWithAudienceFilter() {
        // filter to "agent" — guide unit has audience [human, developer], should be excluded
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_knowledge",
            Map.of("query", "guide integration", "audience", "agent"));
        McpSchema.CallToolResult result = KcpServer.handleSearchKnowledge(request, fullRs, fullSlug);

        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        // guide has triggers [guide, integration] so it would match — but audience filter excludes it
        assertFalse(text.contains("\"id\":\"guide\""));
    }

    @Test void searchKnowledgeWithScopeFilter() {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_knowledge",
            Map.of("query", "schema json api", "scope", "module"));
        McpSchema.CallToolResult result = KcpServer.handleSearchKnowledge(request, fullRs, fullSlug);

        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        // api-schema is module scope, spec is global, guide is project
        assertTrue(text.contains("\"id\":\"api-schema\""));
        assertFalse(text.contains("\"id\":\"spec\""));
        assertFalse(text.contains("\"id\":\"guide\""));
    }

    // ── get_unit ──────────────────────────────────────────────────────────────

    @Test void getUnitReturnsContentForKnownId() {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get_unit",
            Map.of("unit_id", "spec"));
        McpSchema.CallToolResult result = KcpServer.handleGetUnit(request, fullRs, fullSlug);

        assertFalse(result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("Full Example")); // Content of full/README.md
    }

    @Test void getUnitReturnsErrorForUnknownId() {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get_unit",
            Map.of("unit_id", "nonexistent"));
        McpSchema.CallToolResult result = KcpServer.handleGetUnit(request, fullRs, fullSlug);

        assertTrue(result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("Unit not found"));
        assertTrue(text.contains("Available units:"));
    }

    // ── get_command_syntax ────────────────────────────────────────────────────

    @Test void getCommandSyntaxReturnsFormattedBlock() {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get_command_syntax",
            Map.of("command", "git commit"));
        McpSchema.CallToolResult result = KcpServer.handleGetCommandSyntax(request, commandManifests);

        assertFalse(result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("[kcp] git commit:"));
        assertTrue(text.contains("Usage:"));
    }

    @Test void getCommandSyntaxErrorForUnknownCommand() {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get_command_syntax",
            Map.of("command", "unknown-tool"));
        McpSchema.CallToolResult result = KcpServer.handleGetCommandSyntax(request, commandManifests);

        assertTrue(result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("Unknown command"));
        assertTrue(text.contains("Available commands:"));
    }

    @Test void getCommandSyntaxErrorWhenNoManifests() {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get_command_syntax",
            Map.of("command", "git"));
        McpSchema.CallToolResult result = KcpServer.handleGetCommandSyntax(request, null);

        assertTrue(result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("No command manifests loaded"));
    }

    // ── sdd-review prompt ─────────────────────────────────────────────────────

    @Test void sddReviewPromptDefaultsToArchitecture() {
        McpSchema.GetPromptRequest request = new McpSchema.GetPromptRequest("sdd-review", Map.of());
        McpSchema.GetPromptResult result = KcpServer.handleSddReview(request);

        assertEquals(1, result.messages().size());
        McpSchema.PromptMessage msg = result.messages().get(0);
        assertEquals(McpSchema.Role.USER, msg.role());
        String text = ((McpSchema.TextContent) msg.content()).text();
        assertTrue(text.contains("## SDD Review: architecture"));
        assertTrue(text.contains("Intent Clarity"));
        assertTrue(text.contains("Component Boundaries"));
    }

    @Test void sddReviewPromptWithSecurityFocus() {
        McpSchema.GetPromptRequest request = new McpSchema.GetPromptRequest("sdd-review",
            Map.of("focus", "security"));
        McpSchema.GetPromptResult result = KcpServer.handleSddReview(request);

        String text = ((McpSchema.TextContent) result.messages().get(0).content()).text();
        assertTrue(text.contains("## SDD Review: security"));
        assertTrue(text.contains("Input Validation"));
        assertTrue(text.contains("Path Traversal"));
    }

    @Test void sddReviewPromptWithQualityFocus() {
        McpSchema.GetPromptRequest request = new McpSchema.GetPromptRequest("sdd-review",
            Map.of("focus", "quality"));
        McpSchema.GetPromptResult result = KcpServer.handleSddReview(request);

        String text = ((McpSchema.TextContent) result.messages().get(0).content()).text();
        assertTrue(text.contains("## SDD Review: quality"));
        assertTrue(text.contains("Test Coverage"));
    }

    @Test void sddReviewPromptWithPerformanceFocus() {
        McpSchema.GetPromptRequest request = new McpSchema.GetPromptRequest("sdd-review",
            Map.of("focus", "performance"));
        McpSchema.GetPromptResult result = KcpServer.handleSddReview(request);

        String text = ((McpSchema.TextContent) result.messages().get(0).content()).text();
        assertTrue(text.contains("## SDD Review: performance"));
        assertTrue(text.contains("Hot Paths"));
    }

    // ── kcp-explore prompt ────────────────────────────────────────────────────

    @Test void kcpExplorePromptIncludesTopic() {
        McpSchema.GetPromptRequest request = new McpSchema.GetPromptRequest("kcp-explore",
            Map.of("topic", "authentication"));
        McpSchema.GetPromptResult result = KcpServer.handleKcpExplore(request);

        assertEquals(1, result.messages().size());
        McpSchema.PromptMessage msg = result.messages().get(0);
        assertEquals(McpSchema.Role.USER, msg.role());
        String text = ((McpSchema.TextContent) msg.content()).text();
        assertTrue(text.contains("## Explore Knowledge: authentication"));
        assertTrue(text.contains("search_knowledge"));
        assertTrue(text.contains("authentication"));
    }

    // ── ResourceSet expanded record ───────────────────────────────────────────

    @Test void resourceSetContainsUnitMaps() throws Exception {
        KcpServer.ResourceSet rs = KcpServer.buildResources(fixture("full"), false);
        assertNotNull(rs.units());
        assertNotNull(rs.unitDirs());
        assertNotNull(rs.primaryManifest());
        assertEquals(3, rs.units().size());
        assertEquals(3, rs.unitDirs().size());
        assertEquals("full-example", rs.primaryManifest().project());
    }

    // ── scoreUnit ─────────────────────────────────────────────────────────────

    @Test void scoreUnitTriggerMatchGives5Points() throws Exception {
        // The spec unit has triggers: [spec, rules, normative]
        var unit = fullRs.units().get("spec");
        KcpServer.SearchResult result = KcpServer.scoreUnit(unit, List.of("spec"), fullSlug);
        // trigger "spec" = 5, intent contains "spec" might not match, id "spec" = 1, path might not
        assertTrue(result.score() >= 5, "trigger match should give at least 5 points");
    }

    @Test void scoreUnitNoMatchGivesZero() throws Exception {
        var unit = fullRs.units().get("spec");
        KcpServer.SearchResult result = KcpServer.scoreUnit(unit, List.of("zzzzzzz"), fullSlug);
        assertEquals(0, result.score());
    }
}
