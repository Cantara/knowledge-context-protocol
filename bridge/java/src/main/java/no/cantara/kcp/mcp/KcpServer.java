package no.cantara.kcp.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import no.cantara.kcp.KcpParser;
import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Builds and returns a configured MCP server for a KCP manifest.
 */
public final class KcpServer {

    private KcpServer() {}

    // ── Internal helpers (package-private for tests) ──────────────────────────────

    /**
     * Holds the static resource list, per-URI read handlers, and unit context
     * built from a manifest.
     * Package-private so tests can invoke handlers and tool logic directly
     * without a transport.
     */
    record ResourceSet(
        List<McpSchema.Resource> resources,
        Map<String, ResourceHandler> handlers,
        Map<String, KnowledgeUnit> units,
        Map<String, Path> unitDirs,
        KnowledgeManifest primaryManifest,
        int totalUnits
    ) {}

    @FunctionalInterface
    interface ResourceHandler {
        McpSchema.ReadResourceResult handle(String uri);
    }

    /**
     * Parses the manifest and builds all resources and their read handlers.
     * Convenience overload — no sub-manifests.
     */
    static ResourceSet buildResources(Path manifestPath, boolean agentOnly) throws IOException {
        return buildResources(manifestPath, agentOnly, List.of());
    }

    /**
     * Parses the manifest and builds all resources and their read handlers.
     * Units from subManifestPaths are merged; primary manifest wins on duplicate id.
     * Extracted for direct testing without a transport.
     */
    static ResourceSet buildResources(
            Path manifestPath,
            boolean agentOnly,
            List<Path> subManifestPaths) throws IOException {

        KnowledgeManifest manifest = KcpParser.parse(manifestPath);
        Path manifestDir = manifestPath.getParent();
        String slug  = KcpMapper.projectSlug(manifest.project());
        String mUri  = KcpMapper.manifestUri(slug);
        String mJson = KcpMapper.buildManifestJson(manifest, slug);

        List<McpSchema.Resource>     resources = new ArrayList<>();
        Map<String, ResourceHandler> handlers  = new LinkedHashMap<>();

        // ── manifest meta-resource ────────────────────────────────────────────────
        resources.add(KcpMapper.buildManifestResource(slug));
        handlers.put(mUri, uri ->
            new McpSchema.ReadResourceResult(
                List.of(new McpSchema.TextResourceContents(uri, "application/json", mJson, null)),
                null
            )
        );

        // ── unit context: unit.id -> [unit, manifestDir]  (primary wins on dup) ───
        Map<String, KnowledgeUnit> unitMap  = new LinkedHashMap<>();
        Map<String, Path>          dirMap   = new LinkedHashMap<>();
        for (KnowledgeUnit unit : manifest.units()) {
            unitMap.put(unit.id(), unit);
            dirMap.put(unit.id(), manifestDir);
        }

        // ── merge sub-manifests ───────────────────────────────────────────────────
        for (Path subPath : subManifestPaths) {
            Path resolvedSub = subPath.toAbsolutePath();
            Path subDir = resolvedSub.getParent();
            KnowledgeManifest subManifest;
            try {
                subManifest = KcpParser.parse(resolvedSub);
            } catch (Exception e) {
                System.err.printf("  [kcp-mcp] warning: could not load sub-manifest %s: %s%n",
                    resolvedSub, e.getMessage());
                continue;
            }
            int added = 0;
            for (KnowledgeUnit unit : subManifest.units()) {
                if (unitMap.containsKey(unit.id())) {
                    System.err.printf(
                        "  [kcp-mcp] warning: duplicate unit id '%s' in %s — skipping%n",
                        unit.id(), resolvedSub);
                    continue;
                }
                unitMap.put(unit.id(), unit);
                dirMap.put(unit.id(), subDir);
                added++;
            }
            System.err.printf("  [kcp-mcp] loaded sub-manifest %s — %d unit(s)%n",
                resolvedSub, added);
        }

        // ── build unit resources from merged context ──────────────────────────────
        for (Map.Entry<String, KnowledgeUnit> entry : unitMap.entrySet()) {
            KnowledgeUnit unit    = entry.getValue();
            Path          unitDir = dirMap.get(entry.getKey());

            if (agentOnly && !unit.audience().contains("agent")) continue;

            resources.add(KcpMapper.buildUnitResource(slug, unit));

            final String unitUri  = KcpMapper.unitUri(slug, unit.id());
            final String mime     = KcpMapper.resolveMime(unit);
            final String unitPath = unit.path();
            final Path finalUnitDir = unitDir;

            handlers.put(unitUri, uri -> {
                try {
                    KcpContent.ContentResult content = KcpContent.read(finalUnitDir, unitPath, mime);
                    if (content.binary()) {
                        return new McpSchema.ReadResourceResult(
                            List.of(new McpSchema.BlobResourceContents(uri, mime, content.text(), null)),
                            null
                        );
                    } else {
                        return new McpSchema.ReadResourceResult(
                            List.of(new McpSchema.TextResourceContents(uri, mime, content.text(), null)),
                            null
                        );
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        return new ResourceSet(resources, handlers, unitMap, dirMap, manifest, unitMap.size());
    }

    // ── Search scoring (package-private for tests) ─────────────────────────────

    record SearchResult(String id, String intent, String path, String uri, int score) {}

    /**
     * Score a unit against a set of query terms.
     * - trigger match: 5 pts each
     * - intent match: 3 pts each
     * - id/path match: 1 pt each
     */
    static SearchResult scoreUnit(KnowledgeUnit unit, List<String> terms, String slug) {
        int score = 0;
        List<String> lowerTriggers = unit.triggers() != null
            ? unit.triggers().stream().map(String::toLowerCase).toList()
            : List.of();
        String lowerIntent = unit.intent() != null ? unit.intent().toLowerCase() : "";
        String lowerId     = unit.id().toLowerCase();
        String lowerPath   = unit.path() != null ? unit.path().toLowerCase() : "";

        for (String term : terms) {
            String lterm = term.toLowerCase();

            // Trigger match — 5 pts per matching trigger
            for (String trig : lowerTriggers) {
                if (trig.contains(lterm)) score += 5;
            }

            // Intent match — 3 pts
            if (lowerIntent.contains(lterm)) score += 3;

            // Id match — 1 pt
            if (lowerId.contains(lterm)) score += 1;

            // Path match — 1 pt
            if (lowerPath.contains(lterm)) score += 1;
        }

        return new SearchResult(
            unit.id(), unit.intent(), unit.path(),
            KcpMapper.unitUri(slug, unit.id()), score);
    }

    // ── Public factories ──────────────────────────────────────────────────────

    /**
     * Convenience overload — no sub-manifests, no command manifests.
     */
    public static McpSyncServer createServer(
            Path manifestPath,
            McpServerTransportProvider transport,
            boolean agentOnly,
            boolean warnOnValidation) throws IOException {
        return createServer(manifestPath, transport, agentOnly, warnOnValidation, List.of(), null);
    }

    /**
     * Overload with sub-manifests but no command manifests.
     */
    public static McpSyncServer createServer(
            Path manifestPath,
            McpServerTransportProvider transport,
            boolean agentOnly,
            boolean warnOnValidation,
            List<Path> subManifestPaths) throws IOException {
        return createServer(manifestPath, transport, agentOnly, warnOnValidation, subManifestPaths, null);
    }

    /**
     * Full overload with sub-manifests and optional command manifests.
     *
     * @param manifestPath       path to knowledge.yaml
     * @param transport          MCP transport provider (e.g. StdioServerTransportProvider)
     * @param agentOnly          if true, expose only units with audience: [agent]
     * @param warnOnValidation   if true, log validation warnings to stderr
     * @param subManifestPaths   additional manifest paths whose units are merged
     * @param commandManifests   loaded command manifests (null = no get_command_syntax tool)
     */
    public static McpSyncServer createServer(
            Path manifestPath,
            McpServerTransportProvider transport,
            boolean agentOnly,
            boolean warnOnValidation,
            List<Path> subManifestPaths,
            Map<String, KcpCommands.CommandManifest> commandManifests) throws IOException {

        ResourceSet rs = buildResources(manifestPath, agentOnly, subManifestPaths);
        KnowledgeManifest manifest = rs.primaryManifest();
        String slug = KcpMapper.projectSlug(manifest.project());

        int primaryUnits = manifest.units().size();
        int totalUnits   = rs.totalUnits();
        int subUnits     = totalUnits - primaryUnits;

        String agentNote = agentOnly ? " [agent-only]" : "";
        String subNote   = subManifestPaths.isEmpty() ? "" :
            String.format(" (%d primary + %d from %d sub-manifest(s))",
                primaryUnits, subUnits, subManifestPaths.size());
        String cmdNote = commandManifests != null && !commandManifests.isEmpty()
            ? " + " + commandManifests.size() + " command(s)" : "";

        System.err.printf("[kcp-mcp] Serving '%s' — %d unit(s)%s%s%s%n",
            manifest.project(), totalUnits, subNote, agentNote, cmdNote);
        System.err.printf("[kcp-mcp] Start with: %s%n", KcpMapper.manifestUri(slug));

        McpSyncServer server = McpServer.sync(transport)
            .serverInfo("kcp-" + slug, "0.6.0")
            .capabilities(McpSchema.ServerCapabilities.builder()
                .resources(null, null)
                .tools(null)
                .prompts(null)
                .build())
            .build();

        // ── Register resources ────────────────────────────────────────────────
        for (McpSchema.Resource resource : rs.resources()) {
            final String uri = resource.uri();
            ResourceHandler handler = rs.handlers().get(uri);
            server.addResource(new McpServerFeatures.SyncResourceSpecification(
                resource,
                (exchange, request) -> handler.handle(request.uri())
            ));
        }

        // ── Register tools ────────────────────────────────────────────────────
        registerTools(server, rs, slug, commandManifests);

        // ── Register prompts ──────────────────────────────────────────────────
        registerPrompts(server);

        return server;
    }

    // ── Tool registration ─────────────────────────────────────────────────────

    private static void registerTools(
            McpSyncServer server,
            ResourceSet rs,
            String slug,
            Map<String, KcpCommands.CommandManifest> commandManifests) {

        // Tool: search_knowledge
        McpSchema.JsonSchema searchSchema = new McpSchema.JsonSchema(
            "object",
            Map.of(
                "query", Map.of("type", "string", "description", "Search terms (space-separated)"),
                "audience", Map.of("type", "string", "description",
                    "Filter by audience: agent | developer | architect | operator | human"),
                "scope", Map.of("type", "string", "description",
                    "Filter by scope: global | project | module")
            ),
            List.of("query"), null, null, null
        );

        server.addTool(new McpServerFeatures.SyncToolSpecification(
            new McpSchema.Tool("search_knowledge", null,
                "Search knowledge units by query. Matches against triggers, intent, and id.",
                searchSchema, null, null, null),
            (exchange, request) -> handleSearchKnowledge(request, rs, slug)
        ));

        // Tool: get_unit
        McpSchema.JsonSchema getUnitSchema = new McpSchema.JsonSchema(
            "object",
            Map.of(
                "unit_id", Map.of("type", "string", "description",
                    "The unit id from search_knowledge results")
            ),
            List.of("unit_id"), null, null, null
        );

        server.addTool(new McpServerFeatures.SyncToolSpecification(
            new McpSchema.Tool("get_unit", null,
                "Fetch the content of a specific knowledge unit by its id.",
                getUnitSchema, null, null, null),
            (exchange, request) -> handleGetUnit(request, rs, slug)
        ));

        // Tool: get_command_syntax (only if commandManifests loaded)
        if (commandManifests != null && !commandManifests.isEmpty()) {
            McpSchema.JsonSchema cmdSchema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "command", Map.of("type", "string", "description",
                        "Command name e.g. 'git commit', 'mvn', 'docker'")
                ),
                List.of("command"), null, null, null
            );

            server.addTool(new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("get_command_syntax", null,
                    "Get syntax guidance for a CLI command from kcp-commands manifests.",
                    cmdSchema, null, null, null),
                (exchange, request) -> handleGetCommandSyntax(request, commandManifests)
            ));
        }
    }

    // ── Tool handlers (package-private for testing) ───────────────────────────

    static McpSchema.CallToolResult handleSearchKnowledge(
            McpSchema.CallToolRequest request,
            ResourceSet rs,
            String slug) {

        Map<String, Object> args = request.arguments();
        String query = args != null && args.get("query") != null
            ? String.valueOf(args.get("query")) : "";
        String audienceFilter = args != null && args.get("audience") != null
            ? String.valueOf(args.get("audience")) : null;
        String scopeFilter = args != null && args.get("scope") != null
            ? String.valueOf(args.get("scope")) : null;

        if (query.isBlank()) {
            return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("Please provide a search query.")),
                false, null, null);
        }

        List<String> terms = List.of(query.trim().split("\\s+"));
        List<SearchResult> results = new ArrayList<>();

        for (Map.Entry<String, KnowledgeUnit> entry : rs.units().entrySet()) {
            KnowledgeUnit unit = entry.getValue();

            // Apply filters
            if (audienceFilter != null && (unit.audience() == null
                    || !unit.audience().contains(audienceFilter))) {
                continue;
            }
            if (scopeFilter != null && !scopeFilter.equals(unit.scope())) {
                continue;
            }

            SearchResult scored = scoreUnit(unit, terms, slug);
            if (scored.score() > 0) {
                results.add(scored);
            }
        }

        if (results.isEmpty()) {
            String ids = String.join(", ", rs.units().keySet());
            return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(
                    "No units matched query \"" + query + "\". Available units: " + ids)),
                false, null, null);
        }

        // Sort by score descending, take top 5
        results.sort((a, b) -> Integer.compare(b.score(), a.score()));
        List<SearchResult> top5 = results.subList(0, Math.min(5, results.size()));

        // Build JSON array manually (no Jackson in production)
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < top5.size(); i++) {
            SearchResult r = top5.get(i);
            if (i > 0) sb.append(",\n");
            sb.append("  {");
            sb.append("\"id\":\"").append(escapeJson(r.id())).append("\",");
            sb.append("\"intent\":\"").append(escapeJson(r.intent())).append("\",");
            sb.append("\"path\":\"").append(escapeJson(r.path())).append("\",");
            sb.append("\"uri\":\"").append(escapeJson(r.uri())).append("\",");
            sb.append("\"score\":").append(r.score());
            sb.append("}");
        }
        sb.append("\n]");

        return new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(sb.toString())),
            false, null, null);
    }

    static McpSchema.CallToolResult handleGetUnit(
            McpSchema.CallToolRequest request,
            ResourceSet rs,
            String slug) {

        Map<String, Object> args = request.arguments();
        String unitId = args != null && args.get("unit_id") != null
            ? String.valueOf(args.get("unit_id")) : "";

        KnowledgeUnit unit = rs.units().get(unitId);
        if (unit == null) {
            String ids = String.join(", ", rs.units().keySet());
            return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(
                    "Unit not found: \"" + unitId + "\". Available units: " + ids)),
                true, null, null);
        }

        Path unitDir = rs.unitDirs().get(unitId);
        String mime = KcpMapper.resolveMime(unit);

        try {
            KcpContent.ContentResult content = KcpContent.read(unitDir, unit.path(), mime);
            if (content.binary()) {
                return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(
                        "[Binary content: " + mime + ", base64 length: " + content.text().length() + "]")),
                    false, null, null);
            } else {
                return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(content.text())),
                    false, null, null);
            }
        } catch (IOException e) {
            return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("Error reading unit: " + e.getMessage())),
                true, null, null);
        }
    }

    static McpSchema.CallToolResult handleGetCommandSyntax(
            McpSchema.CallToolRequest request,
            Map<String, KcpCommands.CommandManifest> commandManifests) {

        Map<String, Object> args = request.arguments();
        String cmdQuery = args != null && args.get("command") != null
            ? String.valueOf(args.get("command")) : "";

        if (commandManifests == null || commandManifests.isEmpty()) {
            return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(
                    "No command manifests loaded \u2014 start kcp-mcp with --commands-dir")),
                true, null, null);
        }

        KcpCommands.CommandManifest found = KcpCommands.lookupCommand(commandManifests, cmdQuery);
        if (found == null) {
            // Collect unique base command names
            Set<String> commands = new TreeSet<>();
            for (KcpCommands.CommandManifest m : commandManifests.values()) {
                commands.add(m.command());
            }
            String available = String.join(", ", commands);
            return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(
                    "Unknown command: \"" + cmdQuery + "\". Available commands: " + available)),
                true, null, null);
        }

        return new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(KcpCommands.formatSyntaxBlock(found))),
            false, null, null);
    }

    // ── Prompt registration ───────────────────────────────────────────────────

    private static void registerPrompts(McpSyncServer server) {
        // Prompt: sdd-review
        server.addPrompt(new McpServerFeatures.SyncPromptSpecification(
            new McpSchema.Prompt(
                "sdd-review",
                "Review code or architecture using SDD (Skill-Driven Development) methodology.",
                List.of(new McpSchema.PromptArgument(
                    "focus",
                    "Focus area: architecture | quality | security | performance",
                    false
                ))
            ),
            (exchange, request) -> handleSddReview(request)
        ));

        // Prompt: kcp-explore
        server.addPrompt(new McpServerFeatures.SyncPromptSpecification(
            new McpSchema.Prompt(
                "kcp-explore",
                "Explore available knowledge units for a topic.",
                List.of(new McpSchema.PromptArgument(
                    "topic",
                    "Topic to explore e.g. 'authentication', 'deployment'",
                    true
                ))
            ),
            (exchange, request) -> handleKcpExplore(request)
        ));
    }

    // ── Prompt handlers (package-private for testing) ─────────────────────────

    static McpSchema.GetPromptResult handleSddReview(McpSchema.GetPromptRequest request) {
        Map<String, Object> args = request.arguments();
        String focus = args != null && args.get("focus") != null
            ? String.valueOf(args.get("focus")) : "architecture";

        Map<String, String> focusGuidance = Map.of(
            "architecture", String.join("\n",
                "1. **Intent Clarity**: Does each component have a single, clearly stated purpose?",
                "2. **Component Boundaries**: Are module boundaries clean? Can you describe each module's responsibility in one sentence?",
                "3. **Dependency Direction**: Do dependencies flow from concrete to abstract? Are there circular dependencies?",
                "4. **Knowledge Documentation**: Is there a knowledge.yaml or equivalent that maps the architecture for AI assistants?",
                "5. **Skill Decomposition**: Could an AI agent understand and modify each component independently?"
            ),
            "quality", String.join("\n",
                "1. **Test Coverage**: Are critical paths covered? Do tests verify intent, not implementation details?",
                "2. **Error Handling**: Are errors handled at the right level? Do error messages help diagnosis?",
                "3. **Naming**: Do names reflect domain concepts? Would a new developer understand the code from names alone?",
                "4. **Code Duplication**: Are there repeated patterns that should be extracted into shared utilities?",
                "5. **Documentation Freshness**: Does the documentation match the current implementation?"
            ),
            "security", String.join("\n",
                "1. **Input Validation**: Are all external inputs validated before use?",
                "2. **Authentication & Authorization**: Are auth boundaries clearly defined and enforced?",
                "3. **Secret Management**: Are secrets externalized? No hardcoded credentials?",
                "4. **Dependency Security**: Are dependencies up to date? Any known CVEs?",
                "5. **Path Traversal**: Are file paths validated against traversal attacks?"
            ),
            "performance", String.join("\n",
                "1. **Hot Paths**: Are the most-called code paths optimized? Are there unnecessary allocations?",
                "2. **Caching**: Are expensive computations cached appropriately? Is cache invalidation correct?",
                "3. **I/O Patterns**: Are I/O operations batched where possible? Any N+1 query patterns?",
                "4. **Concurrency**: Are concurrent operations safe? Are there potential deadlocks or race conditions?",
                "5. **Resource Cleanup**: Are resources (connections, file handles, timers) properly cleaned up?"
            )
        );

        String criteria = focusGuidance.getOrDefault(focus, focusGuidance.get("architecture"));

        String promptText = String.join("\n",
            "## SDD Review: " + focus,
            "",
            "You are reviewing code using the Skill-Driven Development (SDD) methodology.",
            "SDD emphasizes clear intent, modular components that AI agents can understand,",
            "and structured knowledge documentation.",
            "",
            "### Review Criteria (" + focus + "):",
            "",
            criteria,
            "",
            "### Instructions",
            "",
            "Review the code or architecture against these criteria. For each item:",
            "- State whether it passes, needs improvement, or fails",
            "- Provide specific examples from the code",
            "- Suggest concrete improvements where needed",
            "",
            "Start by examining the project structure, then drill into the focus area.",
            "Use `search_knowledge` to find relevant project knowledge units first."
        );

        return new McpSchema.GetPromptResult(
            "SDD review with " + focus + " focus",
            List.of(new McpSchema.PromptMessage(
                McpSchema.Role.USER,
                new McpSchema.TextContent(promptText)
            ))
        );
    }

    static McpSchema.GetPromptResult handleKcpExplore(McpSchema.GetPromptRequest request) {
        Map<String, Object> args = request.arguments();
        String topic = args != null && args.get("topic") != null
            ? String.valueOf(args.get("topic")) : "";

        String promptText = String.join("\n",
            "## Explore Knowledge: " + topic,
            "",
            "Find and present all knowledge units related to \"" + topic + "\".",
            "",
            "### Steps",
            "",
            "1. Call the `search_knowledge` tool with query: \"" + topic + "\"",
            "2. For each result, summarize:",
            "   - **Unit ID** and relevance score",
            "   - **Intent**: what this unit teaches",
            "   - **Path**: where to find it",
            "   - **Audience**: who it is written for",
            "3. Suggest a reading order based on dependencies (check depends_on fields)",
            "4. Highlight which units are most relevant to the topic",
            "",
            "Present the results as a navigable knowledge map that helps the user",
            "understand what information is available and where to start."
        );

        return new McpSchema.GetPromptResult(
            "Explore knowledge for: " + topic,
            List.of(new McpSchema.PromptMessage(
                McpSchema.Role.USER,
                new McpSchema.TextContent(promptText)
            ))
        );
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
