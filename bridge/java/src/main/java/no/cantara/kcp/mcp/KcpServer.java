package no.cantara.kcp.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import no.cantara.kcp.KcpParser;
import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;
import no.cantara.kcp.model.ManifestRef;
import no.cantara.kcp.model.Temporal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
        // unit.id -> the federation source's temporal window (manifests[].temporal, §3.6),
        // for units from a sub-manifest associated with a manifests[] entry via local_mirror.
        // Primary units and unassociated sub-manifests have no entry (always included).
        Map<String, Temporal> sourceTemporals,
        // manifests[].id -> temporal, for supersession resolution (issue #98 F4).
        Map<String, Temporal> refTemporals,
        KnowledgeManifest primaryManifest,
        int totalUnits,
        int manifestTokenTotal
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
        Map<String, Temporal>      srcTempMap = new LinkedHashMap<>();
        for (KnowledgeUnit unit : manifest.units()) {
            unitMap.put(unit.id(), unit);
            dirMap.put(unit.id(), manifestDir);
        }

        // manifests[].id -> temporal, for supersession resolution (issue #98 F4).
        Map<String, Temporal> refTemporalById = new LinkedHashMap<>();
        for (ManifestRef ref : manifest.manifests()) {
            refTemporalById.put(ref.id(), ref.temporal());
        }

        // Associate each federation entry's local_mirror with its manifests[] declaration so a
        // sub-manifest loaded from disk inherits its source temporal window (§3.6 / C18).
        // Keyed by the canonical (symlink-resolved) absolute mirror path (issue #98 F2).
        Map<Path, ManifestRef> mirrorToRef = new LinkedHashMap<>();
        for (ManifestRef ref : manifest.manifests()) {
            if (ref.localMirror() != null && !ref.localMirror().isEmpty() && manifestDir != null) {
                mirrorToRef.put(canonical(manifestDir.resolve(ref.localMirror())), ref);
            }
        }

        // ── merge sub-manifests ───────────────────────────────────────────────────
        for (Path subPath : subManifestPaths) {
            Path resolvedSub = subPath.toAbsolutePath();
            Path subDir = resolvedSub.getParent();
            ManifestRef sourceRef = mirrorToRef.get(canonical(resolvedSub));
            // A federation that declares mirrors but loads a sub-manifest matching none means
            // that sub-manifest's units would bypass temporal filtering — surface it (F2).
            if (sourceRef == null && !mirrorToRef.isEmpty()) {
                System.err.printf("  [kcp-mcp] warning: sub-manifest %s matched no manifests[].local_mirror"
                    + " — its units are not subject to federation temporal filtering (§3.6 / C18)%n", resolvedSub);
            }
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
                if (sourceRef != null && sourceRef.temporal() != null) {
                    srcTempMap.put(unit.id(), sourceRef.temporal());
                }
                added++;
            }
            System.err.printf("  [kcp-mcp] loaded sub-manifest %s — %d unit(s)%n",
                resolvedSub, added);
        }

        // ── build unit resources from merged context ──────────────────────────────
        // Resources are registered statically with the SDK, so temporally-excluded units are
        // filtered here (effective date = today, UTC): they are neither listed nor readable —
        // not just hidden from search (issue #98 F1). Historical access remains via
        // search_knowledge's as_of, which reads rs.units() directly.
        // §3.2 / C20 (v0.22): does the primary manifest require agent attestation?
        boolean requireAttestation = manifest.trust() != null
                && manifest.trust().agentRequirements() != null
                && Boolean.TRUE.equals(manifest.trust().agentRequirements().requireAttestation());

        String resourceToday = effectiveToday();
        for (Map.Entry<String, KnowledgeUnit> entry : unitMap.entrySet()) {
            KnowledgeUnit unit    = entry.getValue();
            Path          unitDir = dirMap.get(entry.getKey());

            if (agentOnly && !unit.audience().contains("agent")) continue;
            if (!isUnitServable(unit, srcTempMap.get(entry.getKey()), resourceToday, refTemporalById)) continue;

            resources.add(KcpMapper.buildUnitResource(slug, unit));

            final String unitUri  = KcpMapper.unitUri(slug, unit.id());
            final String mime     = KcpMapper.resolveMime(unit);
            final String unitPath = unit.path();
            final Path finalUnitDir = unitDir;
            // C20: a resource read carries no attestation channel — restricted units under a
            // manifest requiring attestation are fetched via get_unit with an attestation argument.
            final boolean needsAttestation = requireAttestation && "restricted".equals(unit.access());
            final String uid = unit.id();

            handlers.put(unitUri, uri -> {
                if (needsAttestation) {
                    throw new IllegalStateException("Unit '" + uid + "' requires agent attestation (§3.2); "
                            + "fetch it via the get_unit tool with an 'attestation' argument");
                }
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

        int manifestTokenTotal = 0;
        for (KnowledgeUnit u : unitMap.values()) {
            if (u.hints() instanceof Map<?, ?> h) {
                Object te = h.get("token_estimate");
                if (te instanceof Number n) manifestTokenTotal += n.intValue();
            }
        }
        return new ResourceSet(resources, handlers, unitMap, dirMap, srcTempMap, refTemporalById, manifest, unitMap.size(), manifestTokenTotal);
    }

    // ── Search scoring (package-private for tests) ─────────────────────────────

    static final Map<String, Integer> SENSITIVITY_ORDER = Map.of(
        "public", 0, "internal", 1, "confidential", 2, "restricted", 3);

    record SearchResult(
            String id, String intent, String path, String uri, int score,
            List<String> matchReason, Integer tokenEstimate, String summaryUnit,
            String caution) {}

    /**
     * Score a unit against a set of query terms.
     * - trigger match: 5 pts each
     * - intent match: 3 pts each
     * - id/path match: 1 pt each
     * Returns match_reason, token_estimate, summary_unit per RFC-0007.
     */
    static SearchResult scoreUnit(KnowledgeUnit unit, List<String> terms, String slug) {
        int score = 0;
        Set<String> matchReason = new LinkedHashSet<>();
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
                if (trig.contains(lterm)) { score += 5; matchReason.add("trigger"); }
            }

            // Intent match — 3 pts
            if (lowerIntent.contains(lterm)) { score += 3; matchReason.add("intent"); }

            // Id match — 1 pt
            if (lowerId.contains(lterm)) { score += 1; matchReason.add("id"); }

            // Path match — 1 pt
            if (lowerPath.contains(lterm)) { score += 1; matchReason.add("path"); }
        }

        // hints: token_estimate and summary_unit
        Integer tokenEstimate = null;
        String summaryUnit = null;
        if (unit.hints() instanceof Map<?, ?> hints) {
            Object te = hints.get("token_estimate");
            if (te instanceof Number n) tokenEstimate = n.intValue();
            Object su = hints.get("summary_unit");
            if (su instanceof String s) summaryUnit = s;
        }

        return new SearchResult(
            unit.id(), unit.intent(), unit.path(),
            KcpMapper.unitUri(slug, unit.id()), score,
            List.copyOf(matchReason), tokenEstimate, summaryUnit, null);
    }

    /** Returns the first not_for phrase matched by any query term, or null (§15.11). */
    static String matchNotFor(KnowledgeUnit unit, List<String> terms) {
        if (unit.notFor() == null || unit.notFor().isEmpty()) return null;
        for (String phrase : unit.notFor()) {
            String lphrase = phrase.toLowerCase();
            for (String term : terms) {
                if (lphrase.contains(term.toLowerCase())) return phrase;
            }
        }
        return null;
    }

    /**
     * Unified bi-temporal inclusion check (§4.22 unit / §3.6 source, §15.13). True when a
     * {@code temporal} block is valid on {@code asOf}; a null block is always included. One
     * predicate for both unit and source temporal (issue #98 F7).
     */
    static boolean isTemporallyIncluded(Temporal t, String asOf) {
        if (t == null) return true;
        if (t.validFrom()  != null && t.validFrom().compareTo(asOf)  > 0) return false;
        if (t.validUntil() != null && t.validUntil().compareTo(asOf) < 0) return false;
        return true;
    }

    /** UTC effective date (YYYY-MM-DD). Pinned to UTC so all three bridges agree at a
     *  timezone boundary (issue #98 F6). */
    static String effectiveToday() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    /** §3.2 / C20: a restricted unit under a manifest requiring attestation must not be served
     *  unless a credential is presented. The bridge checks presence only — never verifies. */
    static boolean unitNeedsAttestation(KnowledgeManifest manifest, KnowledgeUnit unit) {
        var t = manifest.trust();
        return t != null && t.agentRequirements() != null
                && Boolean.TRUE.equals(t.agentRequirements().requireAttestation())
                && "restricted".equals(unit.access());
    }

    static boolean attestationPresented(Map<String, Object> args) {
        Object a = args != null ? args.get("attestation") : null;
        return a != null && !"".equals(a);
    }

    private static final java.util.regex.Pattern AS_OF_RE =
        java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2}([T ][0-9:.+\\-Z]*)?$");

    /** Validate an {@code as_of} value as an ISO-8601 date or datetime (issue #98 F3). */
    static boolean isValidAsOf(String s) {
        return s != null && AS_OF_RE.matcher(s).matches();
    }

    /**
     * §3.6 / C18 manifest-level inclusion with supersession (issue #98 F4). A source is included
     * iff its window is valid on {@code asOf} AND it is not superseded by another manifests[]
     * entry whose own window is active — once a successor is live, the superseded source is
     * dropped, not co-served. {@code refTemporalById} maps manifests[].id → that entry's temporal.
     */
    static boolean isSourceServable(Temporal t, String asOf, Map<String, Temporal> refTemporalById) {
        if (!isTemporallyIncluded(t, asOf)) return false;
        String succ = t != null ? t.supersededBy() : null;
        if (succ != null && refTemporalById.containsKey(succ)
                && isTemporallyIncluded(refTemporalById.get(succ), asOf)) {
            return false;
        }
        return true;
    }

    /**
     * A unit is servable on a date iff its federation source is servable (window valid AND not
     * superseded) AND its own unit-level window is valid. Every retrieval path gates on this so
     * get_unit / read_resource / list_resources can't leak temporally excluded content (F1).
     */
    static boolean isUnitServable(KnowledgeUnit unit, Temporal sourceT, String asOf,
                                  Map<String, Temporal> refTemporalById) {
        return isSourceServable(sourceT, asOf, refTemporalById)
            && isTemporallyIncluded(unit.temporal(), asOf);
    }

    /** Canonical absolute path for matching a local_mirror to a loaded sub-manifest. Follows
     *  symlinks when the file exists so the association can't fail open on a symlinked or
     *  non-canonical path (issue #98 F2); falls back to lexical normalisation otherwise. */
    static Path canonical(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
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
        Map<String, Object> searchProps = new LinkedHashMap<>();
        searchProps.put("query", Map.of("type", "string", "description", "Search terms (space-separated)"));
        searchProps.put("audience", Map.of("type", "string", "description",
            "Filter by audience: agent | developer | architect | operator | human"));
        searchProps.put("scope", Map.of("type", "string", "description",
            "Filter by scope: global | project | module"));
        searchProps.put("sensitivity_max", Map.of("type", "string", "description",
            "Maximum sensitivity to include: public | internal | confidential | restricted. Units above this level are excluded."));
        searchProps.put("exclude_deprecated", Map.of("type", "boolean", "description",
            "Exclude units marked deprecated: true. Default: true."));
        searchProps.put("as_of", Map.of("type", "string", "description",
            "ISO 8601 date for point-in-time temporal query (§15.13). Default: today."));
        searchProps.put("include_all_temporal", Map.of("type", "boolean", "description",
            "If true, skip temporal filtering and return all units regardless of valid_from/valid_until (§15.13). Mutually exclusive with as_of."));
        searchProps.put("attestation", Map.of("type", "string", "description",
            "Agent attestation credential (§3.2). When presented, restricted units are not marked requires_attestation. Presence is checked; the credential is not verified."));
        McpSchema.JsonSchema searchSchema = new McpSchema.JsonSchema(
            "object", searchProps, List.of("query"), null, null, null
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
                    "The unit id from search_knowledge results"),
                "attestation", Map.of("type", "string", "description",
                    "Agent attestation credential (§3.2). Required to fetch access: restricted units when the manifest sets require_attestation. Presence is checked; the credential is not verified.")
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

        // Tool: list_manifests
        McpSchema.JsonSchema listManifestsSchema = new McpSchema.JsonSchema(
            "object",
            Map.of("as_of", Map.of("type", "string", "description",
                "ISO 8601 date (YYYY-MM-DD) to evaluate temporally_active against (§3.6). Default: today (UTC).")),
            List.of(), null, null, null
        );

        server.addTool(new McpServerFeatures.SyncToolSpecification(
            new McpSchema.Tool("list_manifests", null,
                "List the sub-manifests declared in this knowledge.yaml federation block.",
                listManifestsSchema, null, null, null),
            (exchange, request) -> handleListManifests(request, rs)
        ));
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
        String sensitivityMax = args != null && args.get("sensitivity_max") != null
            ? String.valueOf(args.get("sensitivity_max")) : null;
        // exclude_deprecated defaults to true
        boolean excludeDeprecated = args == null || args.get("exclude_deprecated") == null
            || Boolean.TRUE.equals(args.get("exclude_deprecated"));
        // temporal query parameters (§15.13)
        String asOf = args != null && args.get("as_of") != null
            ? String.valueOf(args.get("as_of")) : null;
        boolean includeAllTemporal = args != null
            && Boolean.TRUE.equals(args.get("include_all_temporal"));
        if (asOf != null && includeAllTemporal) {
            return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(
                    "{\"error\":\"temporal_query_conflict\",\"message\":\"as_of and include_all_temporal are mutually exclusive.\"}")),
                true, null, null);
        }
        // F3: reject an unparseable as_of rather than feeding it to lexicographic comparison.
        if (asOf != null && !isValidAsOf(asOf)) {
            return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(
                    "{\"error\":\"invalid_as_of\",\"message\":\"as_of must be an ISO-8601 date (YYYY-MM-DD)\"}")),
                true, null, null);
        }
        String temporalDate = asOf != null ? asOf : effectiveToday();

        if (query.isBlank()) {
            return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("Please provide a search query.")),
                false, null, null);
        }

        List<String> terms = List.of(query.trim().split("\\s+"));
        List<SearchResult> results = new ArrayList<>();

        for (Map.Entry<String, KnowledgeUnit> entry : rs.units().entrySet()) {
            KnowledgeUnit unit = entry.getValue();

            // §3.6 / C18: manifest-level (federation source) temporal filter, applied before
            // scoring and before unit-level temporal. A source outside its validity window is
            // skipped entirely — none of its units are scored or returned. Bypassed by
            // include_all_temporal, consistent with the unit-level semantics below.
            if (!includeAllTemporal && !isSourceServable(
                    rs.sourceTemporals().get(entry.getKey()), temporalDate, rs.refTemporals())) {
                continue;
            }

            // Apply filters
            if (audienceFilter != null && (unit.audience() == null
                    || !unit.audience().contains(audienceFilter))) {
                continue;
            }
            if (scopeFilter != null && !scopeFilter.equals(unit.scope())) {
                continue;
            }
            if (excludeDeprecated && Boolean.TRUE.equals(unit.deprecated())) {
                continue;
            }
            if (sensitivityMax != null) {
                int maxLevel = SENSITIVITY_ORDER.getOrDefault(sensitivityMax, 99);
                int unitLevel = SENSITIVITY_ORDER.getOrDefault(
                    unit.sensitivity() != null ? unit.sensitivity() : "public", 0);
                if (unitLevel > maxLevel) continue;
            }

            SearchResult scored = scoreUnit(unit, terms, slug);
            if (scored.score() > 0) {
                results.add(scored);
            }
        }

        // §15.11 not_for filter: strict exclusion, soft demotion (score → not_for → top-N per §15.12)
        List<SearchResult> finalResults = new ArrayList<>();
        for (SearchResult r : results) {
            KnowledgeUnit u = rs.units().get(r.id());
            String matched = u != null ? matchNotFor(u, terms) : null;
            if (matched == null) { finalResults.add(r); continue; }
            if (Boolean.TRUE.equals(u.notForStrict())) continue;
            finalResults.add(new SearchResult(
                r.id(), r.intent(), r.path(), r.uri(),
                Math.max(1, r.score() / 2),
                r.matchReason(), r.tokenEstimate(), r.summaryUnit(),
                "not_for match: '" + matched + "'"));
        }

        // §15.13 temporal filter: applied after not_for, before top-N cut
        if (!includeAllTemporal) {
            finalResults.removeIf(r -> {
                KnowledgeUnit u = rs.units().get(r.id());
                return u != null && !isTemporallyIncluded(u.temporal(), temporalDate);
            });
        } else {
            // F5: mark every result so a bypassed (possibly out-of-window) result is observable.
            finalResults.replaceAll(r -> {
                String note = r.caution() != null
                    ? r.caution() + "; temporal filtering bypassed"
                    : "temporal filtering bypassed (include_all_temporal)";
                return new SearchResult(r.id(), r.intent(), r.path(), r.uri(), r.score(),
                    r.matchReason(), r.tokenEstimate(), r.summaryUnit(), note);
            });
        }

        if (finalResults.isEmpty()) {
            String ids = String.join(", ", rs.units().keySet());
            return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(
                    "No units matched query \"" + query + "\". Available units: " + ids)),
                false, null, null);
        }

        // Sort by score descending, take top 5
        finalResults.sort((a, b) -> Integer.compare(b.score(), a.score()));
        List<SearchResult> top5 = finalResults.subList(0, Math.min(5, finalResults.size()));

        // C20: mark restricted units needing attestation (dropped if a credential was presented).
        boolean attested = attestationPresented(args);

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
            sb.append("\"score\":").append(r.score()).append(",");
            // match_reason
            sb.append("\"match_reason\":[");
            List<String> reasons = r.matchReason();
            for (int j = 0; j < reasons.size(); j++) {
                if (j > 0) sb.append(",");
                sb.append("\"").append(escapeJson(reasons.get(j))).append("\"");
            }
            sb.append("],");
            // token_estimate
            if (r.tokenEstimate() != null) {
                sb.append("\"token_estimate\":").append(r.tokenEstimate()).append(",");
            } else {
                sb.append("\"token_estimate\":null,");
            }
            // summary_unit
            if (r.summaryUnit() != null) {
                sb.append("\"summary_unit\":\"").append(escapeJson(r.summaryUnit())).append("\",");
            } else {
                sb.append("\"summary_unit\":null,");
            }
            // caution
            if (r.caution() != null) {
                sb.append("\"caution\":\"").append(escapeJson(r.caution())).append("\"");
            } else {
                sb.append("\"caution\":null");
            }
            // C20: requires_attestation marker
            KnowledgeUnit ru = rs.units().get(r.id());
            if (!attested && ru != null && unitNeedsAttestation(rs.primaryManifest(), ru)) {
                sb.append(",\"requires_attestation\":true");
            }
            sb.append("}");
        }
        sb.append("\n]");

        UsageLogger.logSearch(rs.primaryManifest().project(), query, top5.size(), rs.manifestTokenTotal());

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
        // F1: refuse a temporally-excluded unit by id, matching search / resource paths.
        // Default effective date is today (UTC); historical access is via search's as_of.
        String guToday = effectiveToday();
        if (!isUnitServable(unit, rs.sourceTemporals().get(unitId), guToday, rs.refTemporals())) {
            return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(
                    "{\"error\":\"temporally_unavailable\",\"message\":\"Unit '" + escapeJson(unitId)
                        + "' is outside its temporal validity window as of " + guToday + "\"}")),
                true, null, null);
        }
        // C20: refuse restricted-unit content unless an attestation credential is presented.
        if (unitNeedsAttestation(rs.primaryManifest(), unit) && !attestationPresented(request.arguments())) {
            var arReq = rs.primaryManifest().trust().agentRequirements();
            StringBuilder ar = new StringBuilder("{\"require_attestation\":true");
            if (arReq.trustedProviders() != null && !arReq.trustedProviders().isEmpty()) {
                ar.append(",\"trusted_providers\":[");
                for (int i = 0; i < arReq.trustedProviders().size(); i++) {
                    if (i > 0) ar.append(",");
                    ar.append("\"").append(escapeJson(arReq.trustedProviders().get(i))).append("\"");
                }
                ar.append("]");
            }
            if (arReq.attestationUrl() != null) {
                ar.append(",\"attestation_url\":\"").append(escapeJson(arReq.attestationUrl())).append("\"");
            }
            ar.append("}");
            return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(
                    "{\"error\":\"attestation_required\",\"message\":\"Unit '" + escapeJson(unitId)
                        + "' is access: restricted and this manifest requires attestation (§3.2). "
                        + "Re-call get_unit with an 'attestation' argument.\",\"agent_requirements\":" + ar + "}")),
                true, null, null);
        }

        Path unitDir = rs.unitDirs().get(unitId);
        String mime = KcpMapper.resolveMime(unit);

        try {
            KcpContent.ContentResult content = KcpContent.read(unitDir, unit.path(), mime);

            Integer tokenEstimate = null;
            if (unit.hints() instanceof Map<?, ?> h) {
                Object te = h.get("token_estimate");
                if (te instanceof Number n) tokenEstimate = n.intValue();
            }
            UsageLogger.logGetUnit(rs.primaryManifest().project(), unitId, tokenEstimate, rs.manifestTokenTotal());

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

    static McpSchema.CallToolResult handleListManifests(McpSchema.CallToolRequest request, ResourceSet rs) {
        KnowledgeManifest manifest = rs.primaryManifest();
        Map<String, Object> args = request.arguments();
        String asOf = args != null && args.get("as_of") != null ? String.valueOf(args.get("as_of")) : null;
        // F3: validate as_of here too. F9: temporally_active reflects as_of (else today, UTC).
        if (asOf != null && !isValidAsOf(asOf)) {
            return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(
                    "{\"error\":\"invalid_as_of\",\"message\":\"as_of must be an ISO-8601 date (YYYY-MM-DD)\"}")),
                true, null, null);
        }
        String lmDate = asOf != null ? asOf : effectiveToday();
        StringBuilder sb = new StringBuilder("[\n");
        List<ManifestRef> refs = manifest.manifests();
        for (int i = 0; i < refs.size(); i++) {
            ManifestRef m = refs.get(i);
            if (i > 0) sb.append(",\n");
            sb.append("  {");
            sb.append("\"id\":\"").append(escapeJson(m.id())).append("\",");
            sb.append("\"url\":\"").append(escapeJson(m.url())).append("\",");
            sb.append("\"label\":").append(m.label() != null ? "\"" + escapeJson(m.label()) + "\"" : "null").append(",");
            sb.append("\"relationship\":").append(m.relationship() != null ? "\"" + escapeJson(m.relationship()) + "\"" : "null").append(",");
            sb.append("\"has_local_mirror\":").append(m.localMirror() != null && !m.localMirror().isEmpty()).append(",");
            sb.append("\"update_frequency\":").append(m.updateFrequency() != null ? "\"" + escapeJson(m.updateFrequency()) + "\"" : "null").append(",");
            sb.append("\"version_pin\":").append(m.versionPin() != null ? "\"" + escapeJson(m.versionPin()) + "\"" : "null").append(",");
            sb.append("\"version_policy\":").append(m.versionPolicy() != null ? "\"" + escapeJson(m.versionPolicy()) + "\"" : "null").append(",");
            sb.append("\"temporal\":").append(temporalJson(m.temporal())).append(",");
            sb.append("\"temporally_active\":").append(
                isSourceServable(m.temporal(), lmDate, rs.refTemporals()));
            sb.append("}");
        }
        sb.append("\n]");

        return new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(sb.toString())),
            false, null, null);
    }

    /** Serialize a Temporal block to a JSON object (only non-null fields), or {@code null}. */
    static String temporalJson(Temporal t) {
        if (t == null) return "null";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (t.validFrom() != null)    { sb.append("\"valid_from\":\"").append(escapeJson(t.validFrom())).append("\""); first = false; }
        if (t.validUntil() != null)   { if (!first) sb.append(","); sb.append("\"valid_until\":\"").append(escapeJson(t.validUntil())).append("\""); first = false; }
        if (t.recordedAt() != null)   { if (!first) sb.append(","); sb.append("\"recorded_at\":\"").append(escapeJson(t.recordedAt())).append("\""); first = false; }
        if (t.supersededBy() != null) { if (!first) sb.append(","); sb.append("\"superseded_by\":\"").append(escapeJson(t.supersededBy())).append("\""); }
        sb.append("}");
        return sb.toString();
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
