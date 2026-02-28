// KCP YAML parser — TypeScript implementation
// Mirrors the Python parse()/parse_dict() and Java KcpParser.parse()/fromMap() APIs

import { readFileSync } from "node:fs";
import yaml from "js-yaml";
import type {
  KnowledgeManifest,
  KnowledgeUnit,
  Relationship,
  LicenseValue,
  IndexingValue,
} from "./model.js";

// --- Path safety (SPEC §12) ---

export function validateUnitPath(raw: string): string {
  if (!raw) return raw;
  if (raw.startsWith("/") || raw.startsWith("\\")) {
    throw new Error(`Unit path must be relative: "${raw}"`);
  }
  const segments = raw.replace(/\\/g, "/").split("/");
  const resolved: string[] = [];
  for (const seg of segments) {
    if (seg === "..") {
      if (resolved.length === 0) {
        throw new Error(`Unit path escapes manifest root: "${raw}"`);
      }
      resolved.pop();
    } else if (seg !== "." && seg !== "") {
      resolved.push(seg);
    }
  }
  if (resolved.length === 0 || resolved[0] === "..") {
    throw new Error(`Unit path escapes manifest root: "${raw}"`);
  }
  return raw;
}

// --- Date normalization ---

function toDateString(value: unknown): string | undefined {
  if (value === null || value === undefined) return undefined;
  if (value instanceof Date) {
    return value.toISOString().slice(0, 10);
  }
  const s = String(value);
  if (/^\d{4}-\d{2}-\d{2}/.test(s)) return s.slice(0, 10);
  return s;
}

// --- Raw YAML type helpers ---

type RawMap = Record<string, unknown>;

function asStringArray(value: unknown): string[] {
  if (!value) return [];
  if (Array.isArray(value)) return value.map(String);
  return [String(value)];
}

function asLicenseOrIndexing(value: unknown): LicenseValue | undefined {
  if (value === null || value === undefined) return undefined;
  if (typeof value === "string") return value;
  if (typeof value === "object" && !Array.isArray(value))
    return value as Record<string, unknown>;
  return String(value);
}

// --- Parse a raw YAML map into a KnowledgeUnit ---

function parseUnit(raw: RawMap): KnowledgeUnit {
  return {
    id: String(raw["id"] ?? ""),
    path: validateUnitPath(String(raw["path"] ?? "")),
    intent: String(raw["intent"] ?? ""),
    scope: String(raw["scope"] ?? "global"),
    audience: asStringArray(raw["audience"]),
    kind: raw["kind"] !== undefined ? String(raw["kind"]) : undefined,
    format: raw["format"] !== undefined ? String(raw["format"]) : undefined,
    content_type:
      raw["content_type"] !== undefined
        ? String(raw["content_type"])
        : undefined,
    language:
      raw["language"] !== undefined ? String(raw["language"]) : undefined,
    license: asLicenseOrIndexing(raw["license"]),
    validated: toDateString(raw["validated"]),
    update_frequency:
      raw["update_frequency"] !== undefined
        ? String(raw["update_frequency"])
        : undefined,
    indexing: asLicenseOrIndexing(raw["indexing"]),
    depends_on: asStringArray(raw["depends_on"]),
    supersedes:
      raw["supersedes"] !== undefined ? String(raw["supersedes"]) : undefined,
    triggers: asStringArray(raw["triggers"]),
  };
}

// --- Parse a raw YAML map into a Relationship ---

function parseRelationship(raw: RawMap): Relationship {
  return {
    from_id: String(raw["from"] ?? ""),
    to_id: String(raw["to"] ?? ""),
    type: String(raw["type"] ?? "context"),
  };
}

// --- Public API ---

/**
 * Parse a plain JavaScript object (from YAML.load output) into a KnowledgeManifest.
 * Mirrors Python's parse_dict() and Java's KcpParser.fromMap().
 */
export function parseDict(data: RawMap): KnowledgeManifest {
  const rawUnits = (data["units"] as RawMap[]) ?? [];
  const rawRels = (data["relationships"] as RawMap[]) ?? [];

  return {
    project: String(data["project"] ?? ""),
    version: String(data["version"] ?? ""),
    kcp_version:
      data["kcp_version"] !== undefined
        ? String(data["kcp_version"])
        : undefined,
    updated: toDateString(data["updated"]),
    language:
      data["language"] !== undefined ? String(data["language"]) : undefined,
    license: asLicenseOrIndexing(data["license"]),
    indexing: asLicenseOrIndexing(data["indexing"]),
    units: rawUnits.map(parseUnit),
    relationships: rawRels.map(parseRelationship),
  };
}

/**
 * Parse a knowledge.yaml file from disk.
 * Mirrors Python's parse(path) and Java's KcpParser.parse(Path).
 *
 * Uses YAML safe load — no arbitrary type instantiation (SPEC §12).
 */
export function parseFile(filePath: string): KnowledgeManifest {
  const raw = readFileSync(filePath, "utf-8");
  const data = yaml.load(raw, { schema: yaml.DEFAULT_SCHEMA });
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    throw new Error(`Invalid YAML structure in: ${filePath}`);
  }
  return parseDict(data as RawMap);
}
