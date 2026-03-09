package no.cantara.kcp.simulator.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks request budgets per unit per time window.
 * <p>
 * Uses a pluggable clock so tests can advance time without real sleeps.
 */
public final class RequestBudget {

    /** Pluggable clock for testing. */
    @FunctionalInterface
    public interface Clock {
        Instant now();
    }

    private static final long MINUTE_MILLIS = 60_000L;

    private final Map<String, RateLimit> limits;
    private final Map<String, Integer> minuteCounters = new HashMap<>();
    private final Map<String, Integer> dayCounters = new HashMap<>();
    private final Map<String, Long> minuteWindowStart = new HashMap<>();
    private final Map<String, Long> dayWindowStart = new HashMap<>();
    private final Clock clock;

    public RequestBudget(Map<String, RateLimit> limits, Clock clock) {
        this.limits = Map.copyOf(limits);
        this.clock = clock;
    }

    public RequestBudget(Map<String, RateLimit> limits) {
        this(limits, Instant::now);
    }

    /**
     * Check if a request to the given unit would be within advisory limits.
     */
    public boolean isWithinLimit(String unitId) {
        RateLimit limit = limits.getOrDefault(unitId, RateLimit.UNLIMITED);
        if (limit.isUnlimited()) return true;

        long now = clock.now().toEpochMilli();
        resetWindowsIfNeeded(unitId, now);

        if (limit.requestsPerMinute() != null) {
            int used = minuteCounters.getOrDefault(unitId, 0);
            if (used >= limit.requestsPerMinute()) return false;
        }
        if (limit.requestsPerDay() != null) {
            int used = dayCounters.getOrDefault(unitId, 0);
            if (used >= limit.requestsPerDay()) return false;
        }
        return true;
    }

    /**
     * Record a request to the given unit.
     */
    public void recordRequest(String unitId) {
        long now = clock.now().toEpochMilli();
        resetWindowsIfNeeded(unitId, now);

        minuteCounters.merge(unitId, 1, Integer::sum);
        dayCounters.merge(unitId, 1, Integer::sum);
    }

    /**
     * How many seconds until the per-minute window resets for the given unit.
     * Returns 0 if the unit is within limits or has no per-minute limit.
     */
    public int getSecondsUntilMinuteReset(String unitId) {
        RateLimit limit = limits.getOrDefault(unitId, RateLimit.UNLIMITED);
        if (limit.requestsPerMinute() == null) return 0;

        long now = clock.now().toEpochMilli();
        Long windowStart = minuteWindowStart.get(unitId);
        if (windowStart == null) return 0;

        long elapsed = now - windowStart;
        if (elapsed >= MINUTE_MILLIS) return 0;

        return (int) Math.ceil((MINUTE_MILLIS - elapsed) / 1000.0);
    }

    /**
     * Get the number of requests made in the current minute window.
     */
    public int getMinuteUsed(String unitId) {
        return minuteCounters.getOrDefault(unitId, 0);
    }

    /**
     * Get the number of requests made in the current day window.
     */
    public int getDayUsed(String unitId) {
        return dayCounters.getOrDefault(unitId, 0);
    }

    /**
     * Get the effective rate limit for a unit.
     */
    public RateLimit getLimit(String unitId) {
        return limits.getOrDefault(unitId, RateLimit.UNLIMITED);
    }

    private void resetWindowsIfNeeded(String unitId, long now) {
        // Minute window
        Long minStart = minuteWindowStart.get(unitId);
        if (minStart == null || (now - minStart) >= MINUTE_MILLIS) {
            minuteWindowStart.put(unitId, now);
            minuteCounters.put(unitId, 0);
        }

        // Day window (86,400,000 ms)
        Long dayStart = dayWindowStart.get(unitId);
        if (dayStart == null || (now - dayStart) >= 86_400_000L) {
            dayWindowStart.put(unitId, now);
            dayCounters.put(unitId, 0);
        }
    }
}
