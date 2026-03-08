package no.cantara.kcp.simulator.aml;

/**
 * Result of an access decision for a knowledge unit.
 * Uses sealed interface with records for exhaustive pattern matching (Java 17+).
 */
public sealed interface AccessDecision
        permits AccessDecision.Granted, AccessDecision.Denied, AccessDecision.RequiresHumanApproval {

    /** Access granted -- the unit content can be loaded. */
    record Granted(String unitId) implements AccessDecision {}

    /** Access denied -- the reason explains why. */
    record Denied(String unitId, String reason) implements AccessDecision {}

    /** Access requires human approval before content can be loaded. */
    record RequiresHumanApproval(String unitId, String sensitivity, String approvalMechanism)
            implements AccessDecision {}
}
