package no.cantara.kcp.simulator;

import no.cantara.kcp.simulator.agent.GreedyAgent;
import no.cantara.kcp.simulator.audit.AuditLog;
import no.cantara.kcp.simulator.model.KnowledgeUnit;
import no.cantara.kcp.simulator.model.RateLimit;
import no.cantara.kcp.simulator.model.RequestBudget;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class GreedyAgentTest {

    private KnowledgeUnit unit(String id, int rpm, int rpd) {
        return new KnowledgeUnit(id, "docs/" + id + ".md", "Test", "public", "public",
                List.of(), new RateLimit(rpm, rpd));
    }

    @Test
    void completesAllRequestsImmediately() {
        AtomicLong clock = new AtomicLong(1000000L);
        var limits = Map.of("unit1", new RateLimit(10, 100));
        var budget = new RequestBudget(limits, () -> Instant.ofEpochMilli(clock.get()));
        var audit = new AuditLog();
        var agent = new GreedyAgent(budget, audit);

        KnowledgeUnit u = unit("unit1", 10, 100);
        agent.processUnits(List.of(u), 5, clock.get());

        assertEquals(5, audit.size());
    }

    @Test
    void logsViolationsWhenExceedingLimit() {
        AtomicLong clock = new AtomicLong(1000000L);
        var limits = Map.of("unit1", new RateLimit(3, 100));
        var budget = new RequestBudget(limits, () -> Instant.ofEpochMilli(clock.get()));
        var audit = new AuditLog();
        var agent = new GreedyAgent(budget, audit);

        KnowledgeUnit u = unit("unit1", 3, 100);
        agent.processUnits(List.of(u), 6, clock.get());

        assertEquals(6, audit.size(), "All requests still go through");
        assertEquals(3, audit.violationCount(), "3 of 6 are violations (exceeding 3/min)");
        assertEquals(3, agent.violationCount());
    }

    @Test
    void violationMessagesContainAdvisoryViolation() {
        AtomicLong clock = new AtomicLong(1000000L);
        var limits = Map.of("unit1", new RateLimit(1, 100));
        var budget = new RequestBudget(limits, () -> Instant.ofEpochMilli(clock.get()));
        var audit = new AuditLog();
        var agent = new GreedyAgent(budget, audit);

        KnowledgeUnit u = unit("unit1", 1, 100);
        agent.processUnits(List.of(u), 3, clock.get());

        long violationMsgCount = agent.log().stream()
                .filter(l -> l.contains("ADVISORY VIOLATION"))
                .count();
        assertEquals(2, violationMsgCount, "2 of 3 requests exceed 1/min limit");
    }

    @Test
    void noViolationsWhenWithinLimits() {
        AtomicLong clock = new AtomicLong(1000000L);
        var limits = Map.of("unit1", new RateLimit(10, 100));
        var budget = new RequestBudget(limits, () -> Instant.ofEpochMilli(clock.get()));
        var audit = new AuditLog();
        var agent = new GreedyAgent(budget, audit);

        KnowledgeUnit u = unit("unit1", 10, 100);
        agent.processUnits(List.of(u), 5, clock.get());

        assertEquals(0, audit.violationCount());
        assertEquals(0, agent.violationCount());
    }

    @Test
    void multipleUnitsTrackedSeparately() {
        AtomicLong clock = new AtomicLong(1000000L);
        var limits = Map.of(
                "a", new RateLimit(2, 100),
                "b", new RateLimit(5, 100));
        var budget = new RequestBudget(limits, () -> Instant.ofEpochMilli(clock.get()));
        var audit = new AuditLog();
        var agent = new GreedyAgent(budget, audit);

        List<KnowledgeUnit> units = List.of(unit("a", 2, 100), unit("b", 5, 100));
        agent.processUnits(units, 4, clock.get());

        // a: 4 requests, limit 2/min -> 2 violations
        assertEquals(2, audit.violationsForUnit("a").size());
        // b: 4 requests, limit 5/min -> 0 violations
        assertEquals(0, audit.violationsForUnit("b").size());
    }

    @Test
    void fasterThanPoliteAgent() {
        // GreedyAgent uses 10ms per request vs PoliteAgent's potential throttle waits
        AtomicLong clock = new AtomicLong(1000000L);
        var limits = Map.of("unit1", new RateLimit(2, 100));
        var budget = new RequestBudget(limits, () -> Instant.ofEpochMilli(clock.get()));
        var audit = new AuditLog();
        var agent = new GreedyAgent(budget, audit);

        KnowledgeUnit u = unit("unit1", 2, 100);
        long endTime = agent.processUnits(List.of(u), 10, clock.get());

        // 10 requests * 10ms = 100ms total
        long elapsed = endTime - clock.get();
        assertTrue(elapsed <= 200, "GreedyAgent should be fast: " + elapsed + "ms");
    }
}
