#!/usr/bin/env node
// KCP MCP Bridge CLI
// Usage: kcp-mcp [knowledge.yaml] [--agent-only] [--transport stdio|http] [--port 8000]
//                [--sub-manifests <glob>] [--commands-dir <path>]

import { existsSync, readdirSync } from "node:fs";
import { dirname, isAbsolute, join, resolve, sep } from "node:path";
import { parseArgs } from "node:util";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { createKcpServer } from "./server.js";
import { loadCommandManifests } from "./commands.js";

function printUsage(): void {
  process.stderr.write(
    `Usage: kcp-mcp [path/to/knowledge.yaml] [options]

Options:
  --agent-only              Only expose units with audience: [agent]
  --sub-manifests <glob>    Additional manifests to merge (glob, relative to primary
                            manifest dir).  Supports a single * as directory wildcard.
                            Example: "fragments/*/knowledge.yaml"
  --commands-dir <path>     Load kcp-commands YAML manifests from this directory.
                            Enables the get_command_syntax tool.
  --transport <type>        Transport: stdio (default) or http
  --port <number>           Port for HTTP transport (default: 8000)
  --no-warnings             Suppress KCP validation warnings
  --help, -h                Show this help

Examples:
  kcp-mcp                                           # serve ./knowledge.yaml
  kcp-mcp knowledge.yaml --agent-only               # filter to agent-facing units
  kcp-mcp knowledge.yaml --sub-manifests "fragments/*/knowledge.yaml"
  kcp-mcp knowledge.yaml --commands-dir ../kcp-commands/commands
  kcp-mcp knowledge.yaml --transport http --port 9000

Units from all manifests are merged into the primary project namespace.
Start by reading: knowledge://{project-slug}/manifest
`
  );
}

/**
 * Expand a glob pattern containing a single '*' directory wildcard.
 * Relative patterns are resolved against baseDir.
 *
 * Supports only the common case: one '*' as an entire path component
 * (e.g. a pattern where one path segment is exactly the asterisk character).
 */
function expandGlob(pattern: string, baseDir: string): string[] {
  const fullPattern = isAbsolute(pattern) ? pattern : join(baseDir, pattern);

  if (!fullPattern.includes("*")) {
    return existsSync(fullPattern) ? [fullPattern] : [];
  }

  // Split by the separator and find the '*' component
  const parts = fullPattern.split(sep);
  const starIdx = parts.indexOf("*");
  if (starIdx < 0) {
    // '*' is embedded in a segment (e.g. "frag*"), not supported — treat as literal
    return existsSync(fullPattern) ? [fullPattern] : [];
  }

  const parentDir = parts.slice(0, starIdx).join(sep) || sep;
  const restParts = parts.slice(starIdx + 1);

  if (!existsSync(parentDir)) return [];

  return readdirSync(parentDir, { withFileTypes: true })
    .filter((d) => d.isDirectory())
    .map((d) => join(parentDir, d.name, ...restParts))
    .filter(existsSync)
    .sort(); // deterministic ordering
}

async function main(): Promise<void> {
  const { values, positionals } = parseArgs({
    args: process.argv.slice(2),
    options: {
      "agent-only": { type: "boolean", default: false },
      "sub-manifests": { type: "string" },
      "commands-dir": { type: "string" },
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

  // Resolve sub-manifests from glob (relative to primary manifest directory)
  const primaryDir = dirname(resolve(manifestPath));
  let subManifestPaths: string[] = [];
  const subGlob = values["sub-manifests"] as string | undefined;
  if (subGlob) {
    subManifestPaths = expandGlob(subGlob, primaryDir);
    if (subManifestPaths.length === 0) {
      process.stderr.write(
        `  [kcp-mcp] warning: --sub-manifests '${subGlob}' matched no files\n`
      );
    }
  }

  // Load command manifests if --commands-dir is provided
  let commandManifests: Map<string, import("./commands.js").CommandManifest> | undefined;
  const commandsDir = values["commands-dir"] as string | undefined;
  if (commandsDir) {
    const resolvedCmdsDir = resolve(commandsDir);
    if (!existsSync(resolvedCmdsDir)) {
      process.stderr.write(
        `  [kcp-mcp] warning: --commands-dir '${resolvedCmdsDir}' does not exist\n`
      );
    } else {
      commandManifests = loadCommandManifests(resolvedCmdsDir);
      if (commandManifests.size === 0) {
        process.stderr.write(
          `  [kcp-mcp] warning: no command manifests found in ${resolvedCmdsDir}\n`
        );
      }
    }
  }

  let kcpServer;
  try {
    kcpServer = createKcpServer(manifestPath, {
      agentOnly: values["agent-only"] as boolean,
      warnOnValidation: !(values["no-warnings"] as boolean),
      subManifests: subManifestPaths,
      commandManifests,
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
