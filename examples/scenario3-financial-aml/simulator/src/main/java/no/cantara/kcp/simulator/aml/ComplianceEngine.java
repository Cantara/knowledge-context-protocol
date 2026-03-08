package no.cantara.kcp.simulator.aml;

import java.util.Map;

/**
 * Evaluates compliance constraints on knowledge unit access.
 * These are RFC-0004 fields (not in v0.6 core), enforced as application-defined checks.
 */
public final class ComplianceEngine {

    private final Map<String, ComplianceConfig> unitCompliance;

    public ComplianceEngine(Map<String, ComplianceConfig> unitCompliance) {
        this.unitCompliance = unitCompliance;
    }

    /**
     * Result of a compliance check.
     */
    public sealed interface ComplianceDecision
            permits ComplianceDecision.Compliant, ComplianceDecision.Violation {

        record Compliant(String unitId) implements ComplianceDecision {}
        record Violation(String unitId, String reason, String regulation) implements ComplianceDecision {}
    }

    /**
     * Check data residency compliance for a unit.
     *
     * @param unitId        the unit being accessed
     * @param requestRegion the region from which the request originates
     * @return compliant or violation
     */
    public ComplianceDecision checkDataResidency(String unitId, String requestRegion) {
        ComplianceConfig config = unitCompliance.getOrDefault(unitId, ComplianceConfig.EMPTY);

        if (config.dataResidencyRegions().isEmpty()) {
            return new ComplianceDecision.Compliant(unitId);
        }

        if (!config.isRegionPermitted(requestRegion)) {
            return new ComplianceDecision.Violation(unitId,
                    "Data residency violation: request from '" + requestRegion
                            + "' but unit restricted to " + config.dataResidencyRegions(),
                    "GDPR");
        }

        return new ComplianceDecision.Compliant(unitId);
    }

    /**
     * Check if a restriction applies to a unit.
     *
     * @param unitId     the unit being accessed
     * @param restriction the restriction to check (e.g. "no_ai_training")
     * @return true if the restriction is declared
     */
    public boolean hasRestriction(String unitId, String restriction) {
        return unitCompliance.getOrDefault(unitId, ComplianceConfig.EMPTY)
                .hasRestriction(restriction);
    }

    /**
     * Get the compliance config for a unit.
     */
    public ComplianceConfig getConfig(String unitId) {
        return unitCompliance.getOrDefault(unitId, ComplianceConfig.EMPTY);
    }
}
