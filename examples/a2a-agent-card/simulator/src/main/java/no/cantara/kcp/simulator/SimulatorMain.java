package no.cantara.kcp.simulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Entry point for the A2A + KCP Composition Simulator.
 *
 * <p>Usage:
 * <pre>
 *   java -jar kcp-a2a-simulator-0.1.0-jar-with-dependencies.jar [--auto-approve] [--agent-card PATH] [--manifest PATH]
 * </pre>
 *
 * <p>When paths are not specified, defaults to {@code ../agent-card.json} and {@code ../knowledge.yaml}
 * relative to the current working directory (assumes running from the simulator/ directory).
 */
public final class SimulatorMain {

    public static void main(String[] args) throws IOException {
        boolean autoApprove = false;
        String agentCardPath = null;
        String manifestPath = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--auto-approve" -> autoApprove = true;
                case "--agent-card" -> {
                    if (i + 1 < args.length) agentCardPath = args[++i];
                }
                case "--manifest" -> {
                    if (i + 1 < args.length) manifestPath = args[++i];
                }
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    printUsage();
                    System.exit(1);
                }
            }
        }

        // Resolve paths with sensible defaults
        Path agentCard = resolveAgentCard(agentCardPath);
        Path manifest = resolveManifest(manifestPath);

        // Validate files exist
        if (!Files.exists(agentCard)) {
            System.err.println("Agent card not found: " + agentCard.toAbsolutePath());
            System.err.println("Use --agent-card PATH to specify the location.");
            System.exit(1);
        }
        if (!Files.exists(manifest)) {
            System.err.println("KCP manifest not found: " + manifest.toAbsolutePath());
            System.err.println("Use --manifest PATH to specify the location.");
            System.exit(1);
        }

        ConsoleLog log = new ConsoleLog();
        OrchestratorAgent orchestrator = new OrchestratorAgent(log, autoApprove);
        orchestrator.run(agentCard, manifest);
    }

    private static Path resolveAgentCard(String explicit) {
        if (explicit != null) return Path.of(explicit);
        // Try common locations
        Path relative = Path.of("../agent-card.json");
        if (Files.exists(relative)) return relative;
        Path sibling = Path.of("agent-card.json");
        if (Files.exists(sibling)) return sibling;
        return relative; // default, will fail with clear message
    }

    private static Path resolveManifest(String explicit) {
        if (explicit != null) return Path.of(explicit);
        Path relative = Path.of("../knowledge.yaml");
        if (Files.exists(relative)) return relative;
        Path sibling = Path.of("knowledge.yaml");
        if (Files.exists(sibling)) return sibling;
        return relative;
    }

    private static void printUsage() {
        System.out.println("A2A + KCP Composition Simulator");
        System.out.println();
        System.out.println("Usage: java -jar kcp-a2a-simulator-0.1.0-jar-with-dependencies.jar [OPTIONS]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --auto-approve       Skip human approval prompts (for CI)");
        System.out.println("  --agent-card PATH    Path to agent-card.json (default: ../agent-card.json)");
        System.out.println("  --manifest PATH      Path to knowledge.yaml (default: ../knowledge.yaml)");
        System.out.println("  --help, -h           Show this help message");
    }
}
