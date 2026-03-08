package no.cantara.kcp.simulator.legal;

import java.util.Map;
import java.util.Optional;

/**
 * Delegation constraints extracted from raw YAML (not part of KcpParser model).
 * These come from the RFC-0002 Proposal 3 delegation block.
 *
 * @param maxDepth                       maximum delegation chain depth
 * @param requireCapabilityAttenuation   whether capability attenuation is required
 * @param auditChain                     whether an audit chain is required
 * @param humanInTheLoopRequired         whether human approval is required (per-unit override)
 * @param approvalMechanism              the approval mechanism when HITL is required
 */
public record DelegationConfig(
        int maxDepth,
        boolean requireCapabilityAttenuation,
        boolean auditChain,
        boolean humanInTheLoopRequired,
        String approvalMechanism
) {

    /**
     * Parse root-level delegation from raw YAML map.
     */
    @SuppressWarnings("unchecked")
    public static DelegationConfig fromRootYaml(Map<String, Object> rootData) {
        Map<String, Object> delegation = (Map<String, Object>) rootData.get("delegation");
        if (delegation == null) {
            return new DelegationConfig(0, false, false, false, null);
        }
        int maxDepth = toInt(delegation.get("max_depth"), 0);
        boolean attenuation = toBool(delegation.get("require_capability_attenuation"), false);
        boolean audit = toBool(delegation.get("audit_chain"), false);
        return new DelegationConfig(maxDepth, attenuation, audit, false, null);
    }

    /**
     * Parse per-unit delegation override from a raw unit YAML map.
     * Falls back to the root delegation for fields not overridden.
     */
    @SuppressWarnings("unchecked")
    public static DelegationConfig fromUnitYaml(Map<String, Object> unitData, DelegationConfig root) {
        Map<String, Object> delegation = (Map<String, Object>) unitData.get("delegation");
        if (delegation == null) {
            return root;
        }
        int maxDepth = toInt(delegation.get("max_depth"), root.maxDepth());
        boolean attenuation = root.requireCapabilityAttenuation();
        boolean audit = root.auditChain();

        boolean hitlRequired = false;
        String mechanism = null;
        Map<String, Object> hitl = (Map<String, Object>) delegation.get("human_in_the_loop");
        if (hitl != null) {
            hitlRequired = toBool(hitl.get("required"), false);
            Object mech = hitl.get("approval_mechanism");
            mechanism = mech != null ? mech.toString() : null;
        }

        return new DelegationConfig(maxDepth, attenuation, audit, hitlRequired, mechanism);
    }

    public Optional<String> approvalMechanismOpt() {
        return Optional.ofNullable(approvalMechanism);
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try { return Integer.parseInt(value.toString()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private static boolean toBool(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value != null) return Boolean.parseBoolean(value.toString());
        return fallback;
    }
}
