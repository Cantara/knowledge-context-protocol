package no.cantara.kcp.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * CLI entry point for kcp-mcp.
 *
 * <pre>
 * Usage: kcp-mcp [knowledge.yaml] [options]
 *
 * Options:
 *   --agent-only              Only expose units with audience: [agent]
 *   --sub-manifests path ...  Additional manifests to merge
 *   --commands-dir path       Load kcp-commands manifests (enables get_command_syntax tool)
 *   --generate-instructions   Write copilot-instructions.md to stdout and exit
 *   --audience value          Filter units by audience (with --generate-instructions)
 *   --no-warnings             Suppress KCP validation warnings
 *   --help, -h                Show help
 * </pre>
 */
public class KcpMcpCli {

    public static void main(String[] args) {
        Path         manifestPath          = Path.of("knowledge.yaml");
        boolean      agentOnly             = false;
        boolean      warnOnValidation      = true;
        boolean      generateInstructions  = false;
        String       audience              = null;
        Path         commandsDir           = null;
        List<Path>   subManifests          = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--agent-only"             -> agentOnly             = true;
                case "--no-warnings"            -> warnOnValidation      = false;
                case "--generate-instructions"  -> generateInstructions  = true;
                case "--audience"               -> { if (i + 1 < args.length) audience = args[++i]; }
                case "--commands-dir"           -> { if (i + 1 < args.length) commandsDir = Path.of(args[++i]); }
                case "--sub-manifests"          -> {
                    // Consume subsequent non-flag arguments as sub-manifest paths
                    while (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                        subManifests.add(Path.of(args[++i]));
                    }
                }
                case "--help", "-h"             -> { printUsage(); System.exit(0); }
                default -> {
                    if (!args[i].startsWith("-")) {
                        manifestPath = Path.of(args[i]);
                    }
                }
            }
        }

        if (!manifestPath.toFile().exists()) {
            System.err.println("[kcp-mcp] Error: knowledge.yaml not found at " + manifestPath);
            System.exit(1);
        }

        // ── Generate instructions mode ────────────────────────────────────────
        if (generateInstructions) {
            try {
                String md = KcpInstructions.generateInstructions(manifestPath, audience);
                System.out.print(md);
                System.exit(0);
            } catch (Exception e) {
                System.err.println("[kcp-mcp] Error: " + e.getMessage());
                System.exit(1);
            }
        }

        // ── Load command manifests if --commands-dir is provided ───────────────
        Map<String, KcpCommands.CommandManifest> commandManifests = null;
        if (commandsDir != null) {
            if (!Files.isDirectory(commandsDir)) {
                System.err.printf("  [kcp-mcp] warning: --commands-dir '%s' does not exist%n", commandsDir);
            } else {
                commandManifests = KcpCommands.loadCommandManifests(commandsDir);
                if (commandManifests.isEmpty()) {
                    System.err.printf("  [kcp-mcp] warning: no command manifests found in %s%n", commandsDir);
                    commandManifests = null;
                }
            }
        }

        // ── Start MCP server ──────────────────────────────────────────────────
        StdioServerTransportProvider transport =
            new StdioServerTransportProvider(McpJsonDefaults.getMapper());

        McpSyncServer server;
        try {
            server = KcpServer.createServer(
                manifestPath, transport, agentOnly, warnOnValidation, subManifests, commandManifests);
        } catch (Exception e) {
            System.err.println("[kcp-mcp] Startup error: " + e.getMessage());
            System.exit(1);
            return;
        }

        // Block the main thread; transport handles I/O on daemon threads.
        // The process exits when stdin is closed (e.g. MCP client disconnects).
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            latch.countDown();
        }));
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void printUsage() {
        System.err.print("""
            Usage: kcp-mcp [path/to/knowledge.yaml] [options]

            Options:
              --agent-only              Only expose units with audience: [agent]
              --sub-manifests path ...  Additional manifests to merge
              --commands-dir <path>     Load kcp-commands manifests (enables get_command_syntax tool)
              --generate-instructions   Write copilot-instructions.md to stdout and exit
              --audience <value>        Filter units by audience (with --generate-instructions)
              --no-warnings             Suppress KCP validation warnings
              --help, -h                Show this help

            Examples:
              kcp-mcp                                           # serve ./knowledge.yaml
              kcp-mcp knowledge.yaml --agent-only               # filter to agent-facing units
              kcp-mcp knowledge.yaml --commands-dir /path/to/kcp-commands/commands
              kcp-mcp --generate-instructions knowledge.yaml > .github/copilot-instructions.md
              kcp-mcp --generate-instructions knowledge.yaml --audience agent

            Start by reading: knowledge://{project-slug}/manifest
            """);
    }
}
