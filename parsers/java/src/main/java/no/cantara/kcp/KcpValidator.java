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
 */
public class KcpValidator {

    private static final Set<String> VALID_SCOPES = Set.of("global", "project", "module");
    private static final Set<String> VALID_AUDIENCES = Set.of("human", "agent", "developer", "operator", "architect");
    private static final Set<String> VALID_RELATIONSHIP_TYPES = Set.of("enables", "context", "supersedes", "contradicts");

    public static List<String> validate(KnowledgeManifest manifest) {
        List<String> errors = new ArrayList<>();
        Set<String> unitIds = manifest.units().stream().map(KnowledgeUnit::id).collect(Collectors.toSet());

        if (manifest.project() == null || manifest.project().isBlank()) {
            errors.add("manifest: 'project' is required");
        }
        if (manifest.version() == null || manifest.version().isBlank()) {
            errors.add("manifest: 'version' is required");
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
                errors.add(p + ": unknown audience value(s): " + invalidAudience);
            }

            for (String dep : unit.dependsOn()) {
                if (!unitIds.contains(dep)) {
                    errors.add(p + ": 'depends_on' references unknown unit '" + dep + "'");
                }
            }
        }

        for (Relationship rel : manifest.relationships()) {
            String p = "relationship '" + rel.fromId() + "' -> '" + rel.toId() + "'";
            if (!unitIds.contains(rel.fromId())) {
                errors.add(p + ": 'from' references unknown unit '" + rel.fromId() + "'");
            }
            if (!unitIds.contains(rel.toId())) {
                errors.add(p + ": 'to' references unknown unit '" + rel.toId() + "'");
            }
            if (!VALID_RELATIONSHIP_TYPES.contains(rel.type())) {
                errors.add(p + ": 'type' must be one of " + sorted(VALID_RELATIONSHIP_TYPES) + ", got '" + rel.type() + "'");
            }
        }

        return errors;
    }

    private static List<String> sorted(Set<String> set) {
        return set.stream().sorted().toList();
    }
}
