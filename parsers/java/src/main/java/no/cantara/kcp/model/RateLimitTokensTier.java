package no.cantara.kcp.model;

/** Token-based limits for one authentication tier. See SPEC.md §4.15 (v0.25). */
public record RateLimitTokensTier(
        Object tokensPerMinute,
        Object tokensPerDay
) {}
