package no.cantara.kcp.model;

/**
 * An agent declaration (Capability Profile). See SPEC.md §3.13 (RFC-0025, v0.27).
 *
 * @param id             Stable identifier, unique within the manifest.
 * @param name           Display name.
 * @param authorityLevel This agent's own capability ceiling, across all tasks it is
 *                       assigned — one of the root {@code authority_level_scale} values.
 */
public record Agent(
        String id,
        String name,
        String authorityLevel
) {}
