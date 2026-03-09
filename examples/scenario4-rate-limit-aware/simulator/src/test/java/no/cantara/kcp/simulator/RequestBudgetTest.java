package no.cantara.kcp.simulator;

import no.cantara.kcp.simulator.model.RateLimit;
import no.cantara.kcp.simulator.model.RequestBudget;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RequestBudgetTest {

    private AtomicLong time(long epochMs) {
        return new AtomicLong(epochMs);
    }

    @Test
    void withinLimit_noRequestsMade() {
        var budget = new RequestBudget(
                Map.of("unit1", new RateLimit(10, 100)),
                Instant::now);
        assertTrue(budget.isWithinLimit("unit1"));
    }

    @Test
    void withinLimit_underPerMinuteLimit() {
        AtomicLong clock = time(1000000L);
        var budget = new RequestBudget(
                Map.of("unit1", new RateLimit(3, 100)),
                () -> Instant.ofEpochMilli(clock.get()));

        budget.recordRequest("unit1");
        budget.recordRequest("unit1");
        assertTrue(budget.isWithinLimit("unit1"), "2 of 3 used, should be within limit");
        assertEquals(2, budget.getMinuteUsed("unit1"));
    }

    @Test
    void atLimit_exactlyAtPerMinuteLimit() {
        AtomicLong clock = time(1000000L);
        var budget = new RequestBudget(
                Map.of("unit1", new RateLimit(3, 100)),
                () -> Instant.ofEpochMilli(clock.get()));

        budget.recordRequest("unit1");
        budget.recordRequest("unit1");
        budget.recordRequest("unit1");
        assertFalse(budget.isWithinLimit("unit1"), "3 of 3 used, should be at limit");
    }

    @Test
    void overLimit_perMinuteExceeded() {
        AtomicLong clock = time(1000000L);
        var budget = new RequestBudget(
                Map.of("unit1", new RateLimit(2, 100)),
                () -> Instant.ofEpochMilli(clock.get()));

        budget.recordRequest("unit1");
        budget.recordRequest("unit1");
        assertFalse(budget.isWithinLimit("unit1"));
        // Record anyway (GreedyAgent does this)
        budget.recordRequest("unit1");
        assertEquals(3, budget.getMinuteUsed("unit1"));
    }

    @Test
    void resetAfterMinuteWindow() {
        AtomicLong clock = time(1000000L);
        var budget = new RequestBudget(
                Map.of("unit1", new RateLimit(2, 100)),
                () -> Instant.ofEpochMilli(clock.get()));

        budget.recordRequest("unit1");
        budget.recordRequest("unit1");
        assertFalse(budget.isWithinLimit("unit1"), "At limit");

        // Advance past the minute window
        clock.addAndGet(61_000L);
        assertTrue(budget.isWithinLimit("unit1"), "Should reset after minute window");
        assertEquals(0, budget.getMinuteUsed("unit1"));
    }

    @Test
    void perDayLimit_respected() {
        AtomicLong clock = time(1000000L);
        var budget = new RequestBudget(
                Map.of("unit1", new RateLimit(100, 3)),
                () -> Instant.ofEpochMilli(clock.get()));

        budget.recordRequest("unit1");
        budget.recordRequest("unit1");
        budget.recordRequest("unit1");
        assertFalse(budget.isWithinLimit("unit1"), "Day limit reached");
        assertEquals(3, budget.getDayUsed("unit1"));
    }

    @Test
    void unlimitedUnit_alwaysWithinLimit() {
        var budget = new RequestBudget(Map.of(), Instant::now);
        assertTrue(budget.isWithinLimit("unknown-unit"));
    }

    @Test
    void secondsUntilReset_withinWindow() {
        AtomicLong clock = time(1000000L);
        var budget = new RequestBudget(
                Map.of("unit1", new RateLimit(1, 100)),
                () -> Instant.ofEpochMilli(clock.get()));

        budget.recordRequest("unit1");
        // Advance 30 seconds
        clock.addAndGet(30_000L);
        int waitSec = budget.getSecondsUntilMinuteReset("unit1");
        assertTrue(waitSec > 0 && waitSec <= 30,
                "Should wait 30s, got: " + waitSec);
    }

    @Test
    void secondsUntilReset_noLimit() {
        var budget = new RequestBudget(Map.of(), Instant::now);
        assertEquals(0, budget.getSecondsUntilMinuteReset("unit1"));
    }

    @Test
    void independentUnitsTrackedSeparately() {
        AtomicLong clock = time(1000000L);
        var budget = new RequestBudget(
                Map.of("a", new RateLimit(2, 100), "b", new RateLimit(5, 100)),
                () -> Instant.ofEpochMilli(clock.get()));

        budget.recordRequest("a");
        budget.recordRequest("a");
        budget.recordRequest("b");

        assertFalse(budget.isWithinLimit("a"), "Unit a at limit");
        assertTrue(budget.isWithinLimit("b"), "Unit b still has budget");
    }
}
