// KCP → MCP resource mapping — pure functions, no I/O
// The mapping is identical across Python, Java, and TypeScript bridges.

import type { KnowledgeManifest, KnowledgeUnit } from "./model.js";

// --- MIME type resolution ---

const FORMAT_TO_MIME: Record<string, string> = {
  markdown: "text/markdown",
  pdf: "application/pdf",
  openapi: "application/vnd.oai.openapi+yaml",
  "json-schema": "application/schema+json",
  jupyter: "application/x-ipynb+json",
  html: "text/html",
  asciidoc: "text/asciidoc",
  rst: "text/x-rst",
  vtt: "text/vtt",
  yaml: "application/yaml",
  json: "application/json",
  csv: "text/csv",
  text: "text/plain",
};

const EXT_TO_MIME: Record<string, string> = {
  ".md": "text/markdown",
  ".markdown": "text/markdown",
  ".pdf": "application/pdf",
  ".yaml": "application/yaml",
  ".yml": "application/yaml",
  ".json": "application/json",
  ".html": "text/html",
  ".htm": "text/html",
  ".txt": "text/plain",
  ".csv": "text/csv",
  ".rst": "text/x-rst",
  ".adoc": "text/asciidoc",
  ".ipynb": "application/x-ipynb+json",
  ".vtt": "text/vtt",
};

export const BINARY_MIME_PREFIXES = [
  "application/pdf",
  "image/",
  "audio/",
  "video/",
];

export function resolveMimeType(unit: KnowledgeUnit): string {
  if (unit.content_type) return unit.content_type;
  if (unit.format && FORMAT_TO_MIME[unit.format])
    return FORMAT_TO_MIME[unit.format];
  const dotIdx = unit.path.lastIndexOf(".");
  if (dotIdx !== -1) {
    const ext = unit.path.slice(dotIdx).toLowerCase();
    if (EXT_TO_MIME[ext]) return EXT_TO_MIME[ext];
  }
  return "text/plain";
}

export function isBinaryMime(mime: string): boolean {
  return BINARY_MIME_PREFIXES.some((p) => mime.startsWith(p));
}

// --- URI construction ---

export function toProjectSlug(project: string): string {
  return project
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "");
}

export function buildUnitUri(projectSlug: string, unitId: string): string {
  return `knowledge://${projectSlug}/${unitId}`;
}

export function buildManifestUri(projectSlug: string): string {
  return `knowledge://${projectSlug}/manifest`;
}

// --- Priority from scope ---

const SCOPE_PRIORITY: Record<string, number> = {
  global: 1.0,
  project: 0.7,
  module: 0.5,
};

export function scopePriority(scope: string): number {
  return SCOPE_PRIORITY[scope] ?? 0.5;
}

// --- Audience mapping ---

export function mapAudience(audience: string[]): Array<"user" | "assistant"> {
  const result = new Set<"user" | "assistant">();
  for (const a of audience) {
    if (a === "agent") result.add("assistant");
    if (["human", "developer", "architect", "operator", "devops"].includes(a))
      result.add("user");
  }
  if (result.size === 0) result.add("user");
  return [...result];
}

// --- Description construction ---

export function buildDescription(unit: KnowledgeUnit): string {
  const parts: string[] = [unit.intent, ""];
  parts.push(`Audience: ${unit.audience.join(", ")}`);
  parts.push(`Scope: ${unit.scope}`);
  parts.push(`Kind: ${unit.kind ?? "knowledge"}`);
  if (unit.validated) parts.push(`Validated: ${unit.validated}`);
  if (unit.depends_on.length > 0)
    parts.push(`Depends on: ${unit.depends_on.join(", ")}`);
  if (unit.triggers.length > 0)
    parts.push(`Triggers: ${unit.triggers.join(", ")}`);
  if (unit.supersedes) parts.push(`Supersedes: ${unit.supersedes}`);
  return parts.join("\n");
}

// --- MCP Resource shapes ---
// These are plain objects matching the MCP protocol's resource schema.
// We define our own lightweight types to avoid deep SDK type coupling in this module.

export interface McpResourceMeta {
  uri: string;
  name: string;
  title: string;
  description: string;
  mimeType: string;
  annotations: {
    audience: Array<"user" | "assistant">;
    priority: number;
    lastModified?: string;
  };
}

export function buildUnitResource(
  unit: KnowledgeUnit,
  projectSlug: string,
  agentOnly: boolean = false
): McpResourceMeta | null {
  if (agentOnly && !unit.audience.includes("agent")) return null;

  const title =
    unit.intent.length <= 80
      ? unit.intent
      : unit.intent.slice(0, 77) + "...";

  const annotations: McpResourceMeta["annotations"] = {
    audience: mapAudience(unit.audience),
    priority: scopePriority(unit.scope),
  };
  if (unit.validated) {
    annotations.lastModified = `${unit.validated}T00:00:00Z`;
  }

  return {
    uri: buildUnitUri(projectSlug, unit.id),
    name: unit.id,
    title,
    description: buildDescription(unit),
    mimeType: resolveMimeType(unit),
    annotations,
  };
}

export function buildManifestResource(
  manifest: KnowledgeManifest,
  projectSlug: string
): McpResourceMeta {
  const n = manifest.units.length;
  return {
    uri: buildManifestUri(projectSlug),
    name: "manifest",
    title: `Knowledge index: ${manifest.project}`,
    description:
      `Complete index of all ${n} knowledge unit(s) in '${manifest.project}'. ` +
      `Read this first to navigate the knowledge graph. ` +
      `Returns JSON with all units, their intents, dependencies, and relationships.`,
    mimeType: "application/json",
    annotations: {
      audience: ["assistant", "user"],
      priority: 1.0,
    },
  };
}

// --- Manifest JSON serialization ---

export function manifestToJson(
  manifest: KnowledgeManifest,
  projectSlug: string
): string {
  const payload = {
    project: manifest.project,
    version: manifest.version,
    kcp_version: manifest.kcp_version,
    updated: manifest.updated ?? null,
    language: manifest.language ?? null,
    units: manifest.units.map((u) => {
      const entry: Record<string, unknown> = {
        id: u.id,
        path: u.path,
        intent: u.intent,
        scope: u.scope,
        audience: u.audience,
      };
      if (u.kind) entry["kind"] = u.kind;
      if (u.format) entry["format"] = u.format;
      if (u.content_type) entry["content_type"] = u.content_type;
      if (u.language) entry["language"] = u.language;
      if (u.validated) entry["validated"] = u.validated;
      if (u.update_frequency) entry["update_frequency"] = u.update_frequency;
      if (u.depends_on.length > 0) entry["depends_on"] = u.depends_on;
      if (u.supersedes) entry["supersedes"] = u.supersedes;
      if (u.triggers.length > 0) entry["triggers"] = u.triggers;
      return entry;
    }),
    relationships: manifest.relationships.map((r) => ({
      from: r.from_id,
      to: r.to_id,
      type: r.type,
    })),
  };
  return JSON.stringify(payload, null, 2);
}
