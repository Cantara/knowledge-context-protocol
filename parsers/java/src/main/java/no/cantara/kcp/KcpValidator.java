package no.cantara.kcp;

import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;
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
    private static final Set<String> VALID_RELATIONSHIP_TYPES = Set.of("enables", "context", "supersedes", "contradicts");
    private static final Set<String> VALID_KINDS = Set.of("knowledge", "schema", "service", "policy", "executable");
    private static final Set<String> VALID_FORMATS = Set.of(
            "markdown", "pdf", "openapi", "json-schema", "jupyter",
            "html", "asciidoc", "rst", "vtt", "yaml", "json", "csv", "text");
    private static final Set<String> VALID_UPDATE_FREQUENCIES = Set.of("hourly", "daily", "weekly", "monthly", "rarely", "never");
    private static final Set<String> VALID_INDEXING_SHORTHANDS = Set.of("open", "read-only", "no-train", "none");
    private static final Set<String> KNOWN_KCP_VERSIONS = Set.of("0.1", "0.2", "0.3");
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
            warnings.add("manifest: 'kcp_version' not declared; assuming 0.3");
        } else if (!KNOWN_KCP_VERSIONS.contains(manifest.kcpVersion())) {
            warnings.add("manifest: unknown kcp_version '" + manifest.kcpVersion() +
                    "'; processing as " + KNOWN_KCP_VERSIONS.stream().max(String::compareTo).orElse("0.3"));
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

    private static List<String> sorted(Set<String> set) {
        return set.stream().sorted().toList();
    }
}
