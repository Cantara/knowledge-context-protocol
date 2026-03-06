#!/usr/bin/env node
// KCP MCP Bridge CLI
// Usage: kcp-mcp [knowledge.yaml] [--agent-only] [--transport stdio|http] [--port 8000]
//                [--sub-manifests <glob>] [--commands-dir <path>]

import { existsSync, mkdirSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, isAbsolute, join, resolve, sep } from "node:path";
import { parseArgs } from "node:util";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { createKcpServer } from "./server.js";
import { loadCommandManifests } from "./commands.js";
import { generateInstructions, generateAgentFile } from "./instructions.js";
import { generateSplitInstructions } from "./split-instructions.js";

function printUsage(): void {
  process.stderr.write(
    `Usage: kcp-mcp [path/to/knowledge.yaml] [options]

MCP Server Options:
  --agent-only              Only expose units with audience: [agent]
  --sub-manifests <glob>    Additional manifests to merge (glob, relative to primary
                            manifest dir).  Supports a single * as directory wildcard.
                            Example: "fragments/*/knowledge.yaml"
  --commands-dir <path>     Load kcp-commands YAML manifests from this directory.
                            Enables the get_command_syntax tool.
  --transport <type>        Transport: stdio (default) or http
  --port <number>           Port for HTTP transport (default: 8000)
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
  kcp-mcp knowledge.yaml --sub-manifests "fragments/*/knowledge.yaml"
  kcp-mcp knowledge.yaml --commands-dir ../kcp-commands/commands
  kcp-mcp knowledge.yaml --transport http --port 9000
  kcp-mcp --generate-instructions knowledge.yaml    # generate instructions to stdout
  kcp-mcp --generate-instructions knowledge.yaml --output-format compact
  kcp-mcp --generate-instructions knowledge.yaml --output-dir .github/instructions --split-by directory
  kcp-mcp --generate-agent knowledge.yaml           # generate agent file to stdout
  kcp-mcp --generate-all knowledge.yaml             # generate all three tiers to .github/

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
      "generate-instructions": { type: "boolean", default: false },
      "generate-agent": { type: "boolean", default: false },
      "generate-all": { type: "boolean", default: false },
      audience: { type: "string" },
      "output-format": { type: "string", default: "full" },
      "output-dir": { type: "string" },
      "split-by": { type: "string", default: "directory" },
      "max-chars": { type: "string" },
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

  // --- Static generation modes (no MCP server) ---

  const isGenerateInstructions = values["generate-instructions"] as boolean;
  const isGenerateAgent = values["generate-agent"] as boolean;
  const isGenerateAll = values["generate-all"] as boolean;

  if (isGenerateAll) {
    const audience = values["audience"] as string | undefined;
    const ghDir = resolve(".github");
    const instrDir = join(ghDir, "instructions");
    const agentsDir = join(ghDir, "agents");

    mkdirSync(instrDir, { recursive: true });
    mkdirSync(agentsDir, { recursive: true });

    // Tier 1: .github/copilot-instructions.md (compact index)
    const compactContent = generateInstructions(manifestPath, { audience, format: "compact" });
    writeFileSync(join(ghDir, "copilot-instructions.md"), compactContent);
    process.stderr.write(`  wrote ${join(ghDir, "copilot-instructions.md")}\n`);

    // Tier 2: .github/instructions/*.instructions.md (split by directory)
    generateSplitInstructions(manifestPath, instrDir, {
      splitBy: "directory",
      audience,
    });
    process.stderr.write(`  wrote split instructions to ${instrDir}/\n`);

    // Tier 3: .github/agents/kcp-expert.agent.md
    const agentContent = generateAgentFile(manifestPath, { audience });
    writeFileSync(join(agentsDir, "kcp-expert.agent.md"), agentContent);
    process.stderr.write(`  wrote ${join(agentsDir, "kcp-expert.agent.md")}\n`);

    process.exit(0);
  }

  if (isGenerateAgent) {
    const audience = values["audience"] as string | undefined;
    const maxCharsStr = values["max-chars"] as string | undefined;
    const maxChars = maxCharsStr ? parseInt(maxCharsStr, 10) : undefined;
    const content = generateAgentFile(manifestPath, { audience, maxChars });
    process.stdout.write(content);
    process.exit(0);
  }

  if (isGenerateInstructions) {
    const audience = values["audience"] as string | undefined;
    const format = (values["output-format"] as string) as "full" | "compact" | "agent";
    const outputDir = values["output-dir"] as string | undefined;

    if (outputDir) {
      // Split mode: write individual instruction files
      const splitBy = (values["split-by"] as string) as "directory" | "scope" | "unit" | "none";
      mkdirSync(outputDir, { recursive: true });
      generateSplitInstructions(manifestPath, outputDir, { splitBy, audience });
      process.stderr.write(`  wrote split instructions to ${outputDir}/\n`);
    } else {
      // Single file mode: write to stdout
      const content = generateInstructions(manifestPath, { audience, format });
      process.stdout.write(content);
    }
    process.exit(0);
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
