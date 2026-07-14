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

    // ── unit aliases (v0.26, §4.2a) ─────────────────────────────────────────────

    @Test void getUnitResolvesAliasAndSurfacesMatchedAlias() throws Exception {
        KcpServer.ResourceSet rs = KcpServer.buildResources(fixture("aliases"), false);
        String slug = KcpMapper.projectSlug(rs.primaryManifest().project());
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get_unit",
            Map.of("unit_id", "reg-art-21-2b"));
        McpSchema.CallToolResult result = KcpServer.handleGetUnit(request, rs, slug);

        assertFalse(result.isError());
        String meta = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(meta.contains("\"matched_alias\":\"reg-art-21-2b\""), meta);
        assertTrue(meta.contains("\"canonical_id\":\"reg-art-021\""), meta);
        String body = ((McpSchema.TextContent) result.content().get(1)).text();
        assertTrue(body.contains("cybersecurity risk-management"), body);
    }

    @Test void getUnitByCanonicalIdHasNoAliasBlock() throws Exception {
        KcpServer.ResourceSet rs = KcpServer.buildResources(fixture("aliases"), false);
        String slug = KcpMapper.projectSlug(rs.primaryManifest().project());
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get_unit",
            Map.of("unit_id", "reg-art-021"));
        McpSchema.CallToolResult result = KcpServer.handleGetUnit(request, rs, slug);

        assertEquals(1, result.content().size());
        String body = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(body.contains("cybersecurity risk-management"), body);
    }

    @Test void searchMatchesAliasAndReportsMatchedAlias() throws Exception {
        KcpServer.ResourceSet rs = KcpServer.buildResources(fixture("aliases"), false);
        String slug = KcpMapper.projectSlug(rs.primaryManifest().project());
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_knowledge",
            Map.of("query", "reg-art-21-2c"));
        McpSchema.CallToolResult result = KcpServer.handleSearchKnowledge(request, rs, slug);

        String json = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(json.contains("\"id\":\"reg-art-021\""), json);
        assertTrue(json.contains("\"matched_alias\":\"reg-art-21-2c\""), json);
        assertTrue(json.contains("alias"), json); // match_reason includes "alias"
    }

    @Test void getUnitUnknownAliasStill404s() throws Exception {
        KcpServer.ResourceSet rs = KcpServer.buildResources(fixture("aliases"), false);
        String slug = KcpMapper.projectSlug(rs.primaryManifest().project());
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get_unit",
            Map.of("unit_id", "reg-art-21-2z"));
        McpSchema.CallToolResult result = KcpServer.handleGetUnit(request, rs, slug);

        assertTrue(result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("Unit not found"));
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
        McpSchema.CallToolResult result = KcpServer.handleListManifests(
            new McpSchema.CallToolRequest("list_manifests", Map.of()), fullRs);

        assertFalse(result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        // Empty manifests list: opening "[\n" + closing "\n]" = "[\n\n]"
        assertTrue(text.trim().equals("[]") || text.equals("[\n\n]"),
            "Expected empty JSON array, got: " + text);
    }

    @Test void listManifestsReturnsManifestEntriesFromFederationBlock() throws Exception {
        KcpServer.ResourceSet fedRs = KcpServer.buildResources(fixture("federation"), false);

        McpSchema.CallToolResult result = KcpServer.handleListManifests(
            new McpSchema.CallToolRequest("list_manifests", Map.of()), fedRs);

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

    // ── §15.11 not_for filtering ──────────────────────────────────────────────

    @Test void searchKnowledgeSoftDemotesNotForMatch() {
        // "configure" hits admin-console trigger → 5 pts; "end" matches "end user" → halved + caution
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_knowledge",
            Map.of("query", "configure end"));
        McpSchema.CallToolResult result = KcpServer.handleSearchKnowledge(request, rfc007Rs, rfc007Slug);

        assertFalse(result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("\"id\":\"admin-console\""), "Expected admin-console in results");
        assertTrue(text.contains("not_for match"), "Expected caution annotation for not_for match");
    }

    @Test void searchKnowledgeStrictlyExcludesNotForStrictMatch() {
        // "operations" hits internal-ops trigger; "external" matches not_for strict → excluded
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_knowledge",
            Map.of("query", "operations external"));
        McpSchema.CallToolResult result = KcpServer.handleSearchKnowledge(request, rfc007Rs, rfc007Slug);

        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertFalse(text.contains("\"id\":\"internal-ops\""),
            "Strict not_for unit should be excluded when query term matches not_for phrase");
    }

    // ── §15.13 temporal query filtering ──────────────────────────────────────

    @Test void searchKnowledgeExcludesTemporallyInactiveUnitsByDefault() {
        // future-feature (valid_from: 2099) and legacy-guide (valid_until: 2020) should be excluded
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_knowledge",
            Map.of("query", "future legacy integration"));
        McpSchema.CallToolResult result = KcpServer.handleSearchKnowledge(request, rfc007Rs, rfc007Slug);

        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertFalse(text.contains("\"id\":\"future-feature\""),
            "future-feature (valid_from 2099) should be excluded by default temporal filter");
        assertFalse(text.contains("\"id\":\"legacy-guide\""),
            "legacy-guide (valid_until 2020) should be excluded by default temporal filter");
    }

    @Test void searchKnowledgeIncludesTemporallyInactiveWhenFlagSet() {
        // include_all_temporal: true bypasses temporal filtering
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_knowledge",
            Map.of("query", "future feature upcoming", "include_all_temporal", true));
        McpSchema.CallToolResult result = KcpServer.handleSearchKnowledge(request, rfc007Rs, rfc007Slug);

        assertFalse(result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("\"id\":\"future-feature\""),
            "future-feature should appear when include_all_temporal is true");
    }

    // ── Federation temporal (RFC-0021 / C18) ──────────────────────────────────
    // The hub federates two GDPR corpora via local_mirror with disjoint source windows —
    // gdpr-2018 (valid_until 2023-09-01) and gdpr-2023 (valid_from 2023-09-01). Neither corpus
    // declares unit-level temporal, so manifests[].temporal is the only thing that includes or
    // excludes their units. Both consent units match "consent gdpr".

    private static Path subFixture(String name, String sub) {
        URL url = KcpServerToolsTest.class.getClassLoader()
            .getResource("fixtures/" + name + "/" + sub + "/knowledge.yaml");
        assertNotNull(url, "sub-fixture not found: " + name + "/" + sub);
        return Paths.get(url.getPath());
    }

    private static KcpServer.ResourceSet fedRs() throws Exception {
        return KcpServer.buildResources(
            fixture("fed-temporal"), false,
            List.of(subFixture("fed-temporal", "mirror-old"),
                    subFixture("fed-temporal", "mirror-new")));
    }

    private static String fedSearch(KcpServer.ResourceSet rs, Map<String, Object> args) {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_knowledge", args);
        McpSchema.CallToolResult result = KcpServer.handleSearchKnowledge(
            request, rs, KcpMapper.projectSlug(rs.primaryManifest().project()));
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    @Test void fedTemporalAsOfActiveWindowExcludesExpiredSource() throws Exception {
        // 2026 is after gdpr-2018's valid_until and inside gdpr-2023's window.
        String text = fedSearch(fedRs(), Map.of("query", "consent gdpr", "as_of", "2026-06-13"));
        assertTrue(text.contains("\"id\":\"gdpr-2023-consent\""), text);
        assertFalse(text.contains("\"id\":\"gdpr-2018-consent\""), text);
    }

    @Test void fedTemporalAsOfExpiredWindowIncludesIt() throws Exception {
        // 2020 is inside gdpr-2018's window and before gdpr-2023's valid_from.
        String text = fedSearch(fedRs(), Map.of("query", "consent gdpr", "as_of", "2020-01-01"));
        assertTrue(text.contains("\"id\":\"gdpr-2018-consent\""), text);
        assertFalse(text.contains("\"id\":\"gdpr-2023-consent\""), text);
    }

    @Test void fedTemporalIncludeAllReturnsBothSources() throws Exception {
        String text = fedSearch(fedRs(), Map.of("query", "consent gdpr", "include_all_temporal", true));
        assertTrue(text.contains("\"id\":\"gdpr-2018-consent\""), text);
        assertTrue(text.contains("\"id\":\"gdpr-2023-consent\""), text);
    }

    @Test void fedTemporalAsOfAndIncludeAllConflict() throws Exception {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search_knowledge",
            Map.of("query", "consent gdpr", "as_of", "2020-01-01", "include_all_temporal", true));
        McpSchema.CallToolResult result = KcpServer.handleSearchKnowledge(
            request, fedRs(), "fed-temporal-hub");
        assertTrue(result.isError());
        assertTrue(((McpSchema.TextContent) result.content().get(0)).text()
            .contains("temporal_query_conflict"));
    }

    @Test void fedTemporalListManifestsExposesTemporalAndActivity() throws Exception {
        KcpServer.ResourceSet rs = fedRs();
        McpSchema.CallToolResult result = KcpServer.handleListManifests(
            new McpSchema.CallToolRequest("list_manifests", Map.of()), rs);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("\"valid_until\":\"2023-09-01\""), text);
        // gdpr-2018 expired as of today; gdpr-2023 active. Both flags present.
        assertTrue(text.contains("\"temporally_active\":false"), text);
        assertTrue(text.contains("\"temporally_active\":true"), text);
    }

    // ── C18 hardening (issue #98) ─────────────────────────────────────────────
    private static String toolText(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }

    @Test void hF1_getUnitRefusesExpiredSource() throws Exception {
        McpSchema.CallToolResult r = KcpServer.handleGetUnit(
            new McpSchema.CallToolRequest("get_unit", Map.of("unit_id", "gdpr-2018-consent")),
            fedRs(), "fed-temporal-hub");
        assertTrue(r.isError());
        assertTrue(toolText(r).contains("temporally_unavailable"), toolText(r));
    }

    @Test void hF1_buildResourcesOmitsExpiredKeepsActive() throws Exception {
        KcpServer.ResourceSet rs = fedRs();
        java.util.Set<String> uris = new java.util.HashSet<>();
        for (McpSchema.Resource res : rs.resources()) uris.add(res.uri());
        assertTrue(uris.contains(KcpMapper.unitUri("fed-temporal-hub", "gdpr-2023-consent")), uris.toString());
        assertFalse(uris.contains(KcpMapper.unitUri("fed-temporal-hub", "gdpr-2018-consent")), uris.toString());
        // and no read handler exists for the excluded unit
        assertFalse(rs.handlers().containsKey(KcpMapper.unitUri("fed-temporal-hub", "gdpr-2018-consent")));
    }

    @Test void hF2_bindsThroughSymlinkedSubManifest() throws Exception {
        Path tmp = java.nio.file.Files.createTempDirectory("kcp-fed-");
        try {
            Path link = tmp.resolve("mirror-old-link");
            java.nio.file.Files.createSymbolicLink(link,
                fixture("fed-temporal").getParent().resolve("mirror-old"));
            KcpServer.ResourceSet rs = KcpServer.buildResources(fixture("fed-temporal"), false,
                List.of(link.resolve("knowledge.yaml"), subFixture("fed-temporal", "mirror-new")));
            McpSchema.CallToolResult r = KcpServer.handleGetUnit(
                new McpSchema.CallToolRequest("get_unit", Map.of("unit_id", "gdpr-2018-consent")),
                rs, "fed-temporal-hub");
            assertTrue(toolText(r).contains("temporally_unavailable"), toolText(r));
        } finally {
            try { java.nio.file.Files.walk(tmp).sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete()); } catch (Exception ignore) {}
        }
    }

    @Test void hF3_invalidAsOfRejected() throws Exception {
        McpSchema.CallToolResult r = KcpServer.handleSearchKnowledge(
            new McpSchema.CallToolRequest("search_knowledge", Map.of("query", "consent gdpr", "as_of", "not-a-date")),
            fedRs(), "fed-temporal-hub");
        assertTrue(r.isError());
        assertTrue(toolText(r).contains("invalid_as_of"), toolText(r));
    }

    @Test void hF4_supersessionDropsSupersededOnBoundary() throws Exception {
        String text = fedSearch(fedRs(), Map.of("query", "consent gdpr", "as_of", "2023-09-01"));
        assertTrue(text.contains("\"id\":\"gdpr-2023-consent\""), text);
        assertFalse(text.contains("\"id\":\"gdpr-2018-consent\""), text);
    }

    @Test void hF5_includeAllTemporalMarksCaution() throws Exception {
        String text = fedSearch(fedRs(), Map.of("query", "consent gdpr", "include_all_temporal", true));
        assertTrue(text.contains("temporal filtering bypassed"), text);
    }

    @Test void hF9_listManifestsHonoursAsOf() throws Exception {
        McpSchema.CallToolResult r = KcpServer.handleListManifests(
            new McpSchema.CallToolRequest("list_manifests", Map.of("as_of", "2020-01-01")), fedRs());
        String text = toolText(r);
        // 2018 active in 2020, 2023 not yet valid: expect at least one true and one false,
        // and specifically the 2018 entry present with its window.
        assertTrue(text.contains("\"id\":\"gdpr-2018\""), text);
        assertTrue(text.contains("\"temporally_active\":true"), text);
        assertTrue(text.contains("\"temporally_active\":false"), text);
    }
}
