package no.cantara.kcp.simulator.model;

import java.util.List;

/**
 * Simplified knowledge unit for the dependency-ordering simulation.
 *
 * @param id          unit identifier
 * @param path        file path relative to manifest root
 * @param intent      what this unit answers
 * @param access      access level: public, authenticated, restricted
 * @param sensitivity data sensitivity
 * @param dependsOn   IDs of units that should be loaded first (inline field)
 * @param supersedes  ID of the unit this replaces (null if none)
 * @param deprecated  whether this unit is deprecated
 */
public record KnowledgeUnit(
        String id,
        String path,
        String intent,
        String access,
        String sensitivity,
        List<String> dependsOn,
        String supersedes,
        boolean deprecated
) {
    public KnowledgeUnit {
        dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
    }
}
