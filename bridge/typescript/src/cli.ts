#!/usr/bin/env node
// KCP MCP Bridge CLI
// Usage: kcp-mcp [knowledge.yaml] [--agent-only] [--transport stdio|http] [--port 8000]

import { existsSync } from "node:fs";
import { parseArgs } from "node:util";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { createKcpServer } from "./server.js";

function printUsage(): void {
  process.stderr.write(
    `Usage: kcp-mcp [path/to/knowledge.yaml] [options]

Options:
  --agent-only        Only expose units with audience: [agent]
  --transport <type>  Transport: stdio (default) or http
  --port <number>     Port for HTTP transport (default: 8000)
  --no-warnings       Suppress KCP validation warnings
  --help, -h          Show this help

Examples:
  kcp-mcp                              # serve ./knowledge.yaml via stdio
  kcp-mcp knowledge.yaml --agent-only  # filter to agent-facing units
  kcp-mcp knowledge.yaml --transport http --port 9000

The server exposes each knowledge unit as an MCP resource.
Start by reading: knowledge://{project-slug}/manifest
`
  );
}

async function main(): Promise<void> {
  const { values, positionals } = parseArgs({
    args: process.argv.slice(2),
    options: {
      "agent-only": { type: "boolean", default: false },
      transport: { type: "string", default: "stdio" },
      port: { type: "string", default: "8000" },
      "no-warnings": { type: "boolean", default: false },
      help: { type: "boolean", default: false, short: "h" },
    },
    allowPositionals: true,
    strict: false,
  });

  if (values["help"]) {
    printUsage();
    process.exit(0);
  }

  const manifestPath = positionals[0] ?? "knowledge.yaml";

  if (!existsSync(manifestPath)) {
    process.stderr.write(`Error: manifest not found: ${manifestPath}\n`);
    process.exit(1);
  }

  let kcpServer;
  try {
    kcpServer = createKcpServer(manifestPath, {
      agentOnly: values["agent-only"] as boolean,
      warnOnValidation: !(values["no-warnings"] as boolean),
    });
  } catch (err) {
    process.stderr.write(
      `Error: ${err instanceof Error ? err.message : String(err)}\n`
    );
    process.exit(1);
  }

  const transport = values["transport"] as string;

  if (transport === "http") {
    // Streamable HTTP transport
    const port = parseInt(values["port"] as string, 10);
    const { StreamableHTTPServerTransport } = await import(
      "@modelcontextprotocol/sdk/server/streamableHttp.js"
    );
    const http = await import("node:http");

    let sessionTransport: InstanceType<
      typeof StreamableHTTPServerTransport
    > | null = null;

    const httpServer = http.createServer(async (req, res) => {
      if (!sessionTransport) {
        sessionTransport = new StreamableHTTPServerTransport({
          sessionIdGenerator: undefined, // stateless
        });
        await kcpServer.server.connect(sessionTransport);
      }
      await sessionTransport.handleRequest(req, res);
    });

    httpServer.listen(port, () => {
      process.stderr.write(
        `[kcp-mcp] HTTP transport listening on http://localhost:${port}/mcp\n`
      );
    });
  } else {
    // Default: stdio transport
    const stdioTransport = new StdioServerTransport();
    await kcpServer.server.connect(stdioTransport);
  }
}

main().catch((err) => {
  process.stderr.write(
    `Fatal: ${err instanceof Error ? err.message : String(err)}\n`
  );
  process.exit(1);
});
