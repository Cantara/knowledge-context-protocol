package no.cantara.kcp.simulator.legal;

import java.util.Map;

/**
 * Evaluates delegation chain constraints: depth limits, capability attenuation,
 * and no-delegation enforcement.
 *
 * <p>Depth counting convention (adopted by this simulator):
 * <ul>
 *   <li>depth=0: the resource owner (LegalResearchAgent)</li>
 *   <li>depth=1: first delegatee (CaseOrchestratorAgent)</li>
 *   <li>depth=2: second delegatee (ExternalCaseLawAgent)</li>
 * </ul>
 *
 * <p>This convention is flagged as a spec gap -- KCP v0.6 does not define
 * whether the owner is depth=0 or depth=1.
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
     *
     * @param unitId         the unit being delegated
     * @param requestedDepth the delegation depth of the receiving agent
     * @param originalScope  the scope held by the delegating agent
     * @param narrowedScope  the scope being offered to the delegatee (may be null = same scope)
     * @return allowed or blocked
     */
    public DelegationDecision checkDelegation(String unitId, int requestedDepth,
                                               String originalScope, String narrowedScope) {
        DelegationConfig config = unitDelegation.getOrDefault(unitId, rootDelegation);

        // Check max_depth: 0 means no delegation at all
        if (config.maxDepth() == 0) {
            return new DelegationDecision.Blocked(unitId, requestedDepth,
                    "Unit has max_depth=0: delegation is not permitted");
        }

        // Check depth limit
        if (requestedDepth > config.maxDepth()) {
            return new DelegationDecision.Blocked(unitId, requestedDepth,
                    "Depth " + requestedDepth + " exceeds max_depth=" + config.maxDepth());
        }

        // Check capability attenuation (if required by root config)
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

    /**
     * Convenience: check if a unit allows delegation at all (max_depth > 0).
     */
    public boolean isDelegatable(String unitId) {
        DelegationConfig config = unitDelegation.getOrDefault(unitId, rootDelegation);
        return config.maxDepth() > 0;
    }

    /**
     * Get the max_depth for a unit.
     */
    public int maxDepthFor(String unitId) {
        return unitDelegation.getOrDefault(unitId, rootDelegation).maxDepth();
    }
}
