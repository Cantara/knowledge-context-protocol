package no.cantara.kcp.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;
import no.cantara.kcp.model.Relationship;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pure mapping functions: KCP model → MCP schema types.
 * No I/O. Mirrors the TypeScript and Python bridge mappers.
 */
public final class KcpMapper {

    private KcpMapper() {}

    // ── MIME tables ───────────────────────────────────────────────────────────────

    private static final Map<String, String> FORMAT_MIME = Map.ofEntries(
        Map.entry("markdown",  "text/markdown"),
        Map.entry("openapi",   "application/vnd.oai.openapi+yaml"),
        Map.entry("asyncapi",  "application/vnd.aai.asyncapi+yaml"),
        Map.entry("json",      "application/json"),
        Map.entry("yaml",      "application/yaml"),
        Map.entry("text",      "text/plain"),
        Map.entry("html",      "text/html"),
        Map.entry("pdf",       "application/pdf"),
        Map.entry("png",       "image/png"),
        Map.entry("jpg",       "image/jpeg"),
        Map.entry("svg",       "image/svg+xml")
    );

    private static final Map<String, String> EXT_MIME = Map.ofEntries(
        Map.entry(".md",   "text/markdown"),
        Map.entry(".yaml", "application/yaml"),
        Map.entry(".yml",  "application/yaml"),
        Map.entry(".json", "application/json"),
        Map.entry(".txt",  "text/plain"),
        Map.entry(".html", "text/html"),
        Map.entry(".htm",  "text/html"),
        Map.entry(".pdf",  "application/pdf"),
        Map.entry(".png",  "image/png"),
        Map.entry(".jpg",  "image/jpeg"),
        Map.entry(".jpeg", "image/jpeg"),
        Map.entry(".svg",  "image/svg+xml")
    );

    private static final Set<String> BINARY_PREFIXES = Set.of("image/", "audio/", "video/");
    private static final Set<String> BINARY_EXACT    = Set.of(
        "application/pdf", "application/octet-stream", "application/zip");

    private static final Map<String, Double> SCOPE_PRIORITY = Map.of(
        "global",  1.0,
        "project", 0.7,
        "module",  0.5
    );

    // ── Slug ──────────────────────────────────────────────────────────────────────

    public static String projectSlug(String project) {
        String s = project.toLowerCase();
        s = s.replaceAll("\\s+", "-");
        s = s.replaceAll("[^a-z0-9\\-]", "");
        return s;
    }

    // ── URIs ──────────────────────────────────────────────────────────────────────

    public static String unitUri(String slug, String unitId) {
        return "knowledge://" + slug + "/" + unitId;
    }

    public static String manifestUri(String slug) {
        return "knowledge://" + slug + "/manifest";
    }

    // ── MIME ──────────────────────────────────────────────────────────────────────

    public static String resolveMime(KnowledgeUnit unit) {
        if (unit.contentType() != null && !unit.contentType().isBlank()) {
            return unit.contentType();
        }
        if (unit.format() != null) {
            String mime = FORMAT_MIME.get(unit.format().toLowerCase());
            if (mime != null) return mime;
        }
        if (unit.path() != null) {
            int dot = unit.path().lastIndexOf('.');
            if (dot >= 0) {
                String ext = unit.path().substring(dot).toLowerCase();
                String mime = EXT_MIME.get(ext);
                if (mime != null) return mime;
            }
        }
        return "text/plain";
    }

    public static boolean isBinaryMime(String mime) {
        if (mime == null) return false;
        if (BINARY_EXACT.contains(mime)) return true;
        return BINARY_PREFIXES.stream().anyMatch(mime::startsWith);
    }

    // ── Audience ──────────────────────────────────────────────────────────────────

    public static List<McpSchema.Role> mapAudience(List<String> audience) {
        if (audience == null || audience.isEmpty()) {
            return List.of(McpSchema.Role.USER);
        }
        Set<McpSchema.Role> roles = new LinkedHashSet<>();
        for (String a : audience) {
            switch (a) {
                case "agent"              -> roles.add(McpSchema.Role.ASSISTANT);
                case "human", "developer" -> roles.add(McpSchema.Role.USER);
            }
        }
        if (roles.isEmpty()) roles.add(McpSchema.Role.USER);
        return new ArrayList<>(roles);
    }

    // ── Resource building ─────────────────────────────────────────────────────────

    public static String buildDescription(KnowledgeUnit unit) {
        StringBuilder sb = new StringBuilder(unit.intent() != null ? unit.intent() : "");
        if (unit.triggers() != null && !unit.triggers().isEmpty()) {
            sb.append("\nTriggers: ").append(String.join(", ", unit.triggers()));
        }
        if (unit.dependsOn() != null && !unit.dependsOn().isEmpty()) {
            sb.append("\nDepends on: ").append(String.join(", ", unit.dependsOn()));
        }
        return sb.toString();
    }

    public static McpSchema.Resource buildUnitResource(String slug, KnowledgeUnit unit) {
        double priority = SCOPE_PRIORITY.getOrDefault(
            unit.scope() != null ? unit.scope() : "global", 0.5);

        String lastMod = null;
        if (unit.validated() != null) {
            lastMod = unit.validated()
                .atStartOfDay(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
        }

        McpSchema.Annotations annotations = new McpSchema.Annotations(
            mapAudience(unit.audience()), priority, lastMod);

        return new McpSchema.Resource(
            unitUri(slug, unit.id()),
            unit.id(),
            unit.intent(),           // title
            buildDescription(unit),  // description
            resolveMime(unit),
            null,                    // size
            annotations,
            null                     // meta
        );
    }

    public static McpSchema.Resource buildManifestResource(String slug) {
        McpSchema.Annotations annotations = new McpSchema.Annotations(
            List.of(McpSchema.Role.ASSISTANT, McpSchema.Role.USER), 1.0, null);
        return new McpSchema.Resource(
            manifestUri(slug),
            "manifest",
            "Knowledge index",
            "Full unit index for this knowledge base",
            "application/json",
            null,
            annotations,
            null
        );
    }

    // ── Manifest JSON ─────────────────────────────────────────────────────────────

    public static String buildManifestJson(KnowledgeManifest manifest, String slug) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"project\":").append(quoted(manifest.project())).append(",");
        sb.append("\"version\":").append(quoted(manifest.version())).append(",");
        sb.append("\"unit_count\":").append(manifest.units().size()).append(",");
        sb.append("\"units\":[");
        List<KnowledgeUnit> units = manifest.units();
        for (int i = 0; i < units.size(); i++) {
            if (i > 0) sb.append(",");
            KnowledgeUnit u = units.get(i);
            sb.append("{");
            sb.append("\"id\":").append(quoted(u.id())).append(",");
            sb.append("\"uri\":").append(quoted(unitUri(slug, u.id()))).append(",");
            sb.append("\"path\":").append(quoted(u.path())).append(",");
            sb.append("\"intent\":").append(quoted(u.intent())).append(",");
            sb.append("\"scope\":").append(quoted(u.scope())).append(",");
            sb.append("\"audience\":").append(jsonArray(u.audience())).append(",");
            sb.append("\"mimeType\":").append(quoted(resolveMime(u)));
            if (u.validated() != null) {
                sb.append(",\"lastModified\":").append(
                    quoted(u.validated().format(DateTimeFormatter.ISO_LOCAL_DATE)));
            }
            sb.append("}");
        }
        sb.append("],\"relationships\":[");
        List<Relationship> rels = manifest.relationships();
        for (int i = 0; i < rels.size(); i++) {
            if (i > 0) sb.append(",");
            Relationship r = rels.get(i);
            sb.append("{");
            sb.append("\"from\":").append(quoted(r.fromId())).append(",");
            sb.append("\"to\":").append(quoted(r.toId())).append(",");
            sb.append("\"type\":").append(quoted(r.type()));
            sb.append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private static String quoted(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n") + "\"";
    }

    private static String jsonArray(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        return "[" + list.stream().map(KcpMapper::quoted).collect(Collectors.joining(",")) + "]";
    }
}
