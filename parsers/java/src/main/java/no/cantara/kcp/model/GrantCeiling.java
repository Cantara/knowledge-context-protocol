package no.cantara.kcp.model;

import java.util.List;

/**
 * Multi-source minimum ceiling computation. See SPEC.md §3.13 (RFC-0025, v0.27).
 *
 * @param sources          The sources contributing to the minimum. Defaults to an empty list.
 * @param mandatorySources Source ids that MUST appear in {@code sources} — a manifest error
 *                         if any is missing. {@code null}/absent means no mandatory sources.
 */
public record GrantCeiling(
        List<GrantCeilingSource> sources,
        List<String> mandatorySources
) {
    public GrantCeiling {
        sources = sources != null ? List.copyOf(sources) : List.of();
    }
}
