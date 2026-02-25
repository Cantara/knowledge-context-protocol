package no.cantara.kcp.model;

import java.time.LocalDate;
import java.util.List;

/**
 * The root KCP manifest parsed from knowledge.yaml.
 */
public record KnowledgeManifest(
        String kcpVersion,
        String project,
        String version,
        LocalDate updated,
        List<KnowledgeUnit> units,
        List<Relationship> relationships
) {
    public KnowledgeManifest {
        units = units != null ? List.copyOf(units) : List.of();
        relationships = relationships != null ? List.copyOf(relationships) : List.of();
    }
}
