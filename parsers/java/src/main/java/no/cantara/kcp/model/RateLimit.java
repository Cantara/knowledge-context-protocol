package no.cantara.kcp.model;

/**
 * Default rate limit tier — part of the rate_limits block. See SPEC.md §4.15.
 */
public record RateLimit(
        Integer requestsPerMinute,
        Integer requestsPerDay
) {}
