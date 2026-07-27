package no.cantara.kcp.model;

import java.util.List;

/**
 * One step of a {@code kind: playbook} composition. See SPEC.md §4.3b (v0.29, RFC-0027).
 *
 * <p>The step — not the playbook — is the unit of governance. {@code authorityLevel} is a
 * ceiling on this step alone; effective authority is the minimum across it, the playbook's,
 * the task-type grant_ceiling, any tenant ceiling, and the enacting agent's own grant. A
 * playbook can therefore never raise authority: composing units cannot grant what neither
 * the units nor the grants allow.
 *
 * <p>{@code escalation} is a list even when the manifest declares a bare string. The
 * triggers are disjunctive — any one firing suspends the step — so a scalar and a
 * one-element list mean the same thing, and normalising at parse time means no consumer
 * has to handle both shapes.
 *
 * <p>Mirrors {@code shared/src/parser.ts} {@code parseSteps} and the Python
 * {@code PlaybookStep} dataclass.
 */
public record PlaybookStep(
        String id,                // unique within the playbook
        String uses,              // unit id this step enacts; SHOULD name a kind: skill unit
        String action,            // inline description, when no unit exists yet
        List<String> dependsOn,   // step ids that must complete successfully first
        String authorityLevel,    // RFC-0025 scale; ceiling semantics — at most this level
        List<String> escalation,  // RFC-0026 triggers; disjunctive, evaluated pre-enactment
        String successCondition,  // prose assertion; the protocol never evaluates it
        String onFailure,         // abort | continue | escalate; default abort
        String timeout            // ISO 8601 duration; elapsing constitutes failure
) {
    public PlaybookStep {
        dependsOn = dependsOn != null ? List.copyOf(dependsOn) : null;
        escalation = escalation != null ? List.copyOf(escalation) : null;
    }
}
