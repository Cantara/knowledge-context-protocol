package no.cantara.kcp.model;

/** Response-header names carrying live limit state. See SPEC.md §4.15 (v0.25). */
public record RateLimitHeaders(
        String remaining,
        String reset,
        String retryAfter
) {}
