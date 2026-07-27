package no.cantara.kcp;

import no.cantara.kcp.model.Agent;
import no.cantara.kcp.model.Auth;
import no.cantara.kcp.model.AuthMethod;
import no.cantara.kcp.model.Authority;
import no.cantara.kcp.model.Compliance;
import no.cantara.kcp.model.ContentHash;
import no.cantara.kcp.model.ContentStructure;
import no.cantara.kcp.model.ActionScope;
import no.cantara.kcp.model.PlaybookStep;
import no.cantara.kcp.model.Spend;
import no.cantara.kcp.model.Delegation;
import no.cantara.kcp.model.Discovery;
import no.cantara.kcp.model.ExternalDependency;
import no.cantara.kcp.model.FreshnessPolicy;
import no.cantara.kcp.model.ExternalRelationship;
import no.cantara.kcp.model.GrantCeiling;
import no.cantara.kcp.model.GrantCeilingSource;
import no.cantara.kcp.model.HumanInTheLoop;
import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;
import no.cantara.kcp.model.AgentIdentity;
import no.cantara.kcp.model.ManifestRef;
import no.cantara.kcp.model.Payment;
import no.cantara.kcp.model.Serving;
import no.cantara.kcp.model.PaymentMethod;
import no.cantara.kcp.model.RateLimit;
import no.cantara.kcp.model.RateLimitHeaders;
import no.cantara.kcp.model.RateLimits;
import no.cantara.kcp.model.RateLimitTokens;
import no.cantara.kcp.model.RateLimitTokensTier;
import no.cantara.kcp.model.Relationship;
import no.cantara.kcp.model.TaskType;
import no.cantara.kcp.model.Trust;
import no.cantara.kcp.model.TrustAgentRequirements;
import no.cantara.kcp.model.TrustAudit;
import no.cantara.kcp.model.TrustProvenance;
import no.cantara.kcp.model.Temporal;
import no.cantara.kcp.model.Visibility;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
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
        Payment payment = parsePayment(data.get("payment"));
        RateLimits rateLimits = parseRateLimits((Map<String, Object>) data.get("rate_limits"));
        Serving serving = parseServing(data.get("serving"));

        List<Map<String, Object>> unitMaps = (List<Map<String, Object>>) data.getOrDefault("units", List.of());
        List<KnowledgeUnit> units = unitMaps.stream().map(KcpParser::parseUnit).toList();

        List<Map<String, Object>> relMaps = (List<Map<String, Object>>) data.getOrDefault("relationships", List.of());
        List<Relationship> relationships = relMaps.stream().map(KcpParser::parseRelationship).toList();

        List<Map<String, Object>> manifestMaps = (List<Map<String, Object>>) data.getOrDefault("manifests", List.of());
        List<ManifestRef> manifests = manifestMaps.stream().map(KcpParser::parseManifestRef).toList();

        List<Map<String, Object>> extRelMaps = (List<Map<String, Object>>) data.getOrDefault("external_relationships", List.of());
        List<ExternalRelationship> externalRelationships = extRelMaps.stream().map(KcpParser::parseExternalRelationship).toList();

        FreshnessPolicy freshnessPolicy = parseFreshnessPolicy((Map<String, Object>) data.get("freshness_policy"));
        Visibility visibility = parseVisibility((Map<String, Object>) data.get("visibility"));
        Authority authority = parseAuthority((Map<String, Object>) data.get("authority"));
        Discovery discovery = parseDiscovery((Map<String, Object>) data.get("discovery"));
        List<String> notFor = asStringList(data.get("not_for"));
        Temporal temporal = parseTemporal((Map<String, Object>) data.get("temporal"));

        // §3.13 (RFC-0025, v0.27): authority_level_scale, task_types[], agents[], grant_ceiling.
        List<String> authorityLevelScale = asStringListStrict(data.get("authority_level_scale"));
        List<Map<String, Object>> taskTypeMaps = (List<Map<String, Object>>) data.getOrDefault("task_types", List.of());
        List<TaskType> taskTypes = taskTypeMaps.stream().map(KcpParser::parseTaskType).toList();
        List<Map<String, Object>> agentMaps = (List<Map<String, Object>>) data.getOrDefault("agents", List.of());
        List<Agent> agents = agentMaps.stream().map(KcpParser::parseAgent).toList();
        GrantCeiling grantCeiling = parseGrantCeiling(data.get("grant_ceiling"));

        return new KnowledgeManifest(kcpVersion, project, version, updated, language, license, indexing, hints, trust, auth, delegation, compliance, payment, rateLimits, serving, units, relationships, manifests, externalRelationships, freshnessPolicy, visibility, authority, discovery, notFor, temporal, authorityLevelScale, taskTypes, agents, grantCeiling);
    }

    /** §3.13 (RFC-0025, v0.27): a task-type declaration. */
    private static TaskType parseTaskType(Map<String, Object> raw) {
        return new TaskType(
                (String) raw.get("id"),
                (String) raw.get("intent"),
                (String) raw.get("authority_level")
        );
    }

    /** §3.13 (RFC-0025, v0.27): an agent declaration (Capability Profile). */
    private static Agent parseAgent(Map<String, Object> raw) {
        return new Agent(
                (String) raw.get("id"),
                (String) raw.get("name"),
                (String) raw.get("authority_level")
        );
    }

    /** §3.13 (RFC-0025, v0.27): one source in a grant_ceiling minimum computation. */
    private static GrantCeilingSource parseGrantCeilingSource(Map<String, Object> raw) {
        return new GrantCeilingSource(
                (String) raw.get("id"),
                (String) raw.get("authority_level"),
                (String) raw.get("unit_ref"),
                (String) raw.get("task_type_ref"),
                (String) raw.get("agent_ref")
        );
    }

    /** §3.13 (RFC-0025, v0.27): multi-source minimum ceiling computation. */
    @SuppressWarnings("unchecked")
    private static GrantCeiling parseGrantCeiling(Object raw) {
        if (!(raw instanceof Map<?, ?> d)) return null;
        Map<String, Object> data = (Map<String, Object>) d;
        List<Map<String, Object>> sourceMaps = (List<Map<String, Object>>) data.getOrDefault("sources", List.of());
        List<GrantCeilingSource> sources = sourceMaps.stream().map(KcpParser::parseGrantCeilingSource).toList();
        List<String> mandatorySources = asStringListStrict(data.get("mandatory_sources"));
        return new GrantCeiling(sources, mandatorySources);
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
                asStringListStrict(u.get("aliases")),
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
                parsePayment(u.get("payment")),
                parseRateLimits((Map<String, Object>) u.get("rate_limits")),
                parseDelegation((Map<String, Object>) u.get("delegation")),
                parseCompliance((Map<String, Object>) u.get("compliance")),
                parseAuth((Map<String, Object>) u.get("auth")),
                externalDependsOn,
                (List<String>) u.getOrDefault("requires_capabilities", List.of()),
                parseFreshnessPolicy((Map<String, Object>) u.get("freshness_policy")),
                parseVisibility((Map<String, Object>) u.get("visibility")),
                parseAuthority((Map<String, Object>) u.get("authority")),
                parseDiscovery((Map<String, Object>) u.get("discovery")),
                asStringList(u.get("not_for")),
                asBoolean(u.get("not_for_strict")),
                parseContentStructure(u.get("content_structure")),
                parseContentHash(u.get("content_hash")),
                parseTemporal((Map<String, Object>) u.get("temporal")),
                (String) u.get("authority_level"),
                parseActionScope(u.get("action_scope")),
                parseSteps(u.get("steps"))
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
                    (String) provMap.get("contact"),
                    (String) provMap.get("publisher_did")
            );
        }

        Map<String, Object> auditMap = (Map<String, Object>) t.get("audit");
        if (auditMap != null) {
            audit = new TrustAudit(
                    (Boolean) auditMap.get("agent_must_log"),
                    (Boolean) auditMap.get("require_trace_context"),
                    (Boolean) auditMap.get("provides_access_receipts"),
                    (String) auditMap.get("receipt_format")
            );
        }

        TrustAgentRequirements agentRequirements = null;
        Map<String, Object> arMap = (Map<String, Object>) t.get("agent_requirements");
        if (arMap != null) {
            agentRequirements = new TrustAgentRequirements(
                    (Boolean) arMap.get("require_attestation"),
                    (List<String>) arMap.getOrDefault("trusted_providers", List.of()),
                    (String) arMap.get("attestation_url"),
                    (String) arMap.get("attestation_jwks"),
                    (Boolean) arMap.get("propagate_to_governed")
            );
        }

        return new Trust(provenance, audit, agentRequirements);
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
                (String) m.get("registration_url"),
                (String) m.get("trust_domain"),
                (List<String>) m.getOrDefault("supported_methods", List.of()),
                (String) m.get("key_id"),
                (String) m.get("algorithm")
        );
    }

    @SuppressWarnings("unchecked")
    private static Spend parseSpend(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Map<String, Object> d = (Map<String, Object>) m;
        Object cap = d.get("max_spend");
        return new Spend(
                cap instanceof Number n ? n.doubleValue() : null,
                asStringList(d.get("allowed_vendors")),
                (String) d.get("currency"));
    }

    /**
     * §4.3b (v0.29, RFC-0027): the ordered composition a {@code kind: playbook} declares.
     *
     * <p>Anything that is not a list yields null, matching {@link #parseActionScope}: a
     * malformed block must not fail the whole parse, and null reads as "declares no
     * steps", which the validator then rejects for a playbook. Entries that are not maps,
     * or carry no {@code id}, are dropped rather than half-parsed — a step with no
     * identity cannot be named by {@code depends_on}, so it cannot join the graph at all.
     */
    @SuppressWarnings("unchecked")
    private static List<PlaybookStep> parseSteps(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        List<PlaybookStep> steps = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Map<String, Object> d = (Map<String, Object>) m;
            if (d.get("id") == null) continue;
            steps.add(new PlaybookStep(
                    String.valueOf(d.get("id")),
                    asString(d.get("uses")),
                    asString(d.get("action")),
                    asStringList(d.get("depends_on")),
                    asString(d.get("authority_level")),
                    parseEscalation(d.get("escalation")),
                    asString(d.get("success_condition")),
                    asString(d.get("on_failure")),
                    asString(d.get("timeout"))));
        }
        return steps;
    }

    /**
     * §4.3b: {@code escalation} accepts a single trigger or a list; both normalise to a
     * list, since the triggers are disjunctive and a scalar means a list of one.
     */
    private static List<String> parseEscalation(Object raw) {
        if (raw == null) return null;
        if (raw instanceof String s) return List.of(s);
        return asStringList(raw);
    }

    private static String asString(Object raw) {
        return raw != null ? String.valueOf(raw) : null;
    }

    @SuppressWarnings("unchecked")
    private static ActionScope parseActionScope(Object raw) {
        // A scalar or list where an object belongs yields null rather than throwing: a
        // malformed envelope must not fail the whole parse, and null correctly reads as
        // "declares nothing", which authorizes nothing.
        if (!(raw instanceof Map<?, ?> m)) return null;
        Map<String, Object> d = (Map<String, Object>) m;
        return new ActionScope(
                asStringList(d.get("tools")),
                asStringList(d.get("paths")),
                asStringList(d.get("capabilities")),
                parseSpend(d.get("spend")));
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
                (Boolean) d.get("require_delegation_proof"),
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
    // A rate-limit count is an Integer, or the String sentinel "unlimited" (v0.25).
    private static Object parseCount(Object v) {
        if (v instanceof Number n) return n.intValue();
        if ("unlimited".equals(v)) return "unlimited";
        return null;
    }

    @SuppressWarnings("unchecked")
    private static RateLimit parseRateLimitTier(Object raw) {
        if (!(raw instanceof Map)) return null;
        Map<String, Object> d = (Map<String, Object>) raw;
        return new RateLimit(
                parseCount(d.get("requests_per_minute")),
                parseCount(d.get("requests_per_hour")),
                parseCount(d.get("requests_per_day"))
        );
    }

    @SuppressWarnings("unchecked")
    private static RateLimitTokensTier parseTokensTier(Object raw) {
        if (!(raw instanceof Map)) return null;
        Map<String, Object> d = (Map<String, Object>) raw;
        return new RateLimitTokensTier(
                parseCount(d.get("tokens_per_minute")),
                parseCount(d.get("tokens_per_day"))
        );
    }

    @SuppressWarnings("unchecked")
    private static RateLimits parseRateLimits(Map<String, Object> r) {
        if (r == null) return null;
        RateLimitTokens tokens = null;
        if (r.get("tokens") instanceof Map<?, ?> tk) {
            Map<String, Object> t = (Map<String, Object>) tk;
            tokens = new RateLimitTokens(
                    parseTokensTier(t.get("default")),
                    parseTokensTier(t.get("authenticated")),
                    parseTokensTier(t.get("premium"))
            );
        }
        RateLimitHeaders headers = null;
        if (r.get("headers") instanceof Map<?, ?> hd) {
            Map<String, Object> h = (Map<String, Object>) hd;
            headers = new RateLimitHeaders(
                    (String) h.get("remaining"),
                    (String) h.get("reset"),
                    (String) h.get("retry_after")
            );
        }
        return new RateLimits(
                parseRateLimitTier(r.get("default")),
                parseRateLimitTier(r.get("authenticated")),
                parseRateLimitTier(r.get("premium")),
                tokens,
                headers,
                (String) r.get("backoff")
        );
    }

    @SuppressWarnings("unchecked")
    private static PaymentMethod parsePaymentMethod(Object raw) {
        Map<String, Object> d = raw instanceof Map ? (Map<String, Object>) raw : Map.of();
        return new PaymentMethod(
                (String) d.get("type"),
                (String) d.get("currency"),
                d.get("price_per_request") == null ? null : String.valueOf(d.get("price_per_request")),
                asStringList(d.get("networks")),
                (String) d.get("wallet"),
                (String) d.get("provider"),
                (String) d.get("plans_url"),
                (Boolean) d.get("free_tier"),
                d.get("free_requests_per_day") instanceof Number n ? n.intValue() : null,
                (String) d.get("upgrade_url")
        );
    }

    @SuppressWarnings("unchecked")
    private static Payment parsePayment(Object raw) {
        if (!(raw instanceof Map)) return null;
        Map<String, Object> d = (Map<String, Object>) raw;
        List<PaymentMethod> methods = null;
        if (d.get("methods") instanceof List<?> ms) {
            methods = ms.stream().map(KcpParser::parsePaymentMethod).toList();
        }
        return new Payment(
                (String) d.get("default_tier"),
                methods,
                (String) d.get("billing_contact")
        );
    }

    private static Serving parseServing(Object raw) {
        // Type-guard rather than cast: a non-object `serving:` (a string/list/number) must
        // parse to "no serving block", not throw ClassCastException as an unchecked cast would.
        if (!(raw instanceof Map<?, ?> s)) return null;
        return new Serving(asStringListStrict(s.get("manifest")), asStringListStrict(s.get("mcp")));
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
                (String) m.get("local_mirror"),
                (String) m.get("version_pin"),
                (String) m.get("version_policy"),
                parseTemporal((Map<String, Object>) m.get("temporal")),
                asStringList(m.get("context")),
                parseAgentIdentity((Map<String, Object>) m.get("agent_identity"))
        );
    }

    private static AgentIdentity parseAgentIdentity(Map<String, Object> a) {
        if (a == null) return null;
        return new AgentIdentity(
                (Boolean) a.get("required"),
                (String) a.get("credential_hint"),
                (String) a.get("issuer_hint"),
                (String) a.get("docs_url")
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


    @SuppressWarnings("unchecked")
    private static FreshnessPolicy parseFreshnessPolicy(Map<String, Object> fp) {
        if (fp == null) return null;
        return new FreshnessPolicy(
                fp.get("max_age_days") instanceof Number n ? n.intValue() : null,
                (String) fp.get("on_stale"),
                (String) fp.get("review_contact")
        );
    }

    @SuppressWarnings("unchecked")
    private static Visibility parseVisibility(Map<String, Object> v) {
        if (v == null) return null;
        List<Map<String, Object>> conditions = (List<Map<String, Object>>) v.get("conditions");
        return new Visibility(
                (String) v.get("default"),
                conditions
        );
    }

    private static Authority parseAuthority(Map<String, Object> a) {
        if (a == null) return null;
        return new Authority(
                (String) a.get("read"),
                (String) a.get("summarize"),
                (String) a.get("modify"),
                (String) a.get("share_externally"),
                (String) a.get("execute")
        );
    }

    private static Discovery parseDiscovery(Map<String, Object> d) {
        if (d == null) return null;
        Double confidence = null;
        Object rawConfidence = d.get("confidence");
        if (rawConfidence instanceof Number n) {
            confidence = n.doubleValue();
        }
        return new Discovery(
                (String) d.get("verification_status"),
                (String) d.get("source"),
                (String) d.get("observed_at"),
                (String) d.get("verified_at"),
                (String) d.get("verified_by"),
                (String) d.get("evidence"),
                confidence,
                (String) d.get("contradicted_by")
        );
    }

    @SuppressWarnings("unchecked")
    private static ContentHash parseContentHash(Object raw) {
        // RFC-0019 (draft). A declared-but-malformed block parses to an empty
        // record so the validator can flag it; only an absent block parses to
        // null. Mirrors the TypeScript parsers.
        if (raw == null) return null;
        if (!(raw instanceof Map)) return new ContentHash(null, null);
        Map<String, Object> c = (Map<String, Object>) raw;
        return new ContentHash(
                c.get("algorithm") == null ? null : c.get("algorithm").toString(),
                c.get("value") == null ? null : c.get("value").toString()
        );
    }

    @SuppressWarnings("unchecked")
    private static Temporal parseTemporal(Map<String, Object> raw) {
        if (raw == null) return null;
        return new Temporal(
                raw.get("valid_from") == null ? null : raw.get("valid_from").toString(),
                raw.get("valid_until") == null ? null : raw.get("valid_until").toString(),
                raw.get("recorded_at") == null ? null : raw.get("recorded_at").toString(),
                raw.get("superseded_by") == null ? null : raw.get("superseded_by").toString()
        );
    }

    @SuppressWarnings("unchecked")
    private static ContentStructure parseContentStructure(Object raw) {
        // A non-mapping value (scalar, list) is treated as absent rather than
        // crashing the parse — forward-compat, mirroring the TypeScript parsers.
        if (!(raw instanceof Map)) return null;
        Map<String, Object> c = (Map<String, Object>) raw;
        return new ContentStructure(
                c.get("primary") == null ? null : c.get("primary").toString(),
                asStringList(c.get("contains")),
                c.get("density") == null ? null : c.get("density").toString()
        );
    }

    /**
     * Coerce a YAML value to a string list: a list passes through (elements
     * stringified), a scalar becomes a single-element list, null stays null.
     * Mirrors the TypeScript parsers' asStringArray so a common authoring
     * mistake (scalar where a list is expected) degrades identically across
     * implementations instead of crashing.
     */
    private static List<String> asStringList(Object raw) {
        if (raw == null) return null;
        if (raw instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
        }
        return List.of(raw.toString());
    }

    /**
     * v0.26 strict string-list coercion for {@code aliases} and {@code serving} — a non-{@code List}
     * is *absent* (null) and non-{@code String} entries are dropped, matching the TypeScript and
     * Python parsers exactly. Unlike {@link #asStringList}, it never coerces a scalar into a
     * one-element list, so the same signed bytes cannot resolve to different trust decisions across
     * implementations. Structural validity (must be a list of strings) is the JSON schema's job.
     */
    private static List<String> asStringListStrict(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        return list.stream().filter(x -> x instanceof String).map(x -> (String) x).toList();
    }

    /**
     * Coerce a YAML value to a boolean, mirroring TypeScript Boolean()
     * truthiness exactly (any non-empty string is true — YAML itself already
     * resolves unquoted true/false before this code sees them).
     */
    private static Boolean asBoolean(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Boolean b) return b;
        if (raw instanceof Number n) return n.doubleValue() != 0;
        return !raw.toString().isEmpty();
    }

    private static LocalDate parseDate(Object value) {
        if (value == null) return null;
        if (value instanceof java.util.Date d) return d.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return LocalDate.parse(value.toString());
    }
}
