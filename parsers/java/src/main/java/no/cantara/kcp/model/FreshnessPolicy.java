package no.cantara.kcp.model;

/**
 * Freshness policy for a knowledge unit or manifest default. See SPEC.md §3.7 (v0.11).
 *
 * @param maxAgeDays      Days since {@code validated} after which the unit is stale. Optional.
 * @param onStale         Advisory action when stale: {@code warn} | {@code degrade} | {@code block}.
 *                        Default: {@code warn}.
 * @param reviewContact   Email or URL for requesting re-validation. Optional.
 */
public record FreshnessPolicy(
        Integer maxAgeDays,
        String onStale,
        String reviewContact
) {}
