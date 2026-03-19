// KCP → MCP resource mapping — pure functions, no I/O
// The mapping is identical across Python, Java, and TypeScript bridges.

import type { Authority, Discovery, KnowledgeManifest, KnowledgeUnit, Visibility } from "./model.js";

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
  if (unit.access && unit.access !== "public") parts.push(`Access: ${unit.access}`);
  if (unit.sensitivity && unit.sensitivity !== "public")
    parts.push(`Sensitivity: ${unit.sensitivity}`);
  if (unit.depends_on.length > 0)
    parts.push(`Depends on: ${unit.depends_on.join(", ")}`);
  if (unit.triggers.length > 0)
    parts.push(`Triggers: ${unit.triggers.join(", ")}`);
  if (unit.supersedes) parts.push(`Supersedes: ${unit.supersedes}`);
  if (unit.deprecated) parts.push(`Deprecated: true`);
  if (unit.compliance?.data_residency?.length)
    parts.push(`Data residency: ${unit.compliance.data_residency.join(", ")}`);
  if (unit.compliance?.regulations?.length)
    parts.push(`Regulations: ${unit.compliance.regulations.join(", ")}`);
  if (unit.delegation?.max_depth !== undefined)
    parts.push(`Delegation max depth: ${unit.delegation.max_depth}`);
  if (unit.delegation?.human_in_the_loop) {
    const hitl = unit.delegation.human_in_the_loop;
    const desc = hitl.required ? `required (${hitl.approval_mechanism ?? "unspecified"})` : "not required";
    parts.push(`Human in the loop: ${desc}`);
  }
  if (unit.delegation?.audit_chain !== undefined)
    parts.push(`Delegation audit chain: ${unit.delegation.audit_chain}`);
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

// --- RFC-0009 / RFC-0012 mapping helpers ---

export function mapAuthority(authority: Authority): Record<string, string> {
  const result: Record<string, string> = {};
  for (const [key, value] of Object.entries(authority)) {
    if (value !== undefined) {
      result[key] = value;
    }
  }
  return result;
}

export function mapDiscovery(discovery: Discovery): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  if (discovery.verification_status !== undefined)
    result["verification_status"] = discovery.verification_status;
  if (discovery.source !== undefined) result["source"] = discovery.source;
  if (discovery.observed_at !== undefined) result["observed_at"] = discovery.observed_at;
  if (discovery.verified_at !== undefined) result["verified_at"] = discovery.verified_at;
  if (discovery.confidence !== undefined) result["confidence"] = discovery.confidence;
  if (discovery.contradicted_by !== undefined)
    result["contradicted_by"] = discovery.contradicted_by;
  return result;
}

export function mapVisibility(visibility: Visibility): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  if (visibility.default !== undefined) result["default"] = visibility.default;
  if (visibility.conditions !== undefined && visibility.conditions.length > 0) {
    result["conditions"] = visibility.conditions.map((c) => {
      const when: Record<string, unknown> = {};
      if (c.when.environment !== undefined) when["environment"] = c.when.environment;
      if (c.when.agent_role !== undefined) when["agent_role"] = c.when.agent_role;
      const then: Record<string, unknown> = {};
      if (c.then.sensitivity !== undefined) then["sensitivity"] = c.then.sensitivity;
      if (c.then.requires_auth !== undefined) then["requires_auth"] = c.then.requires_auth;
      if (c.then.authority !== undefined) then["authority"] = mapAuthority(c.then.authority);
      return { when, then };
    });
  }
  return result;
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
      if (u.hints) entry["hints"] = u.hints;
      if (u.access) entry["access"] = u.access;
      if (u.auth_scope) entry["auth_scope"] = u.auth_scope;
      if (u.sensitivity) entry["sensitivity"] = u.sensitivity;
      if (u.deprecated) entry["deprecated"] = u.deprecated;
      if (u.payment) entry["payment"] = u.payment;
      if (u.delegation) {
        const d: Record<string, unknown> = {};
        if (u.delegation.max_depth !== undefined) d["max_depth"] = u.delegation.max_depth;
        if (u.delegation.human_in_the_loop !== undefined) d["human_in_the_loop"] = u.delegation.human_in_the_loop;
        if (u.delegation.require_capability_attenuation !== undefined) d["require_capability_attenuation"] = u.delegation.require_capability_attenuation;
        if (u.delegation.audit_chain !== undefined) d["audit_chain"] = u.delegation.audit_chain;
        if (Object.keys(d).length > 0) entry["delegation"] = d;
      }
      if (u.compliance) {
        const c: Record<string, unknown> = {};
        if (u.compliance.data_residency?.length) c["data_residency"] = u.compliance.data_residency;
        if (u.compliance.sensitivity) c["sensitivity"] = u.compliance.sensitivity;
        if (u.compliance.regulations?.length) c["regulations"] = u.compliance.regulations;
        if (u.compliance.restrictions?.length) c["restrictions"] = u.compliance.restrictions;
        if (Object.keys(c).length > 0) entry["compliance"] = c;
      }
      if (u.authority) entry["authority"] = mapAuthority(u.authority);
      if (u.discovery) entry["discovery"] = mapDiscovery(u.discovery);
      if (u.visibility) entry["visibility"] = mapVisibility(u.visibility);
      return entry;
    }),
    relationships: manifest.relationships.map((r) => ({
      from: r.from_id,
      to: r.to_id,
      type: r.type,
    })),
    ...(manifest.manifests.length > 0
      ? {
          manifests: manifest.manifests.map((m) => ({
            id: m.id,
            url: m.url,
            ...(m.label ? { label: m.label } : {}),
            ...(m.relationship ? { relationship: m.relationship } : {}),
            ...(m.update_frequency ? { update_frequency: m.update_frequency } : {}),
            ...(m.local_mirror ? { local_mirror: m.local_mirror } : {}),
          })),
        }
      : {}),
    ...(manifest.external_relationships.length > 0
      ? {
          external_relationships: manifest.external_relationships.map((er) => ({
            ...(er.from_manifest ? { from_manifest: er.from_manifest } : {}),
            from_unit: er.from_unit,
            ...(er.to_manifest ? { to_manifest: er.to_manifest } : {}),
            to_unit: er.to_unit,
            type: er.type,
          })),
        }
      : {}),
    ...(manifest.hints ? { hints: manifest.hints } : {}),
    ...(manifest.trust ? { trust: manifest.trust } : {}),
    ...(manifest.auth ? { auth: manifest.auth } : {}),
    ...(manifest.delegation ? { delegation: manifest.delegation } : {}),
    ...(manifest.compliance ? { compliance: manifest.compliance } : {}),
    ...(manifest.payment ? { payment: manifest.payment } : {}),
    ...(manifest.authority ? { authority: mapAuthority(manifest.authority) } : {}),
    ...(manifest.discovery ? { discovery: mapDiscovery(manifest.discovery) } : {}),
    ...(manifest.visibility ? { visibility: mapVisibility(manifest.visibility) } : {}),
  };
  return JSON.stringify(payload, null, 2);
}
