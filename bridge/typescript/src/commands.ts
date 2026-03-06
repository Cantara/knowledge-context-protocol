// KCP command manifest loader and formatter
// Loads kcp-commands YAML manifests and formats them as compact syntax blocks.

import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import yaml from "js-yaml";

export interface CommandManifest {
  command: string;
  subcommand?: string;
  platform: string;
  description: string;
  syntax: {
    usage: string;
    key_flags: Array<{ flag: string; description: string; use_when: string }>;
    preferred_invocations: Array<{
      invocation: string;
      use_when: string;
    }>;
  };
  output_schema?: { enable_filter: boolean };
}

/**
 * Build the lookup key for a command manifest.
 * "git commit" if subcommand exists, otherwise just "git".
 */
function manifestKey(m: CommandManifest): string {
  return m.subcommand ? `${m.command} ${m.subcommand}` : m.command;
}

/**
 * Load all YAML files from a directory into a map.
 * Key: "command subcommand" (e.g. "git commit") or just "command" if no subcommand.
 *
 * Non-YAML files and files that fail to parse are silently skipped.
 */
export function loadCommandManifests(
  dir: string
): Map<string, CommandManifest> {
  const map = new Map<string, CommandManifest>();

  let entries: string[];
  try {
    entries = readdirSync(dir);
  } catch {
    return map;
  }

  for (const entry of entries) {
    if (!entry.endsWith(".yaml") && !entry.endsWith(".yml")) continue;

    const filePath = join(dir, entry);
    try {
      const raw = readFileSync(filePath, "utf-8");
      const data = yaml.load(raw, { schema: yaml.DEFAULT_SCHEMA });
      if (!data || typeof data !== "object" || Array.isArray(data)) continue;

      const d = data as Record<string, unknown>;
      const manifest = parseCommandManifest(d);
      if (manifest) {
        map.set(manifestKey(manifest), manifest);
      }
    } catch {
      // Skip files that fail to parse
    }
  }

  return map;
}

function parseCommandManifest(
  d: Record<string, unknown>
): CommandManifest | null {
  if (!d["command"] || !d["syntax"]) return null;

  const syntaxRaw = d["syntax"] as Record<string, unknown>;
  const keyFlags = Array.isArray(syntaxRaw["key_flags"])
    ? (syntaxRaw["key_flags"] as Array<Record<string, string>>).map((f) => ({
        flag: String(f["flag"] ?? ""),
        description: String(f["description"] ?? ""),
        use_when: String(f["use_when"] ?? ""),
      }))
    : [];

  const preferred = Array.isArray(syntaxRaw["preferred_invocations"])
    ? (syntaxRaw["preferred_invocations"] as Array<Record<string, string>>).map(
        (p) => ({
          invocation: String(p["invocation"] ?? ""),
          use_when: String(p["use_when"] ?? ""),
        })
      )
    : [];

  return {
    command: String(d["command"]),
    subcommand: d["subcommand"] ? String(d["subcommand"]) : undefined,
    platform: String(d["platform"] ?? "all"),
    description: String(d["description"] ?? ""),
    syntax: {
      usage: String(syntaxRaw["usage"] ?? ""),
      key_flags: keyFlags,
      preferred_invocations: preferred,
    },
    output_schema: d["output_schema"]
      ? {
          enable_filter: Boolean(
            (d["output_schema"] as Record<string, unknown>)["enable_filter"]
          ),
        }
      : undefined,
  };
}

/**
 * Format a manifest as a compact syntax block (Phase A style).
 *
 * Example output:
 * ```
 * [kcp] git commit: Record staged changes to the repository
 * Usage: git commit [<options>]
 * Key flags:
 *   -m '<message>': Commit message inline  -> Simple one-line commits
 *   --amend: Replace the last commit  -> Fixing typo -- never after push
 * Preferred:
 *   git commit -m 'Add feature X'  # Standard single-line commit
 * ```
 */
export function formatSyntaxBlock(manifest: CommandManifest): string {
  const name = manifest.subcommand
    ? `${manifest.command} ${manifest.subcommand}`
    : manifest.command;

  const lines: string[] = [];
  lines.push(`[kcp] ${name}: ${manifest.description}`);
  lines.push(`Usage: ${manifest.syntax.usage}`);

  if (manifest.syntax.key_flags.length > 0) {
    lines.push("Key flags:");
    for (const f of manifest.syntax.key_flags) {
      lines.push(
        `  ${f.flag}: ${f.description}  \u2192 ${f.use_when}`
      );
    }
  }

  if (manifest.syntax.preferred_invocations.length > 0) {
    lines.push("Preferred:");
    for (const p of manifest.syntax.preferred_invocations) {
      lines.push(`  ${p.invocation}  # ${p.use_when}`);
    }
  }

  return lines.join("\n");
}

/**
 * Look up a command by query string.
 *
 * Strategy:
 * 1. Exact match: "git commit" -> map.get("git commit")
 * 2. Prefix match: "git" -> first entry whose key starts with "git"
 *    (returns the base command "git" if it exists, otherwise first subcommand)
 */
export function lookupCommand(
  map: Map<string, CommandManifest>,
  query: string
): CommandManifest | null {
  const normalized = query.trim().toLowerCase();

  // 1. Exact match
  for (const [key, manifest] of map) {
    if (key.toLowerCase() === normalized) return manifest;
  }

  // 2. Prefix match — find entries where the command portion matches
  const queryParts = normalized.split(/\s+/);
  const queryCmd = queryParts[0];

  // Prefer base command (no subcommand) first
  for (const [key, manifest] of map) {
    if (
      manifest.command.toLowerCase() === queryCmd &&
      !manifest.subcommand
    ) {
      return manifest;
    }
  }

  // Fall back to first subcommand match
  for (const [, manifest] of map) {
    if (manifest.command.toLowerCase() === queryCmd) {
      return manifest;
    }
  }

  return null;
}
