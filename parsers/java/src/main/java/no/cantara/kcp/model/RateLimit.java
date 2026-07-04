package no.cantara.kcp.model;

/**
 * Rate limits for one authentication tier — part of the rate_limits block.
 * See SPEC.md §4.15. Count fields are Integer, or the String sentinel
 * "unlimited" (v0.25), so their declared type is Object.
 */
public record RateLimit(
        Object requestsPerMinute,
        Object requestsPerHour,   // v0.25
        Object requestsPerDay
) {}
