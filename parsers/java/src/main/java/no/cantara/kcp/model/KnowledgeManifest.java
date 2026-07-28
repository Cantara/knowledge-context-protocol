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
        Payment payment,
        RateLimits rateLimits,
        Serving serving,                 // RFC-0024 / §3.12 (v0.26)
        List<KnowledgeUnit> units,
        List<Relationship> relationships,
        List<ManifestRef> manifests,
        List<ExternalRelationship> externalRelationships,
        FreshnessPolicy freshnessPolicy,
        Visibility visibility,
        Authority authority,
        Discovery discovery,
        List<String> notFor,
        Temporal temporal,
        List<String> authorityLevelScale,  // RFC-0025 / §3.13 (v0.27) — fixed ordinal scale; null = not declared
        List<TaskType> taskTypes,          // RFC-0025 / §3.13 (v0.27) — defaults to []
        List<Agent> agents,                // RFC-0025 / §3.13 (v0.27) — defaults to []
        GrantCeiling grantCeiling,         // RFC-0025 / §3.13 (v0.27)
        // #166: problems noticed while parsing that no later stage can reconstruct. A
        // value failing scalar resolution (§2.1) is dropped and an unknown field is
        // discarded per §2, so by the time a validator runs both are indistinguishable
        // from a field never written. Diagnostics report; they never rescue.
        List<String> parseDiagnostics
) {
    public KnowledgeManifest {
        units = units != null ? List.copyOf(units) : List.of();
        relationships = relationships != null ? List.copyOf(relationships) : List.of();
        manifests = manifests != null ? List.copyOf(manifests) : List.of();
        externalRelationships = externalRelationships != null ? List.copyOf(externalRelationships) : List.of();
        notFor = notFor != null ? List.copyOf(notFor) : List.of();
        taskTypes = taskTypes != null ? List.copyOf(taskTypes) : List.of();
        agents = agents != null ? List.copyOf(agents) : List.of();
    }
}
