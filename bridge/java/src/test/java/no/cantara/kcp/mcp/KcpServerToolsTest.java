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

    private static KcpServer.ResourceSet rfc007Rs;
    private static String rfc007Slug;

    @BeforeAll static void setUp() throws Exception {
        fullRs = KcpServer.buildResources(fixture("full"), false);
        fullSlug = KcpMapper.projectSlug(fullRs.primaryManifest().project());
        commandManifests = KcpCommands.loadCommandManifests(commandsFixture());
        rfc007Rs = KcpServer.buildResources(fixture("rfc007"), false);
        rfc007Slug = KcpMapper.projectSlug(rfc007Rs.primaryManifest().project());
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

    // ── list_manifests ───────────────────────────────────────────────────────

    @Test void listManifestsReturnsEmptyArrayWhenNoFederationBlock() throws Exception {
        // "full" fixture has no manifests block
        McpSchema.CallToolResult result = KcpServer.handleListManifests(fullRs.primaryManifest());

        assertFalse(result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        // Empty manifests list: opening "[\n" + closing "\n]" = "[\n\n]"
        assertTrue(text.trim().equals("[]") || text.equals("[\n\n]"),
            "Expected empty JSON array, got: " + text);
    }

    @Test void listManifestsReturnsManifestEntriesFromFederationBlock() throws Exception {
        KcpServer.ResourceSet fedRs = KcpServer.buildResources(fixture("federation"), false);

        McpSchema.CallToolResult result = KcpServer.handleListManifests(fedRs.primaryManifest());

        assertFalse(result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("\"id\":\"platform\""));
        assertTrue(text.contains("\"url\":\"https://example.com/platform/knowledge.yaml\""));
        assertTrue(text.contains("\"label\":\"Platform Team\""));
        assertTrue(text.contains("\"relationship\":\"foundation\""));
        assertTrue(text.contains("\"has_local_mirror\":false"));
        assertTrue(text.contains("\"update_frequency\":\"weekly\""));
        assertTrue(text.contains("\"id\":\"security\""));
        assertTrue(text.contains("\"label\":\"Security Team\""));
        assertTrue(text.contains("\"relationship\":\"governs\""));
    }

    // ── RFC-0007 query baseline ───────────────────────────────────────────────

    @Test void searchKnowledgeReturnsMatchReason() {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_knowledge",
            Map.of("query", "authentication"));
        McpSchema.CallToolResult result = KcpServer.handleSearchKnowledge(request, rfc007Rs, rfc007Slug);

        assertFalse(result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        // match_reason must be present and contain "trigger" (authentication is in triggers)
        assertTrue(text.contains("\"match_reason\":"), "Expected match_reason field");
        assertTrue(text.contains("\"trigger\""), "Expected trigger in match_reason");
    }

    @Test void searchKnowledgeReturnsTokenEstimateAndSummaryUnit() {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_knowledge",
            Map.of("query", "authentication"));
        McpSchema.CallToolResult result = KcpServer.handleSearchKnowledge(request, rfc007Rs, rfc007Slug);

        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        // auth-guide has hints.token_estimate: 4200 and hints.summary_unit: auth-tldr
        assertTrue(text.contains("\"token_estimate\":4200"), "Expected token_estimate 4200");
        assertTrue(text.contains("\"summary_unit\":\"auth-tldr\""), "Expected summary_unit auth-tldr");
    }

    @Test void searchKnowledgeExcludesDeprecatedByDefault() {
        // old-api has deprecated: true and triggers [api, endpoints, legacy]
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_knowledge",
            Map.of("query", "api endpoints legacy"));
        McpSchema.CallToolResult result = KcpServer.handleSearchKnowledge(request, rfc007Rs, rfc007Slug);

        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertFalse(text.contains("\"id\":\"old-api\""),
            "Deprecated unit should be excluded by default");
    }

    @Test void searchKnowledgeIncludesDeprecatedWhenFlagFalse() {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_knowledge",
            Map.of("query", "api endpoints legacy", "exclude_deprecated", false));
        McpSchema.CallToolResult result = KcpServer.handleSearchKnowledge(request, rfc007Rs, rfc007Slug);

        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("\"id\":\"old-api\""),
            "Deprecated unit should appear when exclude_deprecated is false");
    }

    @Test void searchKnowledgeFiltersBySensitivityMax() {
        // secret-config has sensitivity: confidential; sensitivity_max: internal should exclude it
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_knowledge",
            Map.of("query", "config secrets credentials", "sensitivity_max", "internal"));
        McpSchema.CallToolResult result = KcpServer.handleSearchKnowledge(request, rfc007Rs, rfc007Slug);

        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertFalse(text.contains("\"id\":\"secret-config\""),
            "Confidential unit should be excluded when sensitivity_max is internal");
    }

    @Test void searchKnowledgeIncludesConfidentialWhenCeilingIsConfidential() {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_knowledge",
            Map.of("query", "config secrets credentials", "sensitivity_max", "confidential"));
        McpSchema.CallToolResult result = KcpServer.handleSearchKnowledge(request, rfc007Rs, rfc007Slug);

        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("\"id\":\"secret-config\""),
            "Confidential unit should appear when sensitivity_max is confidential");
    }
}
