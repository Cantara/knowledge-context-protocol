package no.cantara.kcp.simulator.legal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for Scenario 2: Legal Document Review (3-Hop Delegation) Simulator.
 */
public final class LegalSimulatorMain {

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

        Path agentCard = resolve(agentCardPath, "agent-card.json");
        Path manifest = resolve(manifestPath, "knowledge.yaml");

        if (!Files.exists(agentCard)) {
            System.err.println("Agent card not found: " + agentCard.toAbsolutePath());
            System.exit(1);
        }
        if (!Files.exists(manifest)) {
            System.err.println("KCP manifest not found: " + manifest.toAbsolutePath());
            System.exit(1);
        }

        ConsoleLog log = new ConsoleLog();
        CaseOrchestratorAgent orchestrator = new CaseOrchestratorAgent(log, autoApprove);
        orchestrator.run(agentCard, manifest);
    }

    private static Path resolve(String explicit, String filename) {
        if (explicit != null) return Path.of(explicit);
        Path relative = Path.of("../" + filename);
        if (Files.exists(relative)) return relative;
        Path sibling = Path.of(filename);
        if (Files.exists(sibling)) return sibling;
        return relative;
    }

    private static void printUsage() {
        System.out.println("Scenario 2: Legal Document Review (3-Hop Delegation) Simulator");
        System.out.println();
        System.out.println("Usage: java -jar kcp-scenario2-legal-delegation-0.1.0-jar-with-dependencies.jar [OPTIONS]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --auto-approve       Skip human approval prompts (for CI)");
        System.out.println("  --agent-card PATH    Path to agent-card.json");
        System.out.println("  --manifest PATH      Path to knowledge.yaml");
        System.out.println("  --help, -h           Show this help message");
    }
}
