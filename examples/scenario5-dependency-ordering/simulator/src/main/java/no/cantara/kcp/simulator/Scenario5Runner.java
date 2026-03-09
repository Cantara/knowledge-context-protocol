package no.cantara.kcp.simulator;

import no.cantara.kcp.simulator.agent.KnowledgeIngestionAgent;
import no.cantara.kcp.simulator.audit.IngestionLog;
import no.cantara.kcp.simulator.graph.DependencyGraph;
import no.cantara.kcp.simulator.graph.TopologicalSorter;
import no.cantara.kcp.simulator.model.KnowledgeUnit;
import no.cantara.kcp.simulator.model.LoadResult;
import no.cantara.kcp.simulator.model.Relationship;
import no.cantara.kcp.simulator.parser.ManifestParser;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Entry point for Scenario 5: Dependency Ordering Simulator.
 * <p>
 * Parses a KCP manifest, builds a dependency graph, topologically sorts it,
 * and loads knowledge units in safe order with relationship-aware logging.
 */
public final class Scenario5Runner {

    private final PrintStream out;

    public Scenario5Runner(PrintStream out) {
        this.out = out;
    }

    public Scenario5Runner() {
        this(System.out);
    }

    public List<LoadResult> run(Path manifestPath) throws IOException {
        out.println("=== SCENARIO 5: Dependency Ordering Simulator ===");
        out.println("Manifest: " + manifestPath);
        out.println();

        ManifestParser.ParsedManifest parsed = ManifestParser.parse(manifestPath);
        List<KnowledgeUnit> units = parsed.units();
        List<Relationship> relationships = parsed.relationships();

        // Print units
        out.println("--- Discovered Units (" + units.size() + ") ---");
        for (KnowledgeUnit unit : units) {
            out.printf("  %-25s access=%-14s deps=%s%s%n",
                    unit.id(), unit.access(),
                    unit.dependsOn().isEmpty() ? "none" : String.join(", ", unit.dependsOn()),
                    unit.supersedes() != null ? "  supersedes=" + unit.supersedes() : "");
        }
        out.println();

        // Print relationships
        out.println("--- Relationships (" + relationships.size() + ") ---");
        for (Relationship rel : relationships) {
            out.printf("  %s -[%s]-> %s%n", rel.from(), rel.type(), rel.to());
        }
        out.println();

        // Build graph and sort
        DependencyGraph graph = DependencyGraph.build(units, relationships);
        List<String> loadOrder = TopologicalSorter.sort(graph);

        out.println("--- Topological Load Order ---");
        for (int i = 0; i < loadOrder.size(); i++) {
            out.printf("  %d. %s%n", i + 1, loadOrder.get(i));
        }
        out.println();

        // Ingest
        out.println("--- Ingestion ---");
        IngestionLog ingestionLog = new IngestionLog(out);
        KnowledgeIngestionAgent agent = new KnowledgeIngestionAgent(ingestionLog);
        List<LoadResult> results = agent.ingest(units, relationships);

        out.println();
        out.println("=== SUMMARY ===");
        long loaded = results.stream().filter(r -> r.status() == LoadResult.Status.LOADED).count();
        long skipped = results.stream().filter(r -> r.status() == LoadResult.Status.SKIPPED).count();
        long blocked = results.stream().filter(r -> r.status() == LoadResult.Status.BLOCKED).count();
        out.printf("  Loaded:  %d%n", loaded);
        out.printf("  Skipped: %d%n", skipped);
        out.printf("  Blocked: %d%n", blocked);
        out.printf("  Warnings: %d%n", ingestionLog.warnCount());

        return results;
    }

    public static void main(String[] args) throws IOException {
        String manifestPath = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest" -> {
                    if (i + 1 < args.length) manifestPath = args[++i];
                }
                case "--help", "-h" -> {
                    System.out.println("Scenario 5: Dependency Ordering Simulator");
                    System.out.println("  --manifest PATH   Path to knowledge.yaml");
                    return;
                }
            }
        }

        Path path = resolveManifest(manifestPath);
        new Scenario5Runner().run(path);
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
