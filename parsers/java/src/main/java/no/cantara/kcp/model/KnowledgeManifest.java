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
        String language,
        Object license,
        Object indexing,
        Object hints,
        Trust trust,
        Auth auth,
        Delegation delegation,
        Compliance compliance,
        Object payment,
        RateLimits rateLimits,
        List<KnowledgeUnit> units,
        List<Relationship> relationships,
        List<ManifestRef> manifests,
        List<ExternalRelationship> externalRelationships,
        FreshnessPolicy freshnessPolicy,
        Visibility visibility,
        Authority authority,
        Discovery discovery
) {
    public KnowledgeManifest {
        units = units != null ? List.copyOf(units) : List.of();
        relationships = relationships != null ? List.copyOf(relationships) : List.of();
        manifests = manifests != null ? List.copyOf(manifests) : List.of();
        externalRelationships = externalRelationships != null ? List.copyOf(externalRelationships) : List.of();
    }
}
