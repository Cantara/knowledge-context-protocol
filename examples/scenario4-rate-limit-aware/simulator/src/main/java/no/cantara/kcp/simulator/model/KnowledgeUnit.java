package no.cantara.kcp.simulator.model;

import java.util.List;

/**
 * Simplified knowledge unit for the rate-limit simulation.
 *
 * @param id              unit identifier
 * @param path            file path relative to manifest root
 * @param intent          what this unit answers
 * @param access          access level: public, authenticated, restricted
 * @param sensitivity     data sensitivity: public, internal, confidential, restricted
 * @param dependsOn       IDs of units that should be loaded first
 * @param rateLimit       effective rate limit (unit override or inherited root default)
 */
public record KnowledgeUnit(
        String id,
        String path,
        String intent,
        String access,
        String sensitivity,
        List<String> dependsOn,
        RateLimit rateLimit
) {
    public KnowledgeUnit {
        dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
    }
}
