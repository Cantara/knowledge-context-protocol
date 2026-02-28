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
        Map<String, ResourceHandler> handlers
    ) {}

    @FunctionalInterface
    interface ResourceHandler {
        McpSchema.ReadResourceResult handle(String uri);
    }

    /**
     * Parses the manifest and builds all resources and their read handlers.
     * Extracted for direct testing without a transport.
     */
    static ResourceSet buildResources(Path manifestPath, boolean agentOnly) throws IOException {
        KnowledgeManifest manifest = KcpParser.parse(manifestPath);
        Path manifestDir = manifestPath.getParent();
        String slug  = KcpMapper.projectSlug(manifest.project());
        String mUri  = KcpMapper.manifestUri(slug);
        String mJson = KcpMapper.buildManifestJson(manifest, slug);

        List<McpSchema.Resource>         resources = new ArrayList<>();
        Map<String, ResourceHandler>     handlers  = new LinkedHashMap<>();

        // ── manifest meta-resource ────────────────────────────────────────────────
        resources.add(KcpMapper.buildManifestResource(slug));
        handlers.put(mUri, uri ->
            new McpSchema.ReadResourceResult(
                List.of(new McpSchema.TextResourceContents(uri, "application/json", mJson, null)),
                null
            )
        );

        // ── unit resources ────────────────────────────────────────────────────────
        for (KnowledgeUnit unit : manifest.units()) {
            if (agentOnly && !unit.audience().contains("agent")) continue;

            resources.add(KcpMapper.buildUnitResource(slug, unit));

            final String unitUri  = KcpMapper.unitUri(slug, unit.id());
            final String mime     = KcpMapper.resolveMime(unit);
            final String unitPath = unit.path();

            handlers.put(unitUri, uri -> {
                try {
                    KcpContent.ContentResult content = KcpContent.read(manifestDir, unitPath, mime);
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

        return new ResourceSet(resources, handlers);
    }

    // ── Public factory ────────────────────────────────────────────────────────────

    /**
     * Parses the KCP manifest at {@code manifestPath} and returns a configured
     * MCP sync server ready to accept connections.
     *
     * @param manifestPath     path to knowledge.yaml
     * @param transport        MCP transport provider (e.g. StdioServerTransportProvider)
     * @param agentOnly        if true, expose only units with audience: [agent]
     * @param warnOnValidation if true, log validation warnings to stderr
     */
    public static McpSyncServer createServer(
            Path manifestPath,
            io.modelcontextprotocol.spec.McpServerTransportProvider transport,
            boolean agentOnly,
            boolean warnOnValidation) throws IOException {

        KnowledgeManifest manifest = KcpParser.parse(manifestPath);
        ResourceSet rs = buildResources(manifestPath, agentOnly);
        String slug    = KcpMapper.projectSlug(manifest.project());

        String agentNote = agentOnly ? " (agent-only filter active)" : "";
        System.err.printf("[kcp-mcp] Serving '%s' — %d units%s%n",
            manifest.project(), manifest.units().size(), agentNote);
        System.err.printf("[kcp-mcp] Start with: %s%n", KcpMapper.manifestUri(slug));

        McpSyncServer server = McpServer.sync(transport)
            .serverInfo("kcp-" + slug, "0.1.0")
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
