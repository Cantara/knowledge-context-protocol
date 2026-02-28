package no.cantara.kcp.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * CLI entry point for kcp-mcp.
 *
 * <pre>
 * Usage: kcp-mcp [knowledge.yaml] [--agent-only] [--no-warnings]
 * </pre>
 */
public class KcpMcpCli {

    public static void main(String[] args) {
        Path    manifestPath     = Path.of("knowledge.yaml");
        boolean agentOnly        = false;
        boolean warnOnValidation = true;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--agent-only"  -> agentOnly        = true;
                case "--no-warnings" -> warnOnValidation = false;
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

        StdioServerTransportProvider transport =
            new StdioServerTransportProvider(McpJsonDefaults.getMapper());

        McpSyncServer server;
        try {
            server = KcpServer.createServer(
                manifestPath, transport, agentOnly, warnOnValidation);
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
}
