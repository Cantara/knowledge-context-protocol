package no.cantara.kcp.model;

/**
 * Rate limits block — root-level and per-unit override. See SPEC.md §4.15.
 */
public record RateLimits(
        RateLimit defaultLimit
) {}
