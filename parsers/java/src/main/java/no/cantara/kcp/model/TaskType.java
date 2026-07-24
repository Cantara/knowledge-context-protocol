package no.cantara.kcp.model;

/**
 * A task-type declaration. See SPEC.md §3.13 (RFC-0025, v0.27).
 *
 * @param id             Stable identifier, unique within the manifest.
 * @param intent         One-sentence description, in the style of unit {@code intent}.
 * @param authorityLevel This task-type's own declared ceiling — one of the root
 *                       {@code authority_level_scale} values.
 */
public record TaskType(
        String id,
        String intent,
        String authorityLevel
) {}
