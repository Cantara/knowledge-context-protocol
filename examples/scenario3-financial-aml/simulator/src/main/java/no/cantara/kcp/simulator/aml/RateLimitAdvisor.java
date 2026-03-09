package no.cantara.kcp.simulator.aml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks advisory rate limit usage per unit and logs violations.
 * <p>
 * Rate limits in KCP are advisory (SPEC.md §4.15): agents SHOULD self-throttle,
 * but the limits are not enforced by the manifest itself. This class records
 * each request and flags when an advisory limit is exceeded.
 */
public final class RateLimitAdvisor {

    /**
     * Advisory rate limit for a unit.
     *
     * @param requestsPerMinute max requests per minute (null = no limit)
     * @param requestsPerDay    max requests per day (null = no limit)
     */
    public record AdvisoryLimit(Integer requestsPerMinute, Integer requestsPerDay) {
        public static final AdvisoryLimit NONE = new AdvisoryLimit(null, null);
    }

    /**
     * Audit entry recording a rate limit advisory event.
     */
    public record AuditEntry(String unitId, String agentName, boolean withinLimit, String message) {}

    private final Map<String, AdvisoryLimit> unitLimits;
    private final Map<String, Integer> minuteCounters = new HashMap<>();
    private final List<AuditEntry> auditLog = new ArrayList<>();

    /**
     * @param unitLimits per-unit advisory limits (unit id -> limit)
     */
    public RateLimitAdvisor(Map<String, AdvisoryLimit> unitLimits) {
        this.unitLimits = Map.copyOf(unitLimits);
    }

    /**
     * Record a request to a unit and check whether it exceeds the advisory limit.
     *
     * @param unitId    the unit being accessed
     * @param agentName the agent making the request
     * @return true if within advisory limits, false if advisory violation
     */
    public boolean recordRequest(String unitId, String agentName) {
        AdvisoryLimit limit = unitLimits.getOrDefault(unitId, AdvisoryLimit.NONE);
        int count = minuteCounters.merge(unitId, 1, Integer::sum);

        boolean withinLimit = true;
        String message;

        if (limit.requestsPerMinute() != null && count > limit.requestsPerMinute()) {
            withinLimit = false;
            message = String.format(
                    "ADVISORY_VIOLATION: %s exceeded rate_limits advisory for %s — %d requests/min (limit: %d/min)",
                    agentName, unitId, count, limit.requestsPerMinute());
        } else {
            message = String.format(
                    "Request recorded: %s -> %s — %d/%s requests/min",
                    agentName, unitId, count,
                    limit.requestsPerMinute() != null ? limit.requestsPerMinute().toString() : "unlimited");
        }

        auditLog.add(new AuditEntry(unitId, agentName, withinLimit, message));
        return withinLimit;
    }

    /**
     * Get all audit entries.
     */
    public List<AuditEntry> auditLog() {
        return Collections.unmodifiableList(auditLog);
    }

    /**
     * Count advisory violations for a specific unit.
     */
    public long violationCount(String unitId) {
        return auditLog.stream()
                .filter(e -> !e.withinLimit() && unitId.equals(e.unitId()))
                .count();
    }

    /**
     * Total advisory violations across all units.
     */
    public long totalViolations() {
        return auditLog.stream().filter(e -> !e.withinLimit()).count();
    }

    /**
     * Get the advisory limit for a unit.
     */
    public AdvisoryLimit getLimit(String unitId) {
        return unitLimits.getOrDefault(unitId, AdvisoryLimit.NONE);
    }

    /**
     * Reset minute counters (simulates a new minute window).
     */
    public void resetMinuteCounters() {
        minuteCounters.clear();
    }
}
