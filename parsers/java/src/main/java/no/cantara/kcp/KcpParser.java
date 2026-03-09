package no.cantara.kcp;

import no.cantara.kcp.model.Auth;
import no.cantara.kcp.model.AuthMethod;
import no.cantara.kcp.model.Compliance;
import no.cantara.kcp.model.Delegation;
import no.cantara.kcp.model.HumanInTheLoop;
import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;
import no.cantara.kcp.model.Relationship;
import no.cantara.kcp.model.Trust;
import no.cantara.kcp.model.TrustAudit;
import no.cantara.kcp.model.TrustProvenance;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Parses a knowledge.yaml file into a {@link KnowledgeManifest}.
 */
public class KcpParser {

    // SafeConstructor disables arbitrary Java type instantiation via YAML tags
    // (e.g. !!javax.script.ScriptEngineManager). SnakeYAML 2.x defaults to safe,
    // but we declare it explicitly so the intent survives refactoring.
    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    public static KnowledgeManifest parse(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return parse(is);
        }
    }

    public static KnowledgeManifest parse(InputStream is) {
        Map<String, Object> data = YAML.load(is);
        return fromMap(data);
    }

    @SuppressWarnings("unchecked")
    public static KnowledgeManifest fromMap(Map<String, Object> data) {
        String kcpVersion = (String) data.get("kcp_version");
        String project = (String) data.get("project");
        String version = (String) data.get("version");
        LocalDate updated = parseDate(data.get("updated"));
        String language = (String) data.get("language");
        Object license = data.get("license");
        Object indexing = data.get("indexing");
        Object hints = data.get("hints");
        Trust trust = parseTrust((Map<String, Object>) data.get("trust"));
        Auth auth = parseAuth((Map<String, Object>) data.get("auth"));
        Delegation delegation = parseDelegation((Map<String, Object>) data.get("delegation"));
        Compliance compliance = parseCompliance((Map<String, Object>) data.get("compliance"));
        Object payment = data.get("payment");

        List<Map<String, Object>> unitMaps = (List<Map<String, Object>>) data.getOrDefault("units", List.of());
        List<KnowledgeUnit> units = unitMaps.stream().map(KcpParser::parseUnit).toList();

        List<Map<String, Object>> relMaps = (List<Map<String, Object>>) data.getOrDefault("relationships", List.of());
        List<Relationship> relationships = relMaps.stream().map(KcpParser::parseRelationship).toList();

        return new KnowledgeManifest(kcpVersion, project, version, updated, language, license, indexing, hints, trust, auth, delegation, compliance, payment, units, relationships);
    }

    /**
     * Validates that a unit path does not traverse outside the manifest root.
     * Spec §12 requires parsers to reject paths containing ".." that escape the root.
     */
    static String validateUnitPath(String rawPath) {
        if (rawPath == null) return null;
        // Reject absolute paths
        if (rawPath.startsWith("/") || rawPath.startsWith("\\")) {
            throw new IllegalArgumentException("Unit path must be relative: " + rawPath);
        }
        // Normalise and check for traversal
        try {
            Path normalised = Path.of(rawPath).normalize();
            if (normalised.startsWith("..")) {
                throw new IllegalArgumentException("Unit path escapes manifest root: " + rawPath);
            }
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid unit path: " + rawPath, e);
        }
        return rawPath;
    }

    @SuppressWarnings("unchecked")
    private static KnowledgeUnit parseUnit(Map<String, Object> u) {
        return new KnowledgeUnit(
                (String) u.get("id"),
                validateUnitPath((String) u.get("path")),
                (String) u.get("kind"),
                (String) u.get("intent"),
                (String) u.get("format"),
                (String) u.get("content_type"),
                (String) u.get("language"),
                (String) u.getOrDefault("scope", "global"),
                (List<String>) u.getOrDefault("audience", List.of()),
                u.get("license"),
                parseDate(u.get("validated")),
                (String) u.get("update_frequency"),
                u.get("indexing"),
                (List<String>) u.getOrDefault("depends_on", List.of()),
                (String) u.get("supersedes"),
                (List<String>) u.getOrDefault("triggers", List.of()),
                u.get("hints"),
                (String) u.get("access"),
                (String) u.get("auth_scope"),
                (String) u.get("sensitivity"),
                (Boolean) u.get("deprecated"),
                u.get("payment"),
                parseDelegation((Map<String, Object>) u.get("delegation")),
                parseCompliance((Map<String, Object>) u.get("compliance"))
        );
    }

    private static Relationship parseRelationship(Map<String, Object> r) {
        return new Relationship(
                (String) r.get("from"),
                (String) r.get("to"),
                (String) r.get("type")
        );
    }

    @SuppressWarnings("unchecked")
    private static Trust parseTrust(Map<String, Object> t) {
        if (t == null) return null;
        TrustProvenance provenance = null;
        TrustAudit audit = null;

        Map<String, Object> provMap = (Map<String, Object>) t.get("provenance");
        if (provMap != null) {
            provenance = new TrustProvenance(
                    (String) provMap.get("publisher"),
                    (String) provMap.get("publisher_url"),
                    (String) provMap.get("contact")
            );
        }

        Map<String, Object> auditMap = (Map<String, Object>) t.get("audit");
        if (auditMap != null) {
            audit = new TrustAudit(
                    (Boolean) auditMap.get("agent_must_log"),
                    (Boolean) auditMap.get("require_trace_context")
            );
        }

        return new Trust(provenance, audit);
    }

    @SuppressWarnings("unchecked")
    private static Auth parseAuth(Map<String, Object> a) {
        if (a == null) return null;
        List<Map<String, Object>> methodMaps = (List<Map<String, Object>>) a.getOrDefault("methods", List.of());
        List<AuthMethod> methods = methodMaps.stream().map(KcpParser::parseAuthMethod).toList();
        return new Auth(methods);
    }

    @SuppressWarnings("unchecked")
    private static AuthMethod parseAuthMethod(Map<String, Object> m) {
        return new AuthMethod(
                (String) m.get("type"),
                (String) m.get("issuer"),
                (List<String>) m.getOrDefault("scopes", List.of()),
                (String) m.get("header"),
                (String) m.get("registration_url")
        );
    }

    @SuppressWarnings("unchecked")
    private static Delegation parseDelegation(Map<String, Object> d) {
        if (d == null) return null;
        // human_in_the_loop is an object per SPEC.md §3.4
        HumanInTheLoop hitl = null;
        Object hitlRaw = d.get("human_in_the_loop");
        if (hitlRaw instanceof Map<?,?> m) {
            Map<String, Object> hm = (Map<String, Object>) m;
            hitl = new HumanInTheLoop(
                    (Boolean) hm.get("required"),
                    (String) hm.get("approval_mechanism"),
                    (String) hm.get("docs_url")
            );
        }
        return new Delegation(
                (Integer) d.get("max_depth"),
                (Boolean) d.get("require_capability_attenuation"),
                (Boolean) d.get("audit_chain"),
                hitl
        );
    }

    @SuppressWarnings("unchecked")
    private static Compliance parseCompliance(Map<String, Object> c) {
        if (c == null) return null;
        return new Compliance(
                (List<String>) c.get("data_residency"),
                (String) c.get("sensitivity"),
                (List<String>) c.get("regulations"),
                (List<String>) c.get("restrictions")
        );
    }

    private static LocalDate parseDate(Object value) {
        if (value == null) return null;
        if (value instanceof java.util.Date d) return d.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return LocalDate.parse(value.toString());
    }
}
