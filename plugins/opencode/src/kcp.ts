// Lightweight KCP manifest reader for the OpenCode plugin.
// Parses knowledge.yaml and builds the structures the hooks need.

import { readFileSync, existsSync } from "node:fs"
import { join } from "node:path"
import yaml from "js-yaml"

export interface KcpUnit {
  id: string
  path: string
  intent: string
  triggers: string[]
  depends_on: string[]
  hints: {
    load_strategy?: string
    priority?: string
    density?: string
  }
}

export interface KcpManifest {
  project: string
  units: KcpUnit[]
  unitByPath: Map<string, KcpUnit>
}

export function loadManifest(projectRoot: string): KcpManifest | null {
  const manifestPath = join(projectRoot, "knowledge.yaml")
  if (!existsSync(manifestPath)) return null

  let data: unknown
  try {
    const raw = readFileSync(manifestPath, "utf-8")
    data = yaml.load(raw, { schema: yaml.DEFAULT_SCHEMA })
  } catch {
    return null
  }

  if (!data || typeof data !== "object" || Array.isArray(data)) return null
  const d = data as Record<string, unknown>
  if (!Array.isArray(d["units"])) return null

  const units: KcpUnit[] = (d["units"] as Record<string, unknown>[]).map(u => ({
    id: String(u["id"] ?? ""),
    path: String(u["path"] ?? ""),
    intent: String(u["intent"] ?? ""),
    triggers: asStringArray(u["triggers"]),
    depends_on: asStringArray(u["depends_on"]),
    hints: (u["hints"] as KcpUnit["hints"]) ?? {},
  }))

  const unitByPath = new Map<string, KcpUnit>()
  for (const unit of units) {
    unitByPath.set(unit.path, unit)
    // Also index by basename for shorter path matching
    const parts = unit.path.split("/")
    unitByPath.set(parts[parts.length - 1], unit)
  }

  return {
    project: String(d["project"] ?? ""),
    units,
    unitByPath,
  }
}

function asStringArray(value: unknown): string[] {
  if (!value) return []
  if (Array.isArray(value)) return value.map(String)
  return [String(value)]
}

/**
 * Build the compact knowledge map injected into the system prompt.
 * Target: ~800-1000 tokens for a 17-unit manifest.
 */
export function buildSystemSection(manifest: KcpManifest): string {
  const lines: string[] = [
    "## Codebase Knowledge Map",
    "",
    `This project has a \`knowledge.yaml\` manifest (KCP). Use this map to find`,
    `files directly before running glob/grep searches.`,
    `★ = load immediately  ·  space = load on demand`,
    "",
  ]

  for (const unit of manifest.units) {
    const always = unit.hints.load_strategy === "always"
    const marker = always ? "★" : " "
    const deps = unit.depends_on.length > 0 ? `  (after: ${unit.depends_on.join(", ")})` : ""
    const kw = unit.triggers.slice(0, 6).join(", ")

    lines.push(`${marker} [${unit.id}] ${unit.path}${deps}`)
    lines.push(`    ${unit.intent}`)
    if (kw) lines.push(`    keywords: ${kw}`)
    lines.push("")
  }

  return lines.join("\n")
}

/**
 * Find KCP units whose path is mentioned in a line of glob/grep output.
 */
export function findMatchingUnit(manifest: KcpManifest, line: string): KcpUnit | undefined {
  for (const unit of manifest.units) {
    if (line.includes(unit.path)) return unit
    // Also check basename match for short paths in grep output
    const basename = unit.path.split("/").pop() ?? ""
    if (basename.length > 4 && line.includes(basename)) return unit
  }
  return undefined
}
