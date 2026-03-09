package no.cantara.kcp.sim.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for the A2A + KCP HTTP Simulation.
 *
 * <p>Starts two embedded HTTP servers (Auth Server on port 9000, Research Agent
 * on port 9001), then runs the Orchestrator client through the full A2A + KCP
 * flow over real HTTP. No external dependencies, no Docker, no cloud — just
 * the JDK's built-in {@code HttpServer} and {@code HttpClient}.
 *
 * <h3>Usage</h3>
 * <pre>
 *   java -jar kcp-a2a-simulator-http-0.1.0-jar-with-dependencies.jar [OPTIONS]
 *
 *   Options:
 *     --manifest PATH    Path to knowledge.yaml (default: ../knowledge.yaml)
 *     --auth-port PORT   Auth Server port (default: 9000)
 *     --agent-port PORT  Research Agent port (default: 9001)
 *     --help, -h         Show this help message
 * </pre>
 *
 * <h3>What happens</h3>
 * <ol>
 *   <li>Auth Server starts on port 9000 (OAuth2 token + introspect)</li>
 *   <li>Research Agent starts on port 9001 (A2A card + KCP manifest + tasks)</li>
 *   <li>Orchestrator discovers the agent via HTTP GET</li>
 *   <li>Orchestrator gets an OAuth2 token via HTTP POST</li>
 *   <li>Orchestrator sends task requests, receives KCP policy decisions</li>
 *   <li>Both servers shut down cleanly</li>
 * </ol>
 */
public final class SimulatorHttpMain {

    public static void main(String[] args) {
        int authPort = 9000;
        int agentPort = 9001;
        String manifestPathStr = null;

        // Parse command-line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest" -> {
                    if (i + 1 < args.length) manifestPathStr = args[++i];
                }
                case "--auth-port" -> {
                    if (i + 1 < args.length) authPort = Integer.parseInt(args[++i]);
                }
                case "--agent-port" -> {
                    if (i + 1 < args.length) agentPort = Integer.parseInt(args[++i]);
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

        // Resolve the KCP manifest path
        Path manifestPath = resolveManifest(manifestPathStr);
        if (!Files.exists(manifestPath)) {
            System.err.println("KCP manifest not found: " + manifestPath.toAbsolutePath());
            System.err.println("Use --manifest PATH to specify the location.");
            System.exit(1);
        }

        // Run the simulation
        AuthServer authServer = new AuthServer(authPort);
        ResearchAgentServer agentServer = new ResearchAgentServer(agentPort, authPort, manifestPath);

        try {
            // Start servers
            authServer.start();
            agentServer.start();
            System.out.println();

            // Run the orchestrator
            OrchestratorClient orchestrator = new OrchestratorClient(
                    "http://localhost:" + agentPort,
                    "http://localhost:" + authPort
            );
            orchestrator.run();

        } catch (Exception e) {
            System.err.println("Simulation failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            // Always shut down servers
            agentServer.stop();
            authServer.stop();
        }
    }

    private static Path resolveManifest(String explicit) {
        if (explicit != null) return Path.of(explicit);
        // Try common locations relative to CWD
        Path relative = Path.of("../knowledge.yaml");
        if (Files.exists(relative)) return relative;
        Path sibling = Path.of("knowledge.yaml");
        if (Files.exists(sibling)) return sibling;
        // Try the absolute path in the repo
        Path repoPath = Path.of("/src/cantara/knowledge-context-protocol/examples/a2a-agent-card/knowledge.yaml");
        if (Files.exists(repoPath)) return repoPath;
        return relative; // default, will fail with clear message
    }

    private static void printUsage() {
        System.out.println("A2A + KCP HTTP Simulation");
        System.out.println();
        System.out.println("Usage: java -jar kcp-a2a-simulator-http-0.1.0-jar-with-dependencies.jar [OPTIONS]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --manifest PATH     Path to knowledge.yaml (default: ../knowledge.yaml)");
        System.out.println("  --auth-port PORT    Auth Server port (default: 9000)");
        System.out.println("  --agent-port PORT   Research Agent port (default: 9001)");
        System.out.println("  --help, -h          Show this help message");
        System.out.println();
        System.out.println("Servers:");
        System.out.println("  Auth Server     localhost:9000  OAuth2 token + introspect");
        System.out.println("  Research Agent  localhost:9001  A2A agent card + KCP manifest + tasks");
    }
}
