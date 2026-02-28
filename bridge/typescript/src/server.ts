// KCP MCP Server
// Reads a knowledge.yaml and exposes its units as MCP resources.

import { dirname, resolve } from "node:path";
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import {
  ListResourcesRequestSchema,
  ReadResourceRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import { parseFile } from "./parser.js";
import { validate } from "./validator.js";
import {
  toProjectSlug,
  buildUnitResource,
  buildManifestResource,
  buildManifestUri,
  buildUnitUri,
  manifestToJson,
  type McpResourceMeta,
} from "./mapper.js";
import { readUnitContent } from "./content.js";
import type { KnowledgeManifest, KnowledgeUnit } from "./model.js";

export interface KcpServerOptions {
  agentOnly?: boolean;
  warnOnValidation?: boolean;
}

export interface KcpMcpServer {
  server: Server;
  manifest: KnowledgeManifest;
  projectSlug: string;
}

/**
 * Create an MCP Server that exposes a knowledge.yaml as MCP resources.
 *
 * @param manifestPath  Absolute or relative path to knowledge.yaml
 * @param options       agentOnly: filter to audience:agent units; warnOnValidation: log warnings
 */
export function createKcpServer(
  manifestPath: string,
  options: KcpServerOptions = {}
): KcpMcpServer {
  const { agentOnly = false, warnOnValidation = true } = options;

  const resolvedPath = resolve(manifestPath);
  const manifestDir = dirname(resolvedPath);

  // Parse and validate
  const manifest = parseFile(resolvedPath);
  const result = validate(manifest, manifestDir);

  if (warnOnValidation && result.warnings.length > 0) {
    for (const w of result.warnings) {
      process.stderr.write(`  [kcp-mcp] warning: ${w}\n`);
    }
  }
  if (!result.isValid) {
    throw new Error(
      `Invalid manifest ${resolvedPath}:\n${result.errors.join("\n")}`
    );
  }

  const projectSlug = toProjectSlug(manifest.project);

  // Build static resource list
  const resourceList: McpResourceMeta[] = [
    buildManifestResource(manifest, projectSlug),
  ];
  for (const unit of manifest.units) {
    const r = buildUnitResource(unit, projectSlug, agentOnly);
    if (r !== null) resourceList.push(r);
  }

  // Index units by id for fast lookup
  const unitIndex = new Map<string, KnowledgeUnit>(
    manifest.units.map((u) => [u.id, u])
  );

  // Create MCP server
  const server = new Server(
    { name: `kcp-${projectSlug}`, version: "0.1.0" },
    {
      capabilities: {
        resources: {},
      },
    }
  );

  // Server instructions (shown to the agent on connection)
  const manifestUri = buildManifestUri(projectSlug);
  process.stderr.write(
    `[kcp-mcp] Serving '${manifest.project}' — ${manifest.units.length} units` +
      (agentOnly ? " (agent-only filter active)" : "") +
      `\n[kcp-mcp] Start with: ${manifestUri}\n`
  );

  // --- handlers ---

  server.setRequestHandler(ListResourcesRequestSchema, async () => ({
    resources: resourceList,
  }));

  server.setRequestHandler(ReadResourceRequestSchema, async (request) => {
    const uri = request.params.uri;

    // Manifest meta-resource
    if (uri === manifestUri) {
      return {
        contents: [
          {
            uri,
            mimeType: "application/json",
            text: manifestToJson(manifest, projectSlug),
          },
        ],
      };
    }

    // Unit resource — parse unit id from URI
    const prefix = `knowledge://${projectSlug}/`;
    if (!uri.startsWith(prefix)) {
      throw new Error(`Unknown resource URI: ${uri}`);
    }
    const unitId = uri.slice(prefix.length);
    const unit = unitIndex.get(unitId);
    if (!unit) {
      throw new Error(`No unit with id '${unitId}'`);
    }

    const content = readUnitContent(manifestDir, unit, uri);

    if (content.type === "text") {
      return {
        contents: [
          { uri: content.uri, mimeType: content.mimeType, text: content.text },
        ],
      };
    } else {
      return {
        contents: [
          { uri: content.uri, mimeType: content.mimeType, blob: content.blob },
        ],
      };
    }
  });

  return { server, manifest, projectSlug };
}
