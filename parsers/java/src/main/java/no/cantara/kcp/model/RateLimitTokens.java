package no.cantara.kcp.model;

/** Token-based limits, mirroring the tier structure. See SPEC.md §4.15 (v0.25). */
public record RateLimitTokens(
        RateLimitTokensTier defaultTier,
        RateLimitTokensTier authenticated,
        RateLimitTokensTier premium
) {}
