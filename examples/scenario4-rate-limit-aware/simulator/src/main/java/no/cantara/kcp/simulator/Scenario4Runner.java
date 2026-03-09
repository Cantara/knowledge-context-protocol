package no.cantara.kcp.simulator;

import no.cantara.kcp.simulator.agent.GreedyAgent;
import no.cantara.kcp.simulator.agent.PoliteAgent;
import no.cantara.kcp.simulator.audit.AuditLog;
import no.cantara.kcp.simulator.model.KnowledgeUnit;
import no.cantara.kcp.simulator.model.RateLimit;
import no.cantara.kcp.simulator.model.RequestBudget;
import no.cantara.kcp.simulator.parser.ManifestParser;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entry point for Scenario 4: Rate-Limit-Aware Agent Simulator.
 * <p>
 * Runs two agents (PoliteAgent and GreedyAgent) against the same KCP manifest
 * and compares their behaviour regarding advisory rate_limits.
 */
public final class Scenario4Runner {

    private final PrintStream out;

    public Scenario4Runner(PrintStream out) {
        this.out = out;
    }

    public Scenario4Runner() {
        this(System.out);
    }

    /**
     * Run the full simulation.
     *
     * @param manifestPath path to knowledge.yaml
     * @param requestsPerUnit how many times each agent requests each unit
     * @return summary of both agents' results
     */
    public SimulationResult run(Path manifestPath, int requestsPerUnit) throws IOException {
        out.println("=== SCENARIO 4: Rate-Limit-Aware Agent Simulator ===");
        out.println("Manifest: " + manifestPath);
        out.println("Requests per unit: " + requestsPerUnit);
        out.println();

        // Parse manifest
        List<KnowledgeUnit> units = ManifestParser.parse(manifestPath);

        out.println("--- Discovered Units ---");
        for (KnowledgeUnit unit : units) {
            RateLimit rl = unit.rateLimit();
            out.printf("  %-20s access=%-14s rate_limit=%s/min, %s/day%n",
                    unit.id(), unit.access(),
                    rl.requestsPerMinute() != null ? rl.requestsPerMinute() : "unlimited",
                    rl.requestsPerDay() != null ? rl.requestsPerDay() : "unlimited");
        }
        out.println();

        Map<String, RateLimit> limitMap = new HashMap<>();
        for (KnowledgeUnit unit : units) {
            limitMap.put(unit.id(), unit.rateLimit());
        }

        // --- PoliteAgent ---
        out.println("--- PoliteAgent (self-throttling) ---");
        AuditLog politeAudit = new AuditLog();
        AtomicLong politeTime = new AtomicLong(Instant.now().toEpochMilli());
        RequestBudget politeBudget = new RequestBudget(limitMap,
                () -> Instant.ofEpochMilli(politeTime.get()));
        PoliteAgent polite = new PoliteAgent(politeBudget, politeAudit);
        politeTime.set(polite.processUnits(units, requestsPerUnit, politeTime.get()));

        for (String line : polite.log()) {
            out.println("  " + line);
        }
        out.println();

        // --- GreedyAgent ---
        out.println("--- GreedyAgent (burst, ignores limits) ---");
        AuditLog greedyAudit = new AuditLog();
        AtomicLong greedyTime = new AtomicLong(Instant.now().toEpochMilli());
        RequestBudget greedyBudget = new RequestBudget(limitMap,
                () -> Instant.ofEpochMilli(greedyTime.get()));
        GreedyAgent greedy = new GreedyAgent(greedyBudget, greedyAudit);
        greedyTime.set(greedy.processUnits(units, requestsPerUnit, greedyTime.get()));

        for (String line : greedy.log()) {
            out.println("  " + line);
        }
        out.println();

        // --- Summary ---
        out.println("=== SUMMARY ===");
        out.printf("  PoliteAgent:  %d requests, %d throttle pauses, %d advisory violations%n",
                politeAudit.size(), polite.throttleCount(), politeAudit.violationCount());
        out.printf("  GreedyAgent:  %d requests, 0 throttle pauses, %d advisory violations%n",
                greedyAudit.size(), greedyAudit.violationCount());

        return new SimulationResult(politeAudit, greedyAudit,
                polite.throttleCount(), greedy.violationCount());
    }

    public record SimulationResult(
            AuditLog politeAudit,
            AuditLog greedyAudit,
            int politeThrottles,
            int greedyViolations
    ) {}

    public static void main(String[] args) throws IOException {
        String manifestPath = null;
        int requestsPerUnit = 5;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest" -> {
                    if (i + 1 < args.length) manifestPath = args[++i];
                }
                case "--requests" -> {
                    if (i + 1 < args.length) requestsPerUnit = Integer.parseInt(args[++i]);
                }
                case "--help", "-h" -> {
                    System.out.println("Scenario 4: Rate-Limit-Aware Agent Simulator");
                    System.out.println("  --manifest PATH   Path to knowledge.yaml");
                    System.out.println("  --requests N      Requests per unit per agent (default: 5)");
                    return;
                }
            }
        }

        Path path = resolveManifest(manifestPath);
        new Scenario4Runner().run(path, requestsPerUnit);
    }

    private static Path resolveManifest(String explicit) {
        if (explicit != null) return Path.of(explicit);
        Path relative = Path.of("../knowledge.yaml");
        if (Files.exists(relative)) return relative;
        Path sibling = Path.of("knowledge.yaml");
        if (Files.exists(sibling)) return sibling;
        return relative;
    }
}
