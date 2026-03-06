// KCP MCP Server
// Reads a knowledge.yaml and exposes its units as MCP resources, tools, and prompts.

import { dirname, resolve } from "node:path";
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import {
  ListResourcesRequestSchema,
  ReadResourceRequestSchema,
  ListToolsRequestSchema,
  CallToolRequestSchema,
  ListPromptsRequestSchema,
  GetPromptRequestSchema,
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
import type { CommandManifest } from "./commands.js";
import { formatSyntaxBlock, lookupCommand } from "./commands.js";

export interface KcpServerOptions {
  agentOnly?: boolean;
  warnOnValidation?: boolean;
  /** Additional manifest file paths whose units are merged into the primary namespace. */
  subManifests?: string[];
  /** Loaded command manifests map (from --commands-dir). If provided, enables get_command_syntax tool. */
  commandManifests?: Map<string, CommandManifest>;
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

// ── Search scoring ───────────────────────────────────────────────────────────

interface SearchResult {
  id: string;
  intent: string;
  path: string;
  audience: string[];
  uri: string;
  score: number;
}

/**
 * Score a unit against a set of query terms.
 * - trigger match: 5 pts each
 * - intent match: 3 pts each
 * - id/path match: 1 pt each
 */
function scoreUnit(
  unit: KnowledgeUnit,
  terms: string[],
  projectSlug: string
): SearchResult {
  let score = 0;
  const lowerTriggers = unit.triggers.map((t) => t.toLowerCase());
  const lowerIntent = unit.intent.toLowerCase();
  const lowerId = unit.id.toLowerCase();
  const lowerPath = unit.path.toLowerCase();

  for (const term of terms) {
    const lterm = term.toLowerCase();

    // Trigger match — 5 pts per matching trigger
    for (const trig of lowerTriggers) {
      if (trig.includes(lterm)) score += 5;
    }

    // Intent match — 3 pts
    if (lowerIntent.includes(lterm)) score += 3;

    // Id match — 1 pt
    if (lowerId.includes(lterm)) score += 1;

    // Path match — 1 pt
    if (lowerPath.includes(lterm)) score += 1;
  }

  return {
    id: unit.id,
    intent: unit.intent,
    path: unit.path,
    audience: unit.audience,
    uri: buildUnitUri(projectSlug, unit.id),
    score,
  };
}

/**
 * Create an MCP Server that exposes one or more knowledge.yaml manifests as MCP resources,
 * tools, and prompts.
 *
 * Units from all manifests are merged into a single namespace under the primary manifest's
 * project slug.  If two manifests define the same unit id, the primary manifest wins and a
 * warning is emitted.
 *
 * @param manifestPath  Absolute or relative path to the primary knowledge.yaml
 * @param options       agentOnly, warnOnValidation, subManifests, commandManifests
 */
export function createKcpServer(
  manifestPath: string,
  options: KcpServerOptions = {}
): KcpMcpServer {
  const {
    agentOnly = false,
    warnOnValidation = true,
    subManifests = [],
    commandManifests,
  } = options;

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
    { name: `kcp-${projectSlug}`, version: "0.6.0" },
    { capabilities: { resources: {}, tools: {}, prompts: {} } }
  );

  process.stderr.write(
    `[kcp-mcp] Serving '${manifest.project}' — ${totalUnits} unit(s)` +
      (subManifests.length > 0
        ? ` (${manifest.units.length} primary + ${totalUnits - manifest.units.length} from ${subManifests.length} sub-manifest(s))`
        : "") +
      (agentOnly ? " [agent-only]" : "") +
      (commandManifests ? ` + ${commandManifests.size} command(s)` : "") +
      `\n[kcp-mcp] Start with: ${manifestUri}\n`
  );

  // ── Resource handlers ──────────────────────────────────────────────────────

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

  // ── Tool definitions ───────────────────────────────────────────────────────

  const toolDefinitions = [
    {
      name: "search_knowledge",
      description:
        "Search knowledge units by query. Matches against triggers, intent, and id.",
      inputSchema: {
        type: "object" as const,
        properties: {
          query: {
            type: "string",
            description: "Search terms (space-separated)",
          },
          audience: {
            type: "string",
            description:
              "Filter by audience: agent | developer | architect | operator | human",
          },
          scope: {
            type: "string",
            description: "Filter by scope: global | project | module",
          },
        },
        required: ["query"],
      },
    },
    {
      name: "get_unit",
      description:
        "Fetch the content of a specific knowledge unit by its id.",
      inputSchema: {
        type: "object" as const,
        properties: {
          unit_id: {
            type: "string",
            description: "The unit id from search_knowledge results",
          },
        },
        required: ["unit_id"],
      },
    },
    {
      name: "get_command_syntax",
      description:
        "Get syntax guidance for a CLI command from kcp-commands manifests.",
      inputSchema: {
        type: "object" as const,
        properties: {
          command: {
            type: "string",
            description:
              "Command name e.g. 'git commit', 'mvn', 'docker'",
          },
        },
        required: ["command"],
      },
    },
  ];

  server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: toolDefinitions,
  }));

  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const { name, arguments: args } = request.params;

    switch (name) {
      case "search_knowledge": {
        const query = String(args?.["query"] ?? "");
        const audienceFilter = args?.["audience"] as string | undefined;
        const scopeFilter = args?.["scope"] as string | undefined;

        if (!query.trim()) {
          return {
            content: [
              {
                type: "text" as const,
                text: "Please provide a search query.",
              },
            ],
          };
        }

        const terms = query.trim().split(/\s+/);
        const results: SearchResult[] = [];

        for (const { unit } of unitContextMap.values()) {
          // Apply filters
          if (
            audienceFilter &&
            !unit.audience.includes(audienceFilter)
          ) {
            continue;
          }
          if (scopeFilter && unit.scope !== scopeFilter) continue;

          const scored = scoreUnit(unit, terms, projectSlug);
          if (scored.score > 0) {
            results.push(scored);
          }
        }

        if (results.length === 0) {
          const ids = [...unitContextMap.keys()].join(", ");
          return {
            content: [
              {
                type: "text" as const,
                text: `No units matched query "${query}". Available units: ${ids}`,
              },
            ],
          };
        }

        // Sort by score descending, take top 5
        results.sort((a, b) => b.score - a.score);
        const top5 = results.slice(0, 5);

        return {
          content: [
            {
              type: "text" as const,
              text: JSON.stringify(top5, null, 2),
            },
          ],
        };
      }

      case "get_unit": {
        const unitId = String(args?.["unit_id"] ?? "");
        const ctx = unitContextMap.get(unitId);

        if (!ctx) {
          const ids = [...unitContextMap.keys()].join(", ");
          return {
            content: [
              {
                type: "text" as const,
                text: `Unit not found: "${unitId}". Available units: ${ids}`,
              },
            ],
            isError: true,
          };
        }

        const uri = buildUnitUri(projectSlug, unitId);
        const unitContent = readUnitContent(ctx.manifestDir, ctx.unit, uri);

        if (unitContent.type === "text") {
          return {
            content: [
              {
                type: "text" as const,
                text: unitContent.text,
              },
            ],
          };
        } else {
          return {
            content: [
              {
                type: "text" as const,
                text: `[Binary content: ${unitContent.mimeType}, base64 length: ${unitContent.blob.length}]`,
              },
            ],
          };
        }
      }

      case "get_command_syntax": {
        if (!commandManifests) {
          return {
            content: [
              {
                type: "text" as const,
                text: "No command manifests loaded \u2014 start kcp-mcp with --commands-dir",
              },
            ],
            isError: true,
          };
        }

        const cmdQuery = String(args?.["command"] ?? "");
        const found = lookupCommand(commandManifests, cmdQuery);

        if (!found) {
          // Collect unique base command names
          const commands = new Set<string>();
          for (const m of commandManifests.values()) {
            commands.add(m.command);
          }
          const available = [...commands].sort().join(", ");
          return {
            content: [
              {
                type: "text" as const,
                text: `Unknown command: "${cmdQuery}". Available commands: ${available}`,
              },
            ],
            isError: true,
          };
        }

        return {
          content: [
            {
              type: "text" as const,
              text: formatSyntaxBlock(found),
            },
          ],
        };
      }

      default:
        return {
          content: [
            {
              type: "text" as const,
              text: `Unknown tool: "${name}"`,
            },
          ],
          isError: true,
        };
    }
  });

  // ── Prompt definitions ─────────────────────────────────────────────────────

  const promptDefinitions = [
    {
      name: "sdd-review",
      description:
        "Review code or architecture using SDD (Skill-Driven Development) methodology",
      arguments: [
        {
          name: "focus",
          description:
            "Focus area: architecture | quality | security | performance",
          required: false,
        },
      ],
    },
    {
      name: "kcp-explore",
      description: "Explore available knowledge units for a topic",
      arguments: [
        {
          name: "topic",
          description:
            "Topic to explore e.g. 'authentication', 'deployment'",
          required: true,
        },
      ],
    },
  ];

  server.setRequestHandler(ListPromptsRequestSchema, async () => ({
    prompts: promptDefinitions,
  }));

  server.setRequestHandler(GetPromptRequestSchema, async (request) => {
    const { name, arguments: args } = request.params;

    switch (name) {
      case "sdd-review": {
        const focus = (args?.["focus"] as string) || "architecture";

        const focusGuidance: Record<string, string> = {
          architecture: [
            "1. **Intent Clarity**: Does each component have a single, clearly stated purpose?",
            "2. **Component Boundaries**: Are module boundaries clean? Can you describe each module's responsibility in one sentence?",
            "3. **Dependency Direction**: Do dependencies flow from concrete to abstract? Are there circular dependencies?",
            "4. **Knowledge Documentation**: Is there a knowledge.yaml or equivalent that maps the architecture for AI assistants?",
            "5. **Skill Decomposition**: Could an AI agent understand and modify each component independently?",
          ].join("\n"),
          quality: [
            "1. **Test Coverage**: Are critical paths covered? Do tests verify intent, not implementation details?",
            "2. **Error Handling**: Are errors handled at the right level? Do error messages help diagnosis?",
            "3. **Naming**: Do names reflect domain concepts? Would a new developer understand the code from names alone?",
            "4. **Code Duplication**: Are there repeated patterns that should be extracted into shared utilities?",
            "5. **Documentation Freshness**: Does the documentation match the current implementation?",
          ].join("\n"),
          security: [
            "1. **Input Validation**: Are all external inputs validated before use?",
            "2. **Authentication & Authorization**: Are auth boundaries clearly defined and enforced?",
            "3. **Secret Management**: Are secrets externalized? No hardcoded credentials?",
            "4. **Dependency Security**: Are dependencies up to date? Any known CVEs?",
            "5. **Path Traversal**: Are file paths validated against traversal attacks?",
          ].join("\n"),
          performance: [
            "1. **Hot Paths**: Are the most-called code paths optimized? Are there unnecessary allocations?",
            "2. **Caching**: Are expensive computations cached appropriately? Is cache invalidation correct?",
            "3. **I/O Patterns**: Are I/O operations batched where possible? Any N+1 query patterns?",
            "4. **Concurrency**: Are concurrent operations safe? Are there potential deadlocks or race conditions?",
            "5. **Resource Cleanup**: Are resources (connections, file handles, timers) properly cleaned up?",
          ].join("\n"),
        };

        const criteria =
          focusGuidance[focus] ?? focusGuidance["architecture"];

        return {
          messages: [
            {
              role: "user" as const,
              content: {
                type: "text" as const,
                text: [
                  `## SDD Review: ${focus}`,
                  "",
                  "You are reviewing code using the Skill-Driven Development (SDD) methodology.",
                  "SDD emphasizes clear intent, modular components that AI agents can understand,",
                  "and structured knowledge documentation.",
                  "",
                  `### Review Criteria (${focus}):`,
                  "",
                  criteria,
                  "",
                  "### Instructions",
                  "",
                  "Review the code or architecture against these criteria. For each item:",
                  "- State whether it passes, needs improvement, or fails",
                  "- Provide specific examples from the code",
                  "- Suggest concrete improvements where needed",
                  "",
                  "Start by examining the project structure, then drill into the focus area.",
                  "Use `search_knowledge` to find relevant project knowledge units first.",
                ].join("\n"),
              },
            },
          ],
        };
      }

      case "kcp-explore": {
        const topic = String(args?.["topic"] ?? "");

        return {
          messages: [
            {
              role: "user" as const,
              content: {
                type: "text" as const,
                text: [
                  `## Explore Knowledge: ${topic}`,
                  "",
                  `Find and present all knowledge units related to "${topic}".`,
                  "",
                  "### Steps",
                  "",
                  `1. Call the \`search_knowledge\` tool with query: "${topic}"`,
                  "2. For each result, summarize:",
                  "   - **Unit ID** and relevance score",
                  "   - **Intent**: what this unit teaches",
                  "   - **Path**: where to find it",
                  "   - **Audience**: who it is written for",
                  "3. Suggest a reading order based on dependencies (check depends_on fields)",
                  "4. Highlight which units are most relevant to the topic",
                  "",
                  "Present the results as a navigable knowledge map that helps the user",
                  "understand what information is available and where to start.",
                ].join("\n"),
              },
            },
          ],
        };
      }

      default:
        throw new Error(`Unknown prompt: "${name}"`);
    }
  });

  return { server, manifest, projectSlug, totalUnits };
}
