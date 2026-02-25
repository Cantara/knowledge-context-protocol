package no.cantara.kcp.model;

import java.time.LocalDate;
import java.util.List;

/**
 * A single knowledge unit in a KCP manifest.
 */
public record KnowledgeUnit(
        String id,
        String path,
        String intent,
        String scope,
        List<String> audience,
        LocalDate validated,
        List<String> dependsOn,
        String supersedes,
        List<String> triggers
) {
    public KnowledgeUnit {
        audience = audience != null ? List.copyOf(audience) : List.of();
        dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
        triggers = triggers != null ? List.copyOf(triggers) : List.of();
    }
}
