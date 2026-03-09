package no.cantara.kcp.simulator;

import no.cantara.kcp.simulator.agent.PoliteAgent;
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

class PoliteAgentTest {

    private KnowledgeUnit unit(String id, int rpm, int rpd) {
        return new KnowledgeUnit(id, "docs/" + id + ".md", "Test", "public", "public",
                List.of(), new RateLimit(rpm, rpd));
    }

    @Test
    void processesAllUnitsWithinLimits() {
        AtomicLong clock = new AtomicLong(1000000L);
        var limits = Map.of("unit1", new RateLimit(10, 100));
        var budget = new RequestBudget(limits, () -> Instant.ofEpochMilli(clock.get()));
        var audit = new AuditLog();
        var agent = new PoliteAgent(budget, audit);

        KnowledgeUnit u = unit("unit1", 10, 100);
        long endTime = agent.processUnits(List.of(u), 5, clock.get());
        clock.set(endTime);

        assertEquals(5, audit.size());
        assertEquals(0, audit.violationCount());
        assertEquals(0, agent.throttleCount());
    }

    @Test
    void throttlesWhenLimitReached() {
        AtomicLong clock = new AtomicLong(1000000L);
        var limits = Map.of("unit1", new RateLimit(3, 100));
        var budget = new RequestBudget(limits, () -> Instant.ofEpochMilli(clock.get()));
        var audit = new AuditLog();
        var agent = new PoliteAgent(budget, audit);

        KnowledgeUnit u = unit("unit1", 3, 100);
        // Request 6 times — should throttle after 3
        long endTime = agent.processUnits(List.of(u), 6, clock.get());
        clock.set(endTime);

        assertEquals(6, audit.size(), "Should complete all 6 requests");
        assertEquals(0, audit.violationCount(), "PoliteAgent never violates");
        assertTrue(agent.throttleCount() > 0, "Should have throttled at least once");
        assertTrue(agent.simulatedWaitMs() > 0, "Should have simulated wait time");
    }

    @Test
    void logsThrottleMessages() {
        AtomicLong clock = new AtomicLong(1000000L);
        var limits = Map.of("unit1", new RateLimit(2, 100));
        var budget = new RequestBudget(limits, () -> Instant.ofEpochMilli(clock.get()));
        var audit = new AuditLog();
        var agent = new PoliteAgent(budget, audit);

        KnowledgeUnit u = unit("unit1", 2, 100);
        long endTime = agent.processUnits(List.of(u), 4, clock.get());
        clock.set(endTime);

        long throttleLogCount = agent.log().stream()
                .filter(l -> l.startsWith("Throttling:"))
                .count();
        assertTrue(throttleLogCount > 0, "Should log throttle events");
    }

    @Test
    void processesMultipleUnits() {
        AtomicLong clock = new AtomicLong(1000000L);
        var limits = Map.of(
                "a", new RateLimit(10, 100),
                "b", new RateLimit(10, 100));
        var budget = new RequestBudget(limits, () -> Instant.ofEpochMilli(clock.get()));
        var audit = new AuditLog();
        var agent = new PoliteAgent(budget, audit);

        List<KnowledgeUnit> units = List.of(unit("a", 10, 100), unit("b", 10, 100));
        long endTime = agent.processUnits(units, 3, clock.get());
        clock.set(endTime);

        assertEquals(6, audit.size()); // 3 per unit
        assertEquals(0, agent.throttleCount());
    }

    @Test
    void zeroViolationsAlways() {
        AtomicLong clock = new AtomicLong(1000000L);
        var limits = Map.of("unit1", new RateLimit(1, 100));
        var budget = new RequestBudget(limits, () -> Instant.ofEpochMilli(clock.get()));
        var audit = new AuditLog();
        var agent = new PoliteAgent(budget, audit);

        KnowledgeUnit u = unit("unit1", 1, 100);
        long endTime = agent.processUnits(List.of(u), 10, clock.get());
        clock.set(endTime);

        assertEquals(0, audit.violationCount(), "PoliteAgent must never violate");
        assertEquals(10, audit.size(), "All requests eventually complete");
    }
}
