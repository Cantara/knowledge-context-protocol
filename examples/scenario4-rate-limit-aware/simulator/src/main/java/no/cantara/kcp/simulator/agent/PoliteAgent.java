package no.cantara.kcp.simulator.agent;

import no.cantara.kcp.simulator.audit.AuditLog;
import no.cantara.kcp.simulator.model.KnowledgeUnit;
import no.cantara.kcp.simulator.model.RateLimit;
import no.cantara.kcp.simulator.model.RequestBudget;

import java.util.ArrayList;
import java.util.List;

/**
 * An agent that reads advisory rate_limits from the KCP manifest and
 * self-throttles accordingly.
 * <p>
 * When the per-minute budget is exhausted, the PoliteAgent simulates waiting
 * (via the clock abstraction) rather than performing a real sleep. Each
 * throttle event is logged.
 */
public final class PoliteAgent {

    public static final String NAME = "PoliteAgent";

    private final RequestBudget budget;
    private final AuditLog auditLog;
    private final List<String> log = new ArrayList<>();
    private int throttleCount = 0;
    private long simulatedWaitMs = 0;

    public PoliteAgent(RequestBudget budget, AuditLog auditLog) {
        this.budget = budget;
        this.auditLog = auditLog;
    }

    /**
     * Process a list of knowledge units in order, respecting rate limits.
     * Each unit is requested {@code requestsPerUnit} times.
     *
     * @param units           the units to process
     * @param requestsPerUnit how many times to request each unit
     * @param timestampMs     simulated starting timestamp
     * @return the final simulated timestamp after all requests
     */
    public long processUnits(List<KnowledgeUnit> units, int requestsPerUnit, long timestampMs) {
        long time = timestampMs;
        for (KnowledgeUnit unit : units) {
            for (int i = 0; i < requestsPerUnit; i++) {
                if (!budget.isWithinLimit(unit.id())) {
                    // Throttle: wait for minute window reset
                    int waitSec = budget.getSecondsUntilMinuteReset(unit.id());
                    if (waitSec <= 0) waitSec = 60; // fallback: wait a full minute
                    long waitMs = waitSec * 1000L;
                    simulatedWaitMs += waitMs;
                    throttleCount++;

                    RateLimit limit = budget.getLimit(unit.id());
                    String msg = String.format(
                            "Throttling: unit=%s, used=%d/min, limit=%d/min — waiting %ds",
                            unit.id(), budget.getMinuteUsed(unit.id()),
                            limit.requestsPerMinute(), waitSec);
                    log.add(msg);

                    // Simulate the passage of time (no real sleep)
                    time += waitMs;
                }

                // Now make the request
                budget.recordRequest(unit.id());
                boolean withinLimit = true; // PoliteAgent always stays within limits
                String msg = String.format("Request: unit=%s, access=%s, request #%d",
                        unit.id(), unit.access(), i + 1);
                auditLog.record(unit.id(), NAME, time, withinLimit, msg);
                log.add(msg);
                time += 100; // simulate 100ms per request
            }
        }
        return time;
    }

    public List<String> log() {
        return List.copyOf(log);
    }

    public int throttleCount() {
        return throttleCount;
    }

    public long simulatedWaitMs() {
        return simulatedWaitMs;
    }
}
