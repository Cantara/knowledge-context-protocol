package no.cantara.kcp.simulator.agent;

import no.cantara.kcp.simulator.audit.AuditLog;
import no.cantara.kcp.simulator.model.KnowledgeUnit;
import no.cantara.kcp.simulator.model.RateLimit;
import no.cantara.kcp.simulator.model.RequestBudget;

import java.util.ArrayList;
import java.util.List;

/**
 * An agent that ignores advisory rate_limits entirely, bursting all requests
 * immediately. Each request that exceeds the advisory limit is logged as an
 * ADVISORY VIOLATION.
 * <p>
 * This agent demonstrates what happens when rate_limits are not respected:
 * the requests succeed (KCP does not enforce), but the audit trail shows
 * every violation.
 */
public final class GreedyAgent {

    public static final String NAME = "GreedyAgent";

    private final RequestBudget budget;
    private final AuditLog auditLog;
    private final List<String> log = new ArrayList<>();
    private int violationCount = 0;

    public GreedyAgent(RequestBudget budget, AuditLog auditLog) {
        this.budget = budget;
        this.auditLog = auditLog;
    }

    /**
     * Process a list of knowledge units, ignoring rate limits.
     * Each unit is requested {@code requestsPerUnit} times immediately.
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
                boolean withinLimit = budget.isWithinLimit(unit.id());
                budget.recordRequest(unit.id());

                if (!withinLimit) {
                    violationCount++;
                    RateLimit limit = budget.getLimit(unit.id());
                    String msg = String.format(
                            "ADVISORY VIOLATION: unit=%s, used=%d/min, limit=%d/min",
                            unit.id(), budget.getMinuteUsed(unit.id()),
                            limit.requestsPerMinute() != null ? limit.requestsPerMinute() : -1);
                    log.add(msg);
                    auditLog.record(unit.id(), NAME, time, false, msg);
                } else {
                    String msg = String.format("Request: unit=%s, access=%s, request #%d",
                            unit.id(), unit.access(), i + 1);
                    log.add(msg);
                    auditLog.record(unit.id(), NAME, time, true, msg);
                }
                time += 10; // bursts: 10ms per request (no throttling)
            }
        }
        return time;
    }

    public List<String> log() {
        return List.copyOf(log);
    }

    public int violationCount() {
        return violationCount;
    }
}
