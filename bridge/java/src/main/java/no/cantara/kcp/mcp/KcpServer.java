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
     * Holds the static resource list and per-URI read handlers built from a manifest.
     * Package-private so tests can invoke handlers directly without a transport.
     */
    record ResourceSet(
        List<McpSchema.Resource> resources,
        Map<String, ResourceHandler> handlers,
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

        // ── unit context: unit.id → [unit, manifestDir]  (primary wins on dup) ───
        Map<String, Object[]> unitContext = new LinkedHashMap<>();
        for (KnowledgeUnit unit : manifest.units()) {
            unitContext.put(unit.id(), new Object[]{unit, manifestDir});
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
                if (unitContext.containsKey(unit.id())) {
                    System.err.printf(
                        "  [kcp-mcp] warning: duplicate unit id '%s' in %s — skipping%n",
                        unit.id(), resolvedSub);
                    continue;
                }
                unitContext.put(unit.id(), new Object[]{unit, subDir});
                added++;
            }
            System.err.printf("  [kcp-mcp] loaded sub-manifest %s — %d unit(s)%n",
                resolvedSub, added);
        }

        // ── build unit resources from merged context ──────────────────────────────
        for (Map.Entry<String, Object[]> entry : unitContext.entrySet()) {
            KnowledgeUnit unit    = (KnowledgeUnit) entry.getValue()[0];
            Path          unitDir = (Path)          entry.getValue()[1];

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

        return new ResourceSet(resources, handlers, unitContext.size());
    }

    // ── Public factory ────────────────────────────────────────────────────────────

    /**
     * Parses the KCP manifest at {@code manifestPath} and returns a configured
     * MCP sync server ready to accept connections.
     * Convenience overload — no sub-manifests.
     */
    public static McpSyncServer createServer(
            Path manifestPath,
            McpServerTransportProvider transport,
            boolean agentOnly,
            boolean warnOnValidation) throws IOException {
        return createServer(manifestPath, transport, agentOnly, warnOnValidation, List.of());
    }

    /**
     * Parses the KCP manifest at {@code manifestPath} and returns a configured
     * MCP sync server ready to accept connections.
     *
     * @param manifestPath     path to knowledge.yaml
     * @param transport        MCP transport provider (e.g. StdioServerTransportProvider)
     * @param agentOnly        if true, expose only units with audience: [agent]
     * @param warnOnValidation if true, log validation warnings to stderr
     * @param subManifestPaths additional manifest paths whose units are merged
     */
    public static McpSyncServer createServer(
            Path manifestPath,
            McpServerTransportProvider transport,
            boolean agentOnly,
            boolean warnOnValidation,
            List<Path> subManifestPaths) throws IOException {

        KnowledgeManifest manifest = KcpParser.parse(manifestPath);
        ResourceSet rs = buildResources(manifestPath, agentOnly, subManifestPaths);
        String slug = KcpMapper.projectSlug(manifest.project());

        int primaryUnits = manifest.units().size();
        int totalUnits   = rs.totalUnits();
        int subUnits     = totalUnits - primaryUnits;

        String agentNote = agentOnly ? " [agent-only]" : "";
        String subNote   = subManifestPaths.isEmpty() ? "" :
            String.format(" (%d primary + %d from %d sub-manifest(s))",
                primaryUnits, subUnits, subManifestPaths.size());

        System.err.printf("[kcp-mcp] Serving '%s' — %d unit(s)%s%s%n",
            manifest.project(), totalUnits, subNote, agentNote);
        System.err.printf("[kcp-mcp] Start with: %s%n", KcpMapper.manifestUri(slug));

        McpSyncServer server = McpServer.sync(transport)
            .serverInfo("kcp-" + slug, "0.5.0")
            .capabilities(McpSchema.ServerCapabilities.builder()
                .resources(null, null)
                .build())
            .build();

        for (McpSchema.Resource resource : rs.resources()) {
            final String uri = resource.uri();
            ResourceHandler handler = rs.handlers().get(uri);
            server.addResource(new McpServerFeatures.SyncResourceSpecification(
                resource,
                (exchange, request) -> handler.handle(request.uri())
            ));
        }

        return server;
    }
}
