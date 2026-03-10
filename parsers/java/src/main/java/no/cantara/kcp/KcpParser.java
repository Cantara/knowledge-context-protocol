package no.cantara.kcp;

import no.cantara.kcp.model.Auth;
import no.cantara.kcp.model.AuthMethod;
import no.cantara.kcp.model.Compliance;
import no.cantara.kcp.model.Delegation;
import no.cantara.kcp.model.ExternalDependency;
import no.cantara.kcp.model.ExternalRelationship;
import no.cantara.kcp.model.HumanInTheLoop;
import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;
import no.cantara.kcp.model.ManifestRef;
import no.cantara.kcp.model.RateLimit;
import no.cantara.kcp.model.RateLimits;
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
        RateLimits rateLimits = parseRateLimits((Map<String, Object>) data.get("rate_limits"));

        List<Map<String, Object>> unitMaps = (List<Map<String, Object>>) data.getOrDefault("units", List.of());
        List<KnowledgeUnit> units = unitMaps.stream().map(KcpParser::parseUnit).toList();

        List<Map<String, Object>> relMaps = (List<Map<String, Object>>) data.getOrDefault("relationships", List.of());
        List<Relationship> relationships = relMaps.stream().map(KcpParser::parseRelationship).toList();

        List<Map<String, Object>> manifestMaps = (List<Map<String, Object>>) data.getOrDefault("manifests", List.of());
        List<ManifestRef> manifests = manifestMaps.stream().map(KcpParser::parseManifestRef).toList();

        List<Map<String, Object>> extRelMaps = (List<Map<String, Object>>) data.getOrDefault("external_relationships", List.of());
        List<ExternalRelationship> externalRelationships = extRelMaps.stream().map(KcpParser::parseExternalRelationship).toList();

        return new KnowledgeManifest(kcpVersion, project, version, updated, language, license, indexing, hints, trust, auth, delegation, compliance, payment, rateLimits, units, relationships, manifests, externalRelationships);
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
        List<Map<String, Object>> extDepMaps = (List<Map<String, Object>>) u.getOrDefault("external_depends_on", List.of());
        List<ExternalDependency> externalDependsOn = extDepMaps.stream().map(KcpParser::parseExternalDependency).toList();

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
                parseRateLimits((Map<String, Object>) u.get("rate_limits")),
                parseDelegation((Map<String, Object>) u.get("delegation")),
                parseCompliance((Map<String, Object>) u.get("compliance")),
                externalDependsOn
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
        // data_residency can be a list (e.g. [EU]) or a map (e.g. {regions: [EU]})
        List<String> dataResidency = null;
        Object dr = c.get("data_residency");
        if (dr instanceof List<?>) {
            dataResidency = (List<String>) dr;
        } else if (dr instanceof Map<?, ?> drMap) {
            Object regions = ((Map<String, Object>) drMap).get("regions");
            if (regions instanceof List<?>) {
                dataResidency = (List<String>) regions;
            }
        }
        return new Compliance(
                dataResidency,
                (String) c.get("sensitivity"),
                (List<String>) c.get("regulations"),
                (List<String>) c.get("restrictions")
        );
    }

    @SuppressWarnings("unchecked")
    private static RateLimits parseRateLimits(Map<String, Object> r) {
        if (r == null) return null;
        Map<String, Object> def = (Map<String, Object>) r.get("default");
        if (def == null) return new RateLimits(null);
        RateLimit defaultLimit = new RateLimit(
                def.get("requests_per_minute") instanceof Number n ? n.intValue() : null,
                def.get("requests_per_day") instanceof Number n ? n.intValue() : null
        );
        return new RateLimits(defaultLimit);
    }

    @SuppressWarnings("unchecked")
    private static ManifestRef parseManifestRef(Map<String, Object> m) {
        return new ManifestRef(
                (String) m.get("id"),
                (String) m.get("url"),
                (String) m.get("label"),
                (String) m.get("relationship"),
                parseAuth((Map<String, Object>) m.get("auth")),
                (String) m.get("update_frequency"),
                (String) m.get("local_mirror")
        );
    }

    private static ExternalDependency parseExternalDependency(Map<String, Object> e) {
        return new ExternalDependency(
                (String) e.get("manifest"),
                (String) e.get("unit"),
                (String) e.get("on_failure")
        );
    }

    private static ExternalRelationship parseExternalRelationship(Map<String, Object> e) {
        return new ExternalRelationship(
                (String) e.get("from_manifest"),
                (String) e.get("from_unit"),
                (String) e.get("to_manifest"),
                (String) e.get("to_unit"),
                (String) e.get("type")
        );
    }

    private static LocalDate parseDate(Object value) {
        if (value == null) return null;
        if (value instanceof java.util.Date d) return d.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return LocalDate.parse(value.toString());
    }
}
