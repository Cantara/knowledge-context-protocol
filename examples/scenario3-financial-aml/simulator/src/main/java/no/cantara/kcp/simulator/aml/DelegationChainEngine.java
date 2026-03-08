package no.cantara.kcp.simulator.aml;

import java.util.Map;

/**
 * Evaluates delegation chain constraints: depth limits, capability attenuation,
 * and no-delegation enforcement for AML multi-agent scenarios.
 */
public final class DelegationChainEngine {

    private final DelegationConfig rootDelegation;
    private final Map<String, DelegationConfig> unitDelegation;

    public DelegationChainEngine(DelegationConfig rootDelegation,
                                  Map<String, DelegationConfig> unitDelegation) {
        this.rootDelegation = rootDelegation;
        this.unitDelegation = unitDelegation;
    }

    /**
     * Result of a delegation check.
     */
    public sealed interface DelegationDecision
            permits DelegationDecision.Allowed, DelegationDecision.Blocked {

        record Allowed(String unitId, int depth, String effectiveScope) implements DelegationDecision {}
        record Blocked(String unitId, int requestedDepth, String reason) implements DelegationDecision {}
    }

    /**
     * Check whether a unit can be delegated to a given depth.
     */
    public DelegationDecision checkDelegation(String unitId, int requestedDepth,
                                               String originalScope, String narrowedScope) {
        DelegationConfig config = unitDelegation.getOrDefault(unitId, rootDelegation);

        // max_depth=0 means no delegation at all
        if (config.maxDepth() == 0) {
            return new DelegationDecision.Blocked(unitId, requestedDepth,
                    "Unit has max_depth=0: this unit cannot be delegated");
        }

        // Check depth limit
        if (requestedDepth > config.maxDepth()) {
            return new DelegationDecision.Blocked(unitId, requestedDepth,
                    "Depth " + requestedDepth + " exceeds max_depth=" + config.maxDepth());
        }

        // Check capability attenuation
        String effectiveScope = narrowedScope != null ? narrowedScope : originalScope;
        if (rootDelegation.requireCapabilityAttenuation() && requestedDepth > 0) {
            if (narrowedScope == null || narrowedScope.equals(originalScope)) {
                return new DelegationDecision.Blocked(unitId, requestedDepth,
                        "Capability attenuation required: delegated scope must be narrower than '"
                                + originalScope + "'");
            }
        }

        return new DelegationDecision.Allowed(unitId, requestedDepth, effectiveScope);
    }

    public boolean isDelegatable(String unitId) {
        return unitDelegation.getOrDefault(unitId, rootDelegation).maxDepth() > 0;
    }

    public int maxDepthFor(String unitId) {
        return unitDelegation.getOrDefault(unitId, rootDelegation).maxDepth();
    }
}
