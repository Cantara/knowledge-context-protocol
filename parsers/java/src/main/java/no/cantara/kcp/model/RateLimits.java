package no.cantara.kcp.model;

/**
 * Rate limits block — root-level and per-unit override. See SPEC.md §4.15.
 * Per-tier, token, header, and backoff fields promoted in v0.25.
 */
public record RateLimits(
        RateLimit defaultLimit,
        RateLimit authenticated,        // v0.25
        RateLimit premium,              // v0.25
        RateLimitTokens tokens,         // v0.25
        RateLimitHeaders headers,       // v0.25
        String backoff                  // v0.25 — linear | exponential | none
) {}
