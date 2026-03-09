package no.cantara.kcp.simulator.model;

/**
 * Advisory rate limit for a knowledge unit.
 *
 * @param requestsPerMinute maximum requests per 60-second rolling window (null = no limit)
 * @param requestsPerDay    maximum requests per calendar day UTC (null = no limit)
 */
public record RateLimit(Integer requestsPerMinute, Integer requestsPerDay) {

    public static final RateLimit UNLIMITED = new RateLimit(null, null);

    /** True if both per-minute and per-day limits are declared. */
    public boolean isFullyDeclared() {
        return requestsPerMinute != null && requestsPerDay != null;
    }

    /** True if no limits are declared at all. */
    public boolean isUnlimited() {
        return requestsPerMinute == null && requestsPerDay == null;
    }
}
