package no.cantara.kcp.model;

import java.time.LocalDate;
import java.util.List;

/**
 * A single knowledge unit in a KCP manifest.
 */
public record KnowledgeUnit(
        String id,
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
        Object payment,
        RateLimits rateLimits,
        Delegation delegation,
        Compliance compliance,
        List<ExternalDependency> externalDependsOn,
        List<String> requiresCapabilities,
        FreshnessPolicy freshnessPolicy
) {
    public KnowledgeUnit {
        audience = audience != null ? List.copyOf(audience) : List.of();
        dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
        triggers = triggers != null ? List.copyOf(triggers) : List.of();
        externalDependsOn = externalDependsOn != null ? List.copyOf(externalDependsOn) : List.of();
        requiresCapabilities = requiresCapabilities != null ? List.copyOf(requiresCapabilities) : List.of();
    }
}
