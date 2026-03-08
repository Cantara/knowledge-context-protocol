package no.cantara.kcp.simulator.aml;

import java.util.List;
import java.util.Map;

/**
 * Compliance constraints extracted from raw YAML (RFC-0004, not in v0.6 core).
 * These fields are application-defined until RFC-0004 promotes to core.
 *
 * @param regulations         applicable regulations (e.g. GDPR, AML5D, NIS2, FATF)
 * @param dataResidencyRegions permitted data residency regions (e.g. [EU])
 * @param restrictions        usage restrictions (e.g. no_ai_training, no_cross_border)
 */
public record ComplianceConfig(
        List<String> regulations,
        List<String> dataResidencyRegions,
        List<String> restrictions
) {

    public static final ComplianceConfig EMPTY = new ComplianceConfig(List.of(), List.of(), List.of());

    /**
     * Parse compliance config from a raw unit YAML map.
     */
    @SuppressWarnings("unchecked")
    public static ComplianceConfig fromUnitYaml(Map<String, Object> unitData) {
        Map<String, Object> compliance = (Map<String, Object>) unitData.get("compliance");
        if (compliance == null) {
            return EMPTY;
        }

        List<String> regulations = (List<String>) compliance.getOrDefault("regulations", List.of());
        List<String> restrictions = (List<String>) compliance.getOrDefault("restrictions", List.of());

        List<String> regions = List.of();
        Map<String, Object> residency = (Map<String, Object>) compliance.get("data_residency");
        if (residency != null) {
            regions = (List<String>) residency.getOrDefault("regions", List.of());
        }

        return new ComplianceConfig(
                List.copyOf(regulations),
                List.copyOf(regions),
                List.copyOf(restrictions)
        );
    }

    /** Check if a specific regulation applies to this unit. */
    public boolean hasRegulation(String regulation) {
        return regulations.contains(regulation);
    }

    /** Check if a specific restriction applies. */
    public boolean hasRestriction(String restriction) {
        return restrictions.contains(restriction);
    }

    /** Check if a region is permitted for data residency. Empty list means no constraint. */
    public boolean isRegionPermitted(String region) {
        if (dataResidencyRegions.isEmpty()) return true;
        return dataResidencyRegions.contains(region);
    }
}
