package no.cantara.kcp.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import no.cantara.kcp.model.HumanInTheLoop;
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
        if (unit.access() != null && !"public".equals(unit.access())) {
            sb.append("\nAccess: ").append(unit.access());
        }
        if (unit.sensitivity() != null && !"public".equals(unit.sensitivity())) {
            sb.append("\nSensitivity: ").append(unit.sensitivity());
        }
        if (unit.triggers() != null && !unit.triggers().isEmpty()) {
            sb.append("\nTriggers: ").append(String.join(", ", unit.triggers()));
        }
        if (unit.dependsOn() != null && !unit.dependsOn().isEmpty()) {
            sb.append("\nDepends on: ").append(String.join(", ", unit.dependsOn()));
        }
        if (unit.deprecated() != null && unit.deprecated()) {
            sb.append("\nDeprecated: true");
        }
        if (unit.compliance() != null) {
            if (!unit.compliance().dataResidency().isEmpty()) {
                sb.append("\nData residency: ").append(String.join(", ", unit.compliance().dataResidency()));
            }
            if (!unit.compliance().regulations().isEmpty()) {
                sb.append("\nRegulations: ").append(String.join(", ", unit.compliance().regulations()));
            }
        }
        if (unit.delegation() != null) {
            if (unit.delegation().maxDepth() != null) {
                sb.append("\nDelegation max depth: ").append(unit.delegation().maxDepth());
            }
            if (unit.delegation().humanInTheLoop() != null) {
                HumanInTheLoop hitl = unit.delegation().humanInTheLoop();
                String desc = Boolean.TRUE.equals(hitl.required())
                        ? "required (" + (hitl.approvalMechanism() != null ? hitl.approvalMechanism() : "unspecified") + ")"
                        : "not required";
                sb.append("\nHuman in the loop: ").append(desc);
            }
            if (unit.delegation().auditChain() != null) {
                sb.append("\nDelegation audit chain: ").append(unit.delegation().auditChain());
            }
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
            if (u.access() != null) {
                sb.append(",\"access\":").append(quoted(u.access()));
            }
            if (u.authScope() != null) {
                sb.append(",\"auth_scope\":").append(quoted(u.authScope()));
            }
            if (u.sensitivity() != null) {
                sb.append(",\"sensitivity\":").append(quoted(u.sensitivity()));
            }
            if (u.deprecated() != null && u.deprecated()) {
                sb.append(",\"deprecated\":true");
            }
            // §4.3a: the kind is what separates a document from something that acts.
            // This bridge omitted it entirely, so an MCP client could not tell a
            // governed procedure from prose (#161).
            if (u.kind() != null) {
                sb.append(",\"kind\":").append(quoted(u.kind()));
            }
            // §4.3a: action_scope, emitted sub-field by sub-field because this builder
            // writes JSON by hand. Anything the model gains later must be added here
            // too — a scope truncated at the bridge boundary is indistinguishable from
            // one that declares none, which authorises nothing.
            if (u.actionScope() != null) {
                StringBuilder scope = new StringBuilder();
                appendArrayIfPresent(scope, "tools", u.actionScope().tools());
                appendArrayIfPresent(scope, "paths", u.actionScope().paths());
                appendArrayIfPresent(scope, "capabilities", u.actionScope().capabilities());
                if (u.actionScope().spend() != null) {
                    var sp = u.actionScope().spend();
                    StringBuilder spend = new StringBuilder();
                    if (sp.maxSpend() != null) spend.append("\"max_spend\":").append(sp.maxSpend());
                    if (sp.currency() != null) {
                        if (spend.length() > 0) spend.append(",");
                        spend.append("\"currency\":").append(quoted(sp.currency()));
                    }
                    if (sp.allowedVendors() != null && !sp.allowedVendors().isEmpty()) {
                        if (spend.length() > 0) spend.append(",");
                        spend.append("\"allowed_vendors\":").append(jsonArray(sp.allowedVendors()));
                    }
                    if (spend.length() > 0) {
                        if (scope.length() > 0) scope.append(",");
                        scope.append("\"spend\":{").append(spend).append("}");
                    }
                }
                // §4.3a (v0.31, RFC-0029): the explicit negative scope. Surfaced so a
                // consumer sees the prohibitions, not just the allowlist — a deny dropped
                // at the bridge boundary reads as "no prohibition", the more permissive lie.
                if (u.actionScope().deny() != null) {
                    var dn = u.actionScope().deny();
                    StringBuilder deny = new StringBuilder();
                    appendArrayIfPresent(deny, "tools", dn.tools());
                    appendArrayIfPresent(deny, "paths", dn.paths());
                    appendArrayIfPresent(deny, "capabilities", dn.capabilities());
                    if (deny.length() > 0) {
                        if (scope.length() > 0) scope.append(",");
                        scope.append("\"deny\":{").append(deny).append("}");
                    }
                }
                if (scope.length() > 0) {
                    sb.append(",\"action_scope\":{").append(scope).append("}");
                }
            }
            // §4.3c (v0.30): the grant deciding whether a governed procedure may act.
            // Absent means NOT eligible, so a consumer that cannot see the field would
            // have to assume the more permissive reading.
            if (u.loadEligible() != null) {
                sb.append(",\"load_eligible\":").append(u.loadEligible());
            }
            // §4.3b (v0.29): the composition, so a consumer can see per-step ceilings.
            if (u.steps() != null && !u.steps().isEmpty()) {
                sb.append(",\"steps\":[");
                for (int s = 0; s < u.steps().size(); s++) {
                    if (s > 0) sb.append(",");
                    var st = u.steps().get(s);
                    sb.append("{\"id\":").append(quoted(st.id()));
                    if (st.uses() != null) sb.append(",\"uses\":").append(quoted(st.uses()));
                    if (st.action() != null) sb.append(",\"action\":").append(quoted(st.action()));
                    if (st.dependsOn() != null && !st.dependsOn().isEmpty())
                        sb.append(",\"depends_on\":").append(jsonArray(st.dependsOn()));
                    if (st.authorityLevel() != null)
                        sb.append(",\"authority_level\":").append(quoted(st.authorityLevel()));
                    if (st.escalation() != null && !st.escalation().isEmpty())
                        sb.append(",\"escalation\":").append(jsonArray(st.escalation()));
                    if (st.successCondition() != null)
                        sb.append(",\"success_condition\":").append(quoted(st.successCondition()));
                    if (st.onFailure() != null)
                        sb.append(",\"on_failure\":").append(quoted(st.onFailure()));
                    if (st.timeout() != null) sb.append(",\"timeout\":").append(quoted(st.timeout()));
                    sb.append("}");
                }
                sb.append("]");
            }
            if (u.delegation() != null) {
                sb.append(",\"delegation\":{");
                boolean first = true;
                if (u.delegation().maxDepth() != null) {
                    sb.append("\"max_depth\":").append(u.delegation().maxDepth());
                    first = false;
                }
                if (u.delegation().humanInTheLoop() != null) {
                    if (!first) sb.append(",");
                    HumanInTheLoop hitl = u.delegation().humanInTheLoop();
                    sb.append("\"human_in_the_loop\":{");
                    boolean hitlFirst = true;
                    if (hitl.required() != null) {
                        sb.append("\"required\":").append(hitl.required());
                        hitlFirst = false;
                    }
                    if (hitl.approvalMechanism() != null) {
                        if (!hitlFirst) sb.append(",");
                        sb.append("\"approval_mechanism\":").append(quoted(hitl.approvalMechanism()));
                        hitlFirst = false;
                    }
                    if (hitl.docsUrl() != null) {
                        if (!hitlFirst) sb.append(",");
                        sb.append("\"docs_url\":").append(quoted(hitl.docsUrl()));
                    }
                    sb.append("}");
                    first = false;
                }
                sb.append("}");
            }
            if (u.compliance() != null) {
                sb.append(",\"compliance\":{");
                boolean first = true;
                if (!u.compliance().dataResidency().isEmpty()) {
                    sb.append("\"data_residency\":").append(jsonArray(u.compliance().dataResidency()));
                    first = false;
                }
                if (u.compliance().sensitivity() != null) {
                    if (!first) sb.append(",");
                    sb.append("\"sensitivity\":").append(quoted(u.compliance().sensitivity()));
                    first = false;
                }
                if (!u.compliance().regulations().isEmpty()) {
                    if (!first) sb.append(",");
                    sb.append("\"regulations\":").append(jsonArray(u.compliance().regulations()));
                    first = false;
                }
                if (!u.compliance().restrictions().isEmpty()) {
                    if (!first) sb.append(",");
                    sb.append("\"restrictions\":").append(jsonArray(u.compliance().restrictions()));
                }
                sb.append("}");
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

    /** Append `,"name":[...]` when the list is present and non-empty. */
    private static void appendArrayIfPresent(StringBuilder sb, String name, List<String> values) {
        if (values == null || values.isEmpty()) return;
        if (sb.length() > 0) sb.append(",");
        sb.append(quoted(name)).append(":").append(jsonArray(values));
    }
}
