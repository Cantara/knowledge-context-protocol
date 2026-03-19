package no.cantara.kcp;

import no.cantara.kcp.model.Compliance;
import no.cantara.kcp.model.Delegation;
import no.cantara.kcp.model.Discovery;
import no.cantara.kcp.model.ExternalDependency;
import no.cantara.kcp.model.ExternalRelationship;
import no.cantara.kcp.model.HumanInTheLoop;
import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;
import no.cantara.kcp.model.ManifestRef;
import no.cantara.kcp.model.Relationship;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates a parsed {@link KnowledgeManifest} against the KCP specification.
 *
 * <p>Returns a {@link ValidationResult} with separate {@code errors} (must fix) and
 * {@code warnings} (should fix) lists, per the conformance rules in SPEC.md §7.
 */
public class KcpValidator {

    private static final Set<String> VALID_SCOPES = Set.of("global", "project", "module");
    private static final Set<String> VALID_AUDIENCES = Set.of("human", "agent", "developer", "operator", "architect", "devops");
    private static final Set<String> VALID_RELATIONSHIP_TYPES = Set.of("enables", "context", "supersedes", "contradicts", "depends_on", "governs");
    private static final Set<String> VALID_KINDS = Set.of("knowledge", "schema", "service", "policy", "executable");
    private static final Set<String> VALID_FORMATS = Set.of(
            "markdown", "pdf", "openapi", "json-schema", "jupyter",
            "html", "asciidoc", "rst", "vtt", "yaml", "json", "csv", "text");
    private static final Set<String> VALID_UPDATE_FREQUENCIES = Set.of("hourly", "daily", "weekly", "monthly", "rarely", "never");
    private static final Set<String> VALID_INDEXING_SHORTHANDS = Set.of("open", "read-only", "no-train", "none");
    private static final Set<String> VALID_ACCESS_VALUES = Set.of("public", "authenticated", "restricted");
    private static final Set<String> VALID_SENSITIVITY_VALUES = Set.of("public", "internal", "confidential", "restricted");
    private static final Set<String> VALID_HITL_MECHANISMS = Set.of("oauth_consent", "uma", "custom");
    private static final Set<String> KNOWN_KCP_VERSIONS = Set.of("0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "0.10", "0.11", "0.12");
    private static final Set<String> VALID_VERIFICATION_STATUSES = Set.of("rumored", "observed", "verified", "deprecated");
    private static final Set<String> VALID_DISCOVERY_SOURCES = Set.of("manual", "web_traversal", "openapi", "llm_inference");
    private static final Set<String> VALID_AUTHORITY_VALUES = Set.of("initiative", "requires_approval", "denied");
    private static final Set<String> VALID_VISIBILITY_DEFAULTS = Set.of("public", "internal", "confidential", "restricted");
    private static final Set<String> VALID_MANIFEST_RELATIONSHIPS = Set.of("child", "foundation", "governs", "peer", "archive");
    private static final Set<String> VALID_ON_FAILURE_VALUES = Set.of("skip", "warn", "degrade");
    private static final Set<String> VALID_VERSION_POLICIES = Set.of("exact", "minimum", "compatible");
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9.\\-]+$");
    private static final int MAX_TRIGGER_LENGTH = 60;
    private static final int MAX_TRIGGERS_PER_UNIT = 20;

    /**
     * Immutable result of validating a manifest.
     *
     * @param errors   Conditions that make the manifest invalid (MUST fix).
     * @param warnings Conditions that are permitted but suspicious (SHOULD fix).
     */
    public record ValidationResult(List<String> errors, List<String> warnings) {
        public ValidationResult {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        public boolean isValid() { return errors.isEmpty(); }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }

    /**
     * Validate a manifest without path existence checking.
     */
    public static ValidationResult validate(KnowledgeManifest manifest) {
        return validate(manifest, null);
    }

    /**
     * Validate a manifest, optionally checking that declared paths exist relative
     * to {@code manifestDir}.
     *
     * @param manifest    The parsed manifest to validate.
     * @param manifestDir The directory containing the manifest file, or {@code null}
     *                    to skip path existence checks.
     */
    public static ValidationResult validate(KnowledgeManifest manifest, Path manifestDir) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> unitIds = manifest.units().stream().map(KnowledgeUnit::id).collect(Collectors.toSet());

        // Cycle detection (§4.7) — detect and silently ignore cycle-closing edges.
        detectCycles(manifest.units(), unitIds);

        // kcp_version — RECOMMENDED; warn if absent or unknown
        if (manifest.kcpVersion() == null || manifest.kcpVersion().isBlank()) {
            warnings.add("manifest: 'kcp_version' not declared; assuming 0.8");
        } else if (!KNOWN_KCP_VERSIONS.contains(manifest.kcpVersion())) {
            warnings.add("manifest: unknown kcp_version '" + manifest.kcpVersion() +
                    "'; processing as " + KNOWN_KCP_VERSIONS.stream().max(String::compareTo).orElse("0.6"));
        }

        // Required root fields
        if (manifest.project() == null || manifest.project().isBlank()) {
            errors.add("manifest: 'project' is required");
        }
        if (manifest.units().isEmpty()) {
            errors.add("manifest: 'units' must not be empty");
        }

        // Duplicate ID detection (§7: SHOULD warn, use first occurrence)
        Set<String> seenIds = new HashSet<>();

        for (KnowledgeUnit unit : manifest.units()) {
            String p = "unit '" + unit.id() + "'";

            if (unit.id() == null || unit.id().isBlank()) {
                errors.add("unit: 'id' is required");
                continue;
            }

            // Duplicate ID check
            if (!seenIds.add(unit.id())) {
                warnings.add(p + ": duplicate 'id' (first occurrence takes precedence)");
            }

            // ID format check (§4.2: lowercase a-z, digits, hyphens, dots)
            if (!ID_PATTERN.matcher(unit.id()).matches()) {
                warnings.add(p + ": 'id' should contain only lowercase a-z, digits, hyphens, and dots");
            }

            if (unit.path() == null || unit.path().isBlank()) {
                errors.add(p + ": 'path' is required");
            } else if (manifestDir != null) {
                Path resolved = manifestDir.resolve(unit.path());
                if (!Files.exists(resolved)) {
                    warnings.add(p + ": path '" + unit.path() + "' does not exist");
                }
            }
            if (unit.intent() == null || unit.intent().isBlank()) {
                errors.add(p + ": 'intent' is required");
            }
            if (unit.scope() == null || unit.scope().isBlank()) {
                errors.add(p + ": 'scope' is required");
            } else if (!VALID_SCOPES.contains(unit.scope())) {
                errors.add(p + ": 'scope' must be one of " + sorted(VALID_SCOPES) + ", got '" + unit.scope() + "'");
            }

            List<String> invalidAudience = unit.audience().stream()
                    .filter(a -> !VALID_AUDIENCES.contains(a))
                    .toList();
            if (!invalidAudience.isEmpty()) {
                warnings.add(p + ": unknown audience value(s): " + invalidAudience);
            }

            // kind validation (§4.3a)
            if (unit.kind() != null && !VALID_KINDS.contains(unit.kind())) {
                warnings.add(p + ": unknown 'kind' value '" + unit.kind() + "'");
            }

            // format validation (§4.4a)
            if (unit.format() != null && !VALID_FORMATS.contains(unit.format())) {
                warnings.add(p + ": unknown 'format' value '" + unit.format() + "'");
            }

            // update_frequency validation (§4.6b)
            if (unit.updateFrequency() != null && !VALID_UPDATE_FREQUENCIES.contains(unit.updateFrequency())) {
                warnings.add(p + ": unknown 'update_frequency' value '" + unit.updateFrequency() + "'");
            }

            // indexing validation (§4.6c)
            if (unit.indexing() instanceof String idx && !VALID_INDEXING_SHORTHANDS.contains(idx)) {
                warnings.add(p + ": unknown 'indexing' shorthand '" + idx + "'");
            }

            for (String dep : unit.dependsOn()) {
                if (!unitIds.contains(dep)) {
                    warnings.add(p + ": 'depends_on' references unknown unit '" + dep + "'");
                }
            }

            // Trigger constraints (§4.9)
            if (unit.triggers().size() > MAX_TRIGGERS_PER_UNIT) {
                warnings.add(p + ": more than " + MAX_TRIGGERS_PER_UNIT + " triggers (" +
                        unit.triggers().size() + "); excess will be ignored");
            }
            for (String trigger : unit.triggers()) {
                if (trigger.length() > MAX_TRIGGER_LENGTH) {
                    warnings.add(p + ": trigger '" + trigger.substring(0, Math.min(30, trigger.length())) +
                            "...' exceeds " + MAX_TRIGGER_LENGTH + " characters");
                }
            }

            // access validation (§4.11)
            if (unit.access() != null && !VALID_ACCESS_VALUES.contains(unit.access())) {
                warnings.add(p + ": unknown 'access' value '" + unit.access() + "'; treating as 'restricted'");
            }

            // auth_scope validation (§4.11)
            if (unit.authScope() != null && !"restricted".equals(unit.access())) {
                warnings.add(p + ": 'auth_scope' is only meaningful when access is 'restricted'");
            }

            // sensitivity validation (§4.12)
            if (unit.sensitivity() != null && !VALID_SENSITIVITY_VALUES.contains(unit.sensitivity())) {
                warnings.add(p + ": unknown 'sensitivity' value '" + unit.sensitivity() + "'");
            }

            // delegation validation (§3.4)
            validateDelegation(unit.delegation(), manifest.delegation(), p, errors, warnings);

            // compliance validation (§3.5)
            validateCompliance(unit.compliance(), p, errors, warnings);

            // hints validation (§4.10)
            if (unit.hints() instanceof Map<?, ?> hints) {
                if (Boolean.TRUE.equals(hints.get("summary_available")) && hints.get("summary_unit") == null) {
                    warnings.add(p + ": summary_available is true but no summary_unit declared");
                }
                Object summaryUnit = hints.get("summary_unit");
                if (summaryUnit instanceof String su && !unitIds.contains(su)) {
                    warnings.add(p + ": summary_unit references non-existent unit '" + su + "'");
                }
                Object chunkOf = hints.get("chunk_of");
                if (chunkOf instanceof String co && !unitIds.contains(co)) {
                    warnings.add(p + ": chunk_of references non-existent unit '" + co + "'");
                }
                if (hints.get("chunk_index") != null && hints.get("chunk_of") == null) {
                    warnings.add(p + ": chunk_index is present without chunk_of");
                }
            }

            // discovery validation (§RFC-0012)
            validateDiscovery(unit.discovery(), unitIds, p, warnings);

            // authority validation (§RFC-0009)
            validateAuthority(unit.authority(), p, warnings);

            // visibility validation (§RFC-0009)
            validateVisibility(unit.visibility(), p, warnings);
        }

        // Root-level delegation validation
        validateDelegation(manifest.delegation(), null, "manifest", errors, warnings);

        // Root-level compliance validation
        validateCompliance(manifest.compliance(), "manifest", errors, warnings);

        // Root-level discovery validation (§RFC-0012)
        validateDiscovery(manifest.discovery(), unitIds, "manifest", warnings);

        // Root-level authority validation (§RFC-0009)
        validateAuthority(manifest.authority(), "manifest", warnings);

        // Root-level visibility validation (§RFC-0009)
        validateVisibility(manifest.visibility(), "manifest", warnings);

        // Warn if any unit requires auth but no root-level auth block is present (§7)
        boolean hasProtectedUnits = manifest.units().stream()
                .anyMatch(u -> "authenticated".equals(u.access()) || "restricted".equals(u.access()));
        if (hasProtectedUnits && (manifest.auth() == null || manifest.auth().methods().isEmpty())) {
            warnings.add("manifest: units with access 'authenticated' or 'restricted' exist but no 'auth' block is declared");
        }

        for (Relationship rel : manifest.relationships()) {
            String p = "relationship '" + rel.fromId() + "' -> '" + rel.toId() + "'";
            if (!unitIds.contains(rel.fromId())) {
                warnings.add(p + ": 'from' references unknown unit '" + rel.fromId() + "'");
            }
            if (!unitIds.contains(rel.toId())) {
                warnings.add(p + ": 'to' references unknown unit '" + rel.toId() + "'");
            }
            if (!VALID_RELATIONSHIP_TYPES.contains(rel.type())) {
                warnings.add(p + ": 'type' must be one of " + sorted(VALID_RELATIONSHIP_TYPES) + ", got '" + rel.type() + "'");
            }
        }

        // Federation validation (§3.6)
        Set<String> manifestIds = new HashSet<>();
        for (ManifestRef ref : manifest.manifests()) {
            String p = "manifests['" + ref.id() + "']";
            if (ref.id() == null || ref.id().isBlank()) {
                errors.add("manifests: entry missing required 'id'");
                continue;
            }
            if (!ID_PATTERN.matcher(ref.id()).matches()) {
                errors.add(p + ": 'id' must match ^[a-z0-9.\\-]+$, got '" + ref.id() + "'");
            }
            if (!manifestIds.add(ref.id())) {
                errors.add(p + ": duplicate manifest id");
            }
            if (ref.url() == null || ref.url().isBlank()) {
                errors.add(p + ": 'url' is required");
            } else if (!ref.url().startsWith("https://")) {
                errors.add(p + ": 'url' must use HTTPS, got '" + ref.url() + "'");
            }
            if (ref.relationship() != null && !VALID_MANIFEST_RELATIONSHIPS.contains(ref.relationship())) {
                warnings.add(p + ": unknown 'relationship' value '" + ref.relationship() + "'");
            }
            if (ref.updateFrequency() != null && !VALID_UPDATE_FREQUENCIES.contains(ref.updateFrequency())) {
                warnings.add(p + ": unknown 'update_frequency' value '" + ref.updateFrequency() + "'");
            }
            if (ref.versionPolicy() != null && !VALID_VERSION_POLICIES.contains(ref.versionPolicy())) {
                warnings.add(p + ": unknown 'version_policy' value '" + ref.versionPolicy() + "'; treating as 'compatible'");
            }
            if (ref.versionPin() != null && ref.versionPolicy() == null) {
                warnings.add(p + ": 'version_pin' is set but 'version_policy' is not declared; defaulting to 'compatible'");
            }
        }

        // Validate external_depends_on references in units
        for (KnowledgeUnit unit : manifest.units()) {
            String p = "unit '" + unit.id() + "'";
            for (ExternalDependency extDep : unit.externalDependsOn()) {
                String ep = p + ".external_depends_on['" + extDep.manifest() + "/" + extDep.unit() + "']";
                if (extDep.manifest() == null || extDep.manifest().isBlank()) {
                    errors.add(ep + ": 'manifest' is required");
                } else if (!manifestIds.contains(extDep.manifest())) {
                    warnings.add(ep + ": references unknown manifest id '" + extDep.manifest() + "'");
                }
                if (extDep.unit() == null || extDep.unit().isBlank()) {
                    errors.add(ep + ": 'unit' is required");
                }
                if (extDep.onFailure() != null && !VALID_ON_FAILURE_VALUES.contains(extDep.onFailure())) {
                    warnings.add(ep + ": unknown 'on_failure' value '" + extDep.onFailure() + "'; treating as 'skip'");
                }
            }
        }

        // Validate external_relationships
        for (ExternalRelationship extRel : manifest.externalRelationships()) {
            String ep = "external_relationship['" + extRel.fromUnit() + "' -> '" + extRel.toUnit() + "']";
            if (extRel.fromUnit() == null || extRel.fromUnit().isBlank()) {
                errors.add(ep + ": 'from_unit' is required");
            }
            if (extRel.toUnit() == null || extRel.toUnit().isBlank()) {
                errors.add(ep + ": 'to_unit' is required");
            }
            if (extRel.type() == null || extRel.type().isBlank()) {
                errors.add(ep + ": 'type' is required");
            }
            if (extRel.fromManifest() != null && !manifestIds.contains(extRel.fromManifest()) && !extRel.fromManifest().isBlank()) {
                warnings.add(ep + ": 'from_manifest' references unknown manifest id '" + extRel.fromManifest() + "'");
            }
            if (extRel.toManifest() != null && !manifestIds.contains(extRel.toManifest()) && !extRel.toManifest().isBlank()) {
                warnings.add(ep + ": 'to_manifest' references unknown manifest id '" + extRel.toManifest() + "'");
            }
        }

        return new ValidationResult(errors, warnings);
    }

    /**
     * Detect cycles in the depends_on graph using DFS.
     * Per SPEC.md §4.7, cycles are silently ignored (no error or warning).
     *
     * @return The set of edges (as "from-&gt;to" strings) that would close a cycle.
     */
    static Set<String> detectCycles(List<KnowledgeUnit> units, Set<String> unitIds) {
        Map<String, List<String>> adj = new HashMap<>();
        for (KnowledgeUnit unit : units) {
            List<String> deps = unit.dependsOn().stream()
                    .filter(unitIds::contains)
                    .toList();
            adj.put(unit.id(), deps);
        }

        Set<String> cycleEdges = new HashSet<>();
        Map<String, Integer> state = new HashMap<>();
        for (String id : unitIds) {
            state.put(id, 0);
        }

        for (String id : unitIds) {
            if (state.get(id) == 0) {
                dfs(id, adj, state, cycleEdges);
            }
        }

        return cycleEdges;
    }

    private static void dfs(String node, Map<String, List<String>> adj,
                            Map<String, Integer> state, Set<String> cycleEdges) {
        state.put(node, 1);
        for (String dep : adj.getOrDefault(node, List.of())) {
            int depState = state.getOrDefault(dep, 0);
            if (depState == 1) {
                cycleEdges.add(node + "->" + dep);
            } else if (depState == 0) {
                dfs(dep, adj, state, cycleEdges);
            }
        }
        state.put(node, 2);
    }

    private static void validateDelegation(Delegation delegation, Delegation rootDelegation,
                                              String prefix, List<String> errors, List<String> warnings) {
        if (delegation == null) return;
        // human_in_the_loop is an object per SPEC.md §3.4 — validate approval_mechanism if present
        if (delegation.humanInTheLoop() != null) {
            String mech = delegation.humanInTheLoop().approvalMechanism();
            if (mech != null && !VALID_HITL_MECHANISMS.contains(mech)) {
                errors.add(prefix + ": delegation.human_in_the_loop.approval_mechanism must be one of " +
                        sorted(VALID_HITL_MECHANISMS) + ", got '" + mech + "'");
            }
        }
        if (rootDelegation != null && delegation.maxDepth() != null && rootDelegation.maxDepth() != null) {
            if (delegation.maxDepth() > rootDelegation.maxDepth()) {
                errors.add(prefix + ": unit delegation.max_depth (" + delegation.maxDepth() +
                        ") must not exceed root delegation.max_depth (" + rootDelegation.maxDepth() + ")");
            }
        }
    }

    private static void validateCompliance(Compliance compliance, String prefix,
                                           List<String> errors, List<String> warnings) {
        if (compliance == null) return;
        if (compliance.sensitivity() != null && !VALID_SENSITIVITY_VALUES.contains(compliance.sensitivity())) {
            errors.add(prefix + ": compliance.sensitivity must be one of " +
                    sorted(VALID_SENSITIVITY_VALUES) + ", got '" + compliance.sensitivity() + "'");
        }
    }

    private static void validateDiscovery(Discovery discovery, Set<String> unitIds,
                                          String prefix, List<String> warnings) {
        if (discovery == null) return;
        String status = discovery.verificationStatus();
        Double confidence = discovery.confidence();

        // verification_status must be a known value
        if (status != null && !VALID_VERIFICATION_STATUSES.contains(status)) {
            warnings.add(prefix + ": discovery.verification_status must be one of " +
                    sorted(VALID_VERIFICATION_STATUSES) + ", got '" + status + "'");
        }

        // source must be a known value
        if (discovery.source() != null && !VALID_DISCOVERY_SOURCES.contains(discovery.source())) {
            warnings.add(prefix + ": discovery.source must be one of " +
                    sorted(VALID_DISCOVERY_SOURCES) + ", got '" + discovery.source() + "'");
        }

        // rumored MUST have confidence < 0.5 (normative)
        if ("rumored".equals(status) && confidence != null && confidence >= 0.5) {
            warnings.add(prefix + ": discovery.verification_status=rumored but confidence=" +
                    confidence + " (MUST be < 0.5)");
        }

        // verified SHOULD have confidence >= 0.8 (normative)
        if ("verified".equals(status) && confidence != null && confidence < 0.8) {
            warnings.add(prefix + ": discovery.verification_status=verified but confidence=" +
                    confidence + " (SHOULD be >= 0.8)");
        }

        // verified_at SHOULD NOT be set when status is rumored or observed
        if (discovery.verifiedAt() != null &&
                ("rumored".equals(status) || "observed".equals(status))) {
            warnings.add(prefix + ": discovery.verified_at is set but verification_status='" +
                    status + "' (SHOULD only be set for verified units)");
        }

        // contradicted_by must reference a known unit id
        if (discovery.contradictedBy() != null && !unitIds.contains(discovery.contradictedBy())) {
            warnings.add(prefix + ": discovery.contradicted_by references unknown unit '" +
                    discovery.contradictedBy() + "'");
        }
    }

    private static void validateAuthority(no.cantara.kcp.model.Authority authority,
                                          String prefix, List<String> warnings) {
        if (authority == null) return;
        Map<String, String> actions = Map.of(
                "read", authority.read() != null ? authority.read() : "",
                "summarize", authority.summarize() != null ? authority.summarize() : "",
                "modify", authority.modify() != null ? authority.modify() : "",
                "share_externally", authority.shareExternally() != null ? authority.shareExternally() : "",
                "execute", authority.execute() != null ? authority.execute() : ""
        );
        actions.forEach((action, value) -> {
            if (!value.isEmpty() && !VALID_AUTHORITY_VALUES.contains(value)) {
                warnings.add(prefix + ": authority." + action + " must be one of " +
                        sorted(VALID_AUTHORITY_VALUES) + ", got '" + value + "'");
            }
        });
    }

    private static void validateVisibility(no.cantara.kcp.model.Visibility visibility,
                                           String prefix, List<String> warnings) {
        if (visibility == null) return;
        if (visibility.defaultSensitivity() != null &&
                !VALID_VISIBILITY_DEFAULTS.contains(visibility.defaultSensitivity())) {
            warnings.add(prefix + ": visibility.default must be one of " +
                    sorted(VALID_VISIBILITY_DEFAULTS) + ", got '" + visibility.defaultSensitivity() + "'");
        }
    }

    private static List<String> sorted(Set<String> set) {
        return set.stream().sorted().toList();
    }
}
