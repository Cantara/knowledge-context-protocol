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
 * MCP Server Options:
 *   --agent-only              Only expose units with audience: [agent]
 *   --sub-manifests path ...  Additional manifests to merge
 *   --commands-dir path       Load kcp-commands manifests (enables get_command_syntax tool)
 *   --no-warnings             Suppress KCP validation warnings
 *
 * Static Generation Options:
 *   --generate-instructions   Write copilot-instructions.md to stdout and exit
 *   --generate-agent          Generate .agent.md to stdout and exit
 *   --generate-all            Generate all three tiers to .github/ and exit
 *   --audience value          Filter units by audience
 *   --output-format fmt       Output format: full (default), compact, agent
 *   --output-dir path         Write split instruction files to this directory
 *   --split-by strategy       Split strategy: directory (default), scope, unit, none
 *   --max-chars n             Max chars for agent file (default: 0 = no limit)
 *   --help, -h                Show help
 * </pre>
 */
public class KcpMcpCli {

    public static void main(String[] args) {
        Path         manifestPath          = Path.of("knowledge.yaml");
        boolean      agentOnly             = false;
        boolean      warnOnValidation      = true;
        boolean      generateInstructions  = false;
        boolean      generateAgent         = false;
        boolean      generateAll           = false;
        String       audience              = null;
        String       outputFormat          = "full";
        String       outputDir             = null;
        String       splitBy               = "directory";
        int          maxChars              = 0;
        Path         commandsDir           = null;
        List<Path>   subManifests          = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--agent-only"             -> agentOnly             = true;
                case "--no-warnings"            -> warnOnValidation      = false;
                case "--generate-instructions"  -> generateInstructions  = true;
                case "--generate-agent"         -> generateAgent         = true;
                case "--generate-all"           -> generateAll           = true;
                case "--audience"               -> { if (i + 1 < args.length) audience = args[++i]; }
                case "--output-format"          -> { if (i + 1 < args.length) outputFormat = args[++i]; }
                case "--output-dir"             -> { if (i + 1 < args.length) outputDir = args[++i]; }
                case "--split-by"               -> { if (i + 1 < args.length) splitBy = args[++i]; }
                case "--max-chars"              -> { if (i + 1 < args.length) maxChars = Integer.parseInt(args[++i]); }
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

        // ── Generate-all mode ───────────────────────────────────────────────────
        if (generateAll) {
            try {
                Path ghDir = Path.of(".github").toAbsolutePath();
                Path instrDir = ghDir.resolve("instructions");
                Path agentsDir = ghDir.resolve("agents");
                Files.createDirectories(instrDir);
                Files.createDirectories(agentsDir);

                // Tier 1: .github/copilot-instructions.md (compact index)
                String compactContent = KcpInstructions.generateInstructions(
                    manifestPath, audience, KcpInstructions.Format.COMPACT);
                Files.writeString(ghDir.resolve("copilot-instructions.md"), compactContent);
                System.err.println("  wrote " + ghDir.resolve("copilot-instructions.md"));

                // Tier 2: .github/instructions/*.instructions.md (split by directory)
                KcpSplitInstructions.generateSplitInstructions(
                    manifestPath, instrDir, KcpSplitInstructions.SplitBy.DIRECTORY, audience);
                System.err.println("  wrote split instructions to " + instrDir + "/");

                // Tier 3: .github/agents/kcp-expert.agent.md
                String agentContent = KcpInstructions.generateAgentFile(manifestPath, audience, 0);
                Files.writeString(agentsDir.resolve("kcp-expert.agent.md"), agentContent);
                System.err.println("  wrote " + agentsDir.resolve("kcp-expert.agent.md"));

                System.exit(0);
            } catch (Exception e) {
                System.err.println("[kcp-mcp] Error: " + e.getMessage());
                System.exit(1);
            }
        }

        // ── Generate-agent mode ─────────────────────────────────────────────────
        if (generateAgent) {
            try {
                String content = KcpInstructions.generateAgentFile(manifestPath, audience, maxChars);
                System.out.print(content);
                System.exit(0);
            } catch (Exception e) {
                System.err.println("[kcp-mcp] Error: " + e.getMessage());
                System.exit(1);
            }
        }

        // ── Generate instructions mode ──────────────────────────────────────────
        if (generateInstructions) {
            try {
                if (outputDir != null) {
                    // Split mode: write individual instruction files
                    KcpSplitInstructions.SplitBy strategy = parseSplitBy(splitBy);
                    Path outPath = Path.of(outputDir);
                    Files.createDirectories(outPath);
                    KcpSplitInstructions.generateSplitInstructions(
                        manifestPath, outPath, strategy, audience);
                    System.err.println("  wrote split instructions to " + outputDir + "/");
                } else {
                    // Single file mode: write to stdout
                    KcpInstructions.Format format = parseFormat(outputFormat);
                    String md = KcpInstructions.generateInstructions(manifestPath, audience, format);
                    System.out.print(md);
                }
                System.exit(0);
            } catch (Exception e) {
                System.err.println("[kcp-mcp] Error: " + e.getMessage());
                System.exit(1);
            }
        }

        // ── Load command manifests if --commands-dir is provided ─────────────────
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

        // ── Start MCP server ────────────────────────────────────────────────────
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

    private static KcpInstructions.Format parseFormat(String value) {
        return switch (value.toLowerCase()) {
            case "compact" -> KcpInstructions.Format.COMPACT;
            case "agent"   -> KcpInstructions.Format.AGENT;
            default        -> KcpInstructions.Format.FULL;
        };
    }

    private static KcpSplitInstructions.SplitBy parseSplitBy(String value) {
        return switch (value.toLowerCase()) {
            case "scope" -> KcpSplitInstructions.SplitBy.SCOPE;
            case "unit"  -> KcpSplitInstructions.SplitBy.UNIT;
            case "none"  -> KcpSplitInstructions.SplitBy.NONE;
            default      -> KcpSplitInstructions.SplitBy.DIRECTORY;
        };
    }

    private static void printUsage() {
        System.err.print("""
            Usage: kcp-mcp [path/to/knowledge.yaml] [options]

            MCP Server Options:
              --agent-only              Only expose units with audience: [agent]
              --sub-manifests path ...  Additional manifests to merge
              --commands-dir <path>     Load kcp-commands manifests (enables get_command_syntax tool)
              --no-warnings             Suppress KCP validation warnings

            Static Generation Options:
              --generate-instructions   Generate copilot-instructions.md to stdout and exit
              --generate-agent          Generate .agent.md to stdout and exit
              --generate-all            Generate all three tiers to .github/ and exit
              --audience <value>        Filter units by audience (e.g. "agent", "human")
              --output-format <fmt>     Output format: full (default), compact, agent
              --output-dir <path>       Write split instruction files to this directory
              --split-by <strategy>     Split strategy: directory, scope, unit, none (default: directory)
              --max-chars <number>      Max chars for agent file (drops lower-scope units to fit)
              --help, -h                Show this help

            Examples:
              kcp-mcp                                           # serve ./knowledge.yaml
              kcp-mcp knowledge.yaml --agent-only               # filter to agent-facing units
              kcp-mcp knowledge.yaml --commands-dir /path/to/kcp-commands/commands
              kcp-mcp --generate-instructions knowledge.yaml > .github/copilot-instructions.md
              kcp-mcp --generate-instructions knowledge.yaml --output-format compact
              kcp-mcp --generate-instructions knowledge.yaml --output-dir .github/instructions --split-by directory
              kcp-mcp --generate-agent knowledge.yaml           # generate agent file to stdout
              kcp-mcp --generate-agent knowledge.yaml --max-chars 5000
              kcp-mcp --generate-all knowledge.yaml             # generate all three tiers to .github/

            Start by reading: knowledge://{project-slug}/manifest
            """);
    }
}
