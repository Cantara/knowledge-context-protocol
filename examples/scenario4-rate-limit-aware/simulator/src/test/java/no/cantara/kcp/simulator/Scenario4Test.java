package no.cantara.kcp.simulator;

import no.cantara.kcp.simulator.agent.GreedyAgent;
import no.cantara.kcp.simulator.agent.PoliteAgent;
import no.cantara.kcp.simulator.audit.AuditLog;
import no.cantara.kcp.simulator.model.KnowledgeUnit;
import no.cantara.kcp.simulator.model.RateLimit;
import no.cantara.kcp.simulator.model.RequestBudget;
import no.cantara.kcp.simulator.parser.ManifestParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test: runs both agents against the same manifest and
 * verifies that the PoliteAgent has 0 violations while the GreedyAgent has violations.
 */
class Scenario4Test {

    private static final String MANIFEST_YAML = """
            kcp_version: "0.9"
            project: test-scenario4
            version: "1.0.0"
            rate_limits:
              default:
                requests_per_minute: 30
                requests_per_day: 1000
            units:
              - id: public-docs
                path: docs/public.md
                intent: "Public documentation"
                access: public
                rate_limits:
                  default:
                    requests_per_minute: 10
                    requests_per_day: 500
              - id: api-reference
                path: docs/api.md
                intent: "API reference"
                access: authenticated
                depends_on: [public-docs]
                rate_limits:
                  default:
                    requests_per_minute: 5
                    requests_per_day: 200
              - id: compliance-data
                path: docs/compliance.md
                intent: "Compliance data"
                access: restricted
                depends_on: [api-reference]
                rate_limits:
                  default:
                    requests_per_minute: 2
                    requests_per_day: 20
            """;

    private List<KnowledgeUnit> parseManifest() {
        return ManifestParser.parse(
                new ByteArrayInputStream(MANIFEST_YAML.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void politeAgentHasZeroViolations() {
        List<KnowledgeUnit> units = parseManifest();
        Map<String, RateLimit> limits = new HashMap<>();
        for (KnowledgeUnit u : units) limits.put(u.id(), u.rateLimit());

        AtomicLong clock = new AtomicLong(1000000L);
        var budget = new RequestBudget(limits, () -> Instant.ofEpochMilli(clock.get()));
        var audit = new AuditLog();
        var agent = new PoliteAgent(budget, audit);

        long endTime = agent.processUnits(units, 8, clock.get());
        clock.set(endTime);

        assertEquals(0, audit.violationCount(),
                "PoliteAgent must have 0 advisory violations");
        assertEquals(24, audit.size(), "Should complete all 3 * 8 = 24 requests");
    }

    @Test
    void greedyAgentHasViolations() {
        List<KnowledgeUnit> units = parseManifest();
        Map<String, RateLimit> limits = new HashMap<>();
        for (KnowledgeUnit u : units) limits.put(u.id(), u.rateLimit());

        AtomicLong clock = new AtomicLong(1000000L);
        var budget = new RequestBudget(limits, () -> Instant.ofEpochMilli(clock.get()));
        var audit = new AuditLog();
        var agent = new GreedyAgent(budget, audit);

        agent.processUnits(units, 8, clock.get());

        assertTrue(audit.violationCount() > 0,
                "GreedyAgent should have advisory violations");
        assertEquals(24, audit.size(), "Should complete all 3 * 8 = 24 requests");
    }

    @Test
    void complianceDataHasMostViolations() {
        List<KnowledgeUnit> units = parseManifest();
        Map<String, RateLimit> limits = new HashMap<>();
        for (KnowledgeUnit u : units) limits.put(u.id(), u.rateLimit());

        AtomicLong clock = new AtomicLong(1000000L);
        var budget = new RequestBudget(limits, () -> Instant.ofEpochMilli(clock.get()));
        var audit = new AuditLog();
        var agent = new GreedyAgent(budget, audit);

        agent.processUnits(units, 8, clock.get());

        // compliance-data: limit 2/min, 8 requests -> 6 violations
        long compViolations = audit.violationsForUnit("compliance-data").size();
        // public-docs: limit 10/min, 8 requests -> 0 violations
        long pubViolations = audit.violationsForUnit("public-docs").size();

        assertTrue(compViolations > pubViolations,
                "Tighter limits should produce more violations: compliance=" +
                        compViolations + " vs public=" + pubViolations);
    }

    @Test
    void parsedManifestHasCorrectUnitCount() {
        List<KnowledgeUnit> units = parseManifest();
        assertEquals(3, units.size());
    }

    @Test
    void parsedManifestHasCorrectRateLimits() {
        List<KnowledgeUnit> units = parseManifest();
        // public-docs: override 10/min
        assertEquals(10, units.get(0).rateLimit().requestsPerMinute());
        // api-reference: override 5/min
        assertEquals(5, units.get(1).rateLimit().requestsPerMinute());
        // compliance-data: override 2/min
        assertEquals(2, units.get(2).rateLimit().requestsPerMinute());
    }

    @Test
    void auditLogTracksAgentNames() {
        List<KnowledgeUnit> units = parseManifest();
        Map<String, RateLimit> limits = new HashMap<>();
        for (KnowledgeUnit u : units) limits.put(u.id(), u.rateLimit());

        // Polite
        AtomicLong clock1 = new AtomicLong(1000000L);
        var budget1 = new RequestBudget(limits, () -> Instant.ofEpochMilli(clock1.get()));
        var audit1 = new AuditLog();
        var polite = new PoliteAgent(budget1, audit1);
        clock1.set(polite.processUnits(units, 2, clock1.get()));

        // Greedy
        AtomicLong clock2 = new AtomicLong(1000000L);
        var budget2 = new RequestBudget(limits, () -> Instant.ofEpochMilli(clock2.get()));
        var audit2 = new AuditLog();
        var greedy = new GreedyAgent(budget2, audit2);
        greedy.processUnits(units, 2, clock2.get());

        assertTrue(audit1.entriesForAgent("PoliteAgent").size() > 0);
        assertTrue(audit2.entriesForAgent("GreedyAgent").size() > 0);
    }
}
