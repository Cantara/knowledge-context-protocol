package no.cantara.kcp.model;

import java.time.LocalDate;
import java.util.List;

/**
 * A single knowledge unit in a KCP manifest.
 */
public record KnowledgeUnit(
        String id,
        java.util.List<String> aliases,   // RFC-0023 / §4.2a (v0.26)
        String path,
        String kind,
        String intent,
        String format,
        String contentType,
        String language,
        String scope,
        List<String> audience,
        Object license,
        LocalDate validated,
        String updateFrequency,
        Object indexing,
        List<String> dependsOn,
        String supersedes,
        List<String> triggers,
        Object hints,
        String access,
        String authScope,
        String sensitivity,
        Boolean deprecated,
        Payment payment,
        RateLimits rateLimits,
        Delegation delegation,
        Compliance compliance,
        Auth auth,   // v0.23 — per-unit auth override (SPEC §3.3)
        List<ExternalDependency> externalDependsOn,
        List<String> requiresCapabilities,
        FreshnessPolicy freshnessPolicy,
        Visibility visibility,
        Authority authority,
        Discovery discovery,
        List<String> notFor,
        Boolean notForStrict,
        ContentStructure contentStructure,
        ContentHash contentHash,
        Temporal temporal,
        String authorityLevel,  // RFC-0025 / §4.23 (v0.27) — ceiling on the root authority_level_scale
        ActionScope actionScope  // §4.3a (v0.26.1) — what a kind: skill procedure may touch
) {
    public KnowledgeUnit {
        audience = audience != null ? List.copyOf(audience) : List.of();
        dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
        triggers = triggers != null ? List.copyOf(triggers) : List.of();
        externalDependsOn = externalDependsOn != null ? List.copyOf(externalDependsOn) : List.of();
        requiresCapabilities = requiresCapabilities != null ? List.copyOf(requiresCapabilities) : List.of();
        notFor = notFor != null ? List.copyOf(notFor) : List.of();
    }
}
