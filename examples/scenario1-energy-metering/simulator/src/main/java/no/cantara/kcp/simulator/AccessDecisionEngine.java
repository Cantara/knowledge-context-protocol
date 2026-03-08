package no.cantara.kcp.simulator;

import no.cantara.kcp.model.KnowledgeUnit;

import java.util.Map;

/**
 * Evaluates access decisions for KCP knowledge units based on:
 * <ul>
 *   <li>Unit access level (public, authenticated, restricted)</li>
 *   <li>Token validity and scope</li>
 *   <li>Delegation config (human-in-the-loop requirement)</li>
 * </ul>
 */
public final class AccessDecisionEngine {

    private final SimulatedOAuth2 oauth2;
    private final Map<String, DelegationConfig> unitDelegation;

    /**
     * @param oauth2         the simulated OAuth2 provider for token validation
     * @param unitDelegation per-unit delegation configs keyed by unit id
     */
    public AccessDecisionEngine(SimulatedOAuth2 oauth2, Map<String, DelegationConfig> unitDelegation) {
        this.oauth2 = oauth2;
        this.unitDelegation = unitDelegation;
    }

    /**
     * Evaluate access for a knowledge unit.
     *
     * @param unit       the knowledge unit to access
     * @param tokenValue the OAuth2 token (may be null for public access)
     * @return the access decision
     */
    public AccessDecision evaluate(KnowledgeUnit unit, String tokenValue) {
        String access = unit.access() != null ? unit.access() : "public";
        String unitId = unit.id();

        switch (access) {
            case "public":
                return new AccessDecision.Granted(unitId);

            case "authenticated":
                if (tokenValue == null) {
                    return new AccessDecision.Denied(unitId, "No credential provided");
                }
                String authScope = scopeForUnit(unit);
                if (!oauth2.validate(tokenValue, authScope)) {
                    return new AccessDecision.Denied(unitId, "Invalid token or missing scope: " + authScope);
                }
                // Check HITL even for authenticated
                DelegationConfig authDelegation = unitDelegation.get(unitId);
                if (authDelegation != null && authDelegation.humanInTheLoopRequired()) {
                    String mechanism = authDelegation.approvalMechanismOpt().orElse("manual");
                    String sensitivity = unit.sensitivity() != null ? unit.sensitivity() : "internal";
                    return new AccessDecision.RequiresHumanApproval(unitId, sensitivity, mechanism);
                }
                return new AccessDecision.Granted(unitId);

            case "restricted":
                if (tokenValue == null) {
                    return new AccessDecision.Denied(unitId, "No credential provided");
                }
                String restrictedScope = scopeForUnit(unit);
                if (!oauth2.validate(tokenValue, restrictedScope)) {
                    return new AccessDecision.Denied(unitId, "Invalid token or missing scope: " + restrictedScope);
                }
                // Check HITL for restricted units
                DelegationConfig delegation = unitDelegation.get(unitId);
                if (delegation != null && delegation.humanInTheLoopRequired()) {
                    String mechanism = delegation.approvalMechanismOpt().orElse("manual");
                    String sensitivity = unit.sensitivity() != null ? unit.sensitivity() : "restricted";
                    return new AccessDecision.RequiresHumanApproval(unitId, sensitivity, mechanism);
                }
                return new AccessDecision.Granted(unitId);

            default:
                return new AccessDecision.Denied(unitId, "Unknown access level: " + access);
        }
    }

    /**
     * Map a unit to its required OAuth2 scope.
     * Uses the explicit auth_scope field when present, otherwise derives from unit id.
     */
    String scopeForUnit(KnowledgeUnit unit) {
        if (unit.authScope() != null) {
            return unit.authScope();
        }
        return scopeFromId(unit.id());
    }

    /**
     * Derive a scope name from a unit id.
     * E.g. "public-guidelines" -> "read:guidelines", "trial-protocols" -> "read:protocols"
     */
    static String scopeFromId(String unitId) {
        if (unitId == null) return "read:unknown";
        int dash = unitId.indexOf('-');
        if (dash >= 0 && dash < unitId.length() - 1) {
            return "read:" + unitId.substring(dash + 1);
        }
        return "read:" + unitId;
    }
}
