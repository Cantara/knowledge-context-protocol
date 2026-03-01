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
  /** Additional manifest file paths whose units are merged into the primary namespace. */
  subManifests?: string[];
}

export interface KcpMcpServer {
  server: Server;
  manifest: KnowledgeManifest;
  projectSlug: string;
  /** Total units served (primary + all sub-manifests). */
  totalUnits: number;
}

/** Internal: tracks which directory a unit's paths resolve against. */
interface UnitContext {
  unit: KnowledgeUnit;
  manifestDir: string;
}

/**
 * Create an MCP Server that exposes one or more knowledge.yaml manifests as MCP resources.
 *
 * Units from all manifests are merged into a single namespace under the primary manifest's
 * project slug.  If two manifests define the same unit id, the primary manifest wins and a
 * warning is emitted.
 *
 * @param manifestPath  Absolute or relative path to the primary knowledge.yaml
 * @param options       agentOnly, warnOnValidation, subManifests (additional manifest paths)
 */
export function createKcpServer(
  manifestPath: string,
  options: KcpServerOptions = {}
): KcpMcpServer {
  const { agentOnly = false, warnOnValidation = true, subManifests = [] } =
    options;

  // ── Primary manifest ─────────────────────────────────────────────────────
  const resolvedPath = resolve(manifestPath);
  const primaryDir = dirname(resolvedPath);

  const manifest = parseFile(resolvedPath);
  const result = validate(manifest, primaryDir);

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
  const manifestUri = buildManifestUri(projectSlug);

  // ── Build merged unit context map ────────────────────────────────────────
  // Maps unit.id → { unit, manifestDir }.  Primary manifest units take precedence.
  const unitContextMap = new Map<string, UnitContext>(
    manifest.units.map((u) => [u.id, { unit: u, manifestDir: primaryDir }])
  );

  // Load each sub-manifest and merge its units
  for (const subPath of subManifests) {
    const resolvedSub = resolve(subPath);
    const subDir = dirname(resolvedSub);

    let subManifest: KnowledgeManifest;
    try {
      subManifest = parseFile(resolvedSub);
    } catch (e) {
      process.stderr.write(
        `  [kcp-mcp] warning: could not load sub-manifest ${resolvedSub}: ${e}\n`
      );
      continue;
    }

    const subResult = validate(subManifest, subDir);
    if (warnOnValidation && subResult.warnings.length > 0) {
      for (const w of subResult.warnings) {
        process.stderr.write(`  [kcp-mcp] warning (${resolvedSub}): ${w}\n`);
      }
    }
    if (!subResult.isValid) {
      process.stderr.write(
        `  [kcp-mcp] warning: skipping invalid sub-manifest ${resolvedSub}:\n` +
          subResult.errors.map((e) => `    ${e}`).join("\n") +
          "\n"
      );
      continue;
    }

    let added = 0;
    for (const unit of subManifest.units) {
      if (unitContextMap.has(unit.id)) {
        process.stderr.write(
          `  [kcp-mcp] warning: duplicate unit id '${unit.id}' in ${resolvedSub} — skipping\n`
        );
        continue;
      }
      unitContextMap.set(unit.id, { unit, manifestDir: subDir });
      added++;
    }
    if (warnOnValidation || added > 0) {
      process.stderr.write(
        `  [kcp-mcp] loaded sub-manifest ${resolvedSub} — ${added} unit(s)\n`
      );
    }
  }

  // ── Build static resource list ────────────────────────────────────────────
  const resourceList: McpResourceMeta[] = [
    buildManifestResource(manifest, projectSlug),
  ];
  for (const { unit } of unitContextMap.values()) {
    const r = buildUnitResource(unit, projectSlug, agentOnly);
    if (r !== null) resourceList.push(r);
  }

  const totalUnits = unitContextMap.size;

  // ── Create MCP server ─────────────────────────────────────────────────────
  const server = new Server(
    { name: `kcp-${projectSlug}`, version: "0.5.0" },
    { capabilities: { resources: {} } }
  );

  process.stderr.write(
    `[kcp-mcp] Serving '${manifest.project}' — ${totalUnits} unit(s)` +
      (subManifests.length > 0
        ? ` (${manifest.units.length} primary + ${totalUnits - manifest.units.length} from ${subManifests.length} sub-manifest(s))`
        : "") +
      (agentOnly ? " [agent-only]" : "") +
      `\n[kcp-mcp] Start with: ${manifestUri}\n`
  );

  // --- handlers ---

  server.setRequestHandler(ListResourcesRequestSchema, async () => ({
    resources: resourceList,
  }));

  server.setRequestHandler(ReadResourceRequestSchema, async (request) => {
    const uri = request.params.uri;

    // Manifest meta-resource (primary manifest JSON)
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

    // Unit resource — resolve id from URI, look up context
    const prefix = `knowledge://${projectSlug}/`;
    if (!uri.startsWith(prefix)) {
      throw new Error(`Unknown resource URI: ${uri}`);
    }
    const unitId = uri.slice(prefix.length);
    const ctx = unitContextMap.get(unitId);
    if (!ctx) {
      throw new Error(`No unit with id '${unitId}'`);
    }

    const content = readUnitContent(ctx.manifestDir, ctx.unit, uri);

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

  return { server, manifest, projectSlug, totalUnits };
}
