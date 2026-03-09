package no.cantara.kcp.model;

/**
 * Delegation constraints block — root-level and per-unit override.
 * Controls how agents may re-delegate tasks to sub-agents. See SPEC.md §3.4.
 */
public record Delegation(
        Integer maxDepth,
        Boolean requireCapabilityAttenuation,
        Boolean auditChain,
        HumanInTheLoop humanInTheLoop
) {
}
