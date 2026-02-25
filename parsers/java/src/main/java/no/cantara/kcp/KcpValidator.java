package no.cantara.kcp;

import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;
import no.cantara.kcp.model.Relationship;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    private static final Set<String> KNOWN_KCP_VERSIONS = Set.of("0.1");

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

    public static ValidationResult validate(KnowledgeManifest manifest) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> unitIds = manifest.units().stream().map(KnowledgeUnit::id).collect(Collectors.toSet());

        // kcp_version — RECOMMENDED; warn if absent or unknown
        if (manifest.kcpVersion() == null || manifest.kcpVersion().isBlank()) {
            warnings.add("manifest: 'kcp_version' not declared; assuming 0.1");
        } else if (!KNOWN_KCP_VERSIONS.contains(manifest.kcpVersion())) {
            warnings.add("manifest: unknown kcp_version '" + manifest.kcpVersion() +
                    "'; processing as " + KNOWN_KCP_VERSIONS.stream().max(String::compareTo).orElse("0.1"));
        }

        // Required root fields
        if (manifest.project() == null || manifest.project().isBlank()) {
            errors.add("manifest: 'project' is required");
        }
        if (manifest.units().isEmpty()) {
            errors.add("manifest: 'units' must not be empty");
        }

        for (KnowledgeUnit unit : manifest.units()) {
            String p = "unit '" + unit.id() + "'";

            if (unit.id() == null || unit.id().isBlank()) {
                errors.add("unit: 'id' is required");
                continue;
            }
            if (unit.path() == null || unit.path().isBlank()) {
                errors.add(p + ": 'path' is required");
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

            for (String dep : unit.dependsOn()) {
                if (!unitIds.contains(dep)) {
                    warnings.add(p + ": 'depends_on' references unknown unit '" + dep + "'");
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

    private static List<String> sorted(Set<String> set) {
        return set.stream().sorted().toList();
    }
}
