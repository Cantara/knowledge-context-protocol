package no.cantara.kcp.model;

/**
 * One source in a {@code grant_ceiling} minimum computation. Exactly one of
 * {@code authorityLevel}, {@code unitRef}, {@code taskTypeRef}, {@code agentRef} SHOULD be
 * present — the resolved value is either the inline {@code authorityLevel} or looked up from
 * the referenced entity's own declared ceiling. See SPEC.md §3.13 (RFC-0025, v0.27).
 *
 * @param id             Stable identifier for this source, used to name the binding cause.
 * @param authorityLevel Inline ceiling value, one of the root {@code authority_level_scale}.
 * @param unitRef        id of a {@code units[]} entry whose own {@code authority_level} resolves this source.
 * @param taskTypeRef    id of a {@code task_types[]} entry whose own {@code authority_level} resolves this source.
 * @param agentRef       id of an {@code agents[]} entry whose own {@code authority_level} resolves this source.
 */
public record GrantCeilingSource(
        String id,
        String authorityLevel,
        String unitRef,
        String taskTypeRef,
        String agentRef
) {}
