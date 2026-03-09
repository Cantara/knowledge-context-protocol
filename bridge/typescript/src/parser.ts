// KCP YAML parser — TypeScript implementation
// Mirrors the Python parse()/parse_dict() and Java KcpParser.parse()/fromMap() APIs

import { readFileSync } from "node:fs";
import yaml from "js-yaml";
import type {
  Auth,
  AuthMethod,
  Compliance,
  Delegation,
  KnowledgeManifest,
  KnowledgeUnit,
  RateLimits,
  Relationship,
  Trust,
  TrustAudit,
  TrustProvenance,
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
    hints:
      raw["hints"] !== undefined && typeof raw["hints"] === "object" && !Array.isArray(raw["hints"])
        ? (raw["hints"] as Record<string, unknown>)
        : undefined,
    access:
      raw["access"] !== undefined ? String(raw["access"]) : undefined,
    auth_scope:
      raw["auth_scope"] !== undefined ? String(raw["auth_scope"]) : undefined,
    sensitivity:
      raw["sensitivity"] !== undefined ? String(raw["sensitivity"]) : undefined,
    deprecated:
      raw["deprecated"] !== undefined ? Boolean(raw["deprecated"]) : undefined,
    payment:
      raw["payment"] !== undefined && typeof raw["payment"] === "object" && !Array.isArray(raw["payment"])
        ? (raw["payment"] as Record<string, unknown>)
        : undefined,
    rate_limits: parseRateLimits(raw["rate_limits"]),
    delegation: parseDelegation(raw["delegation"]),
    compliance: parseCompliance(raw["compliance"]),
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

// --- Trust and Auth parsing ---

function parseTrust(raw: unknown): Trust | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const data = raw as RawMap;

  let provenance: TrustProvenance | undefined;
  const provData = data["provenance"] as RawMap | undefined;
  if (provData && typeof provData === "object") {
    provenance = {
      publisher: provData["publisher"] !== undefined ? String(provData["publisher"]) : undefined,
      publisher_url: provData["publisher_url"] !== undefined ? String(provData["publisher_url"]) : undefined,
      contact: provData["contact"] !== undefined ? String(provData["contact"]) : undefined,
    };
  }

  let audit: TrustAudit | undefined;
  const auditData = data["audit"] as RawMap | undefined;
  if (auditData && typeof auditData === "object") {
    audit = {
      agent_must_log: auditData["agent_must_log"] !== undefined ? Boolean(auditData["agent_must_log"]) : undefined,
      require_trace_context: auditData["require_trace_context"] !== undefined ? Boolean(auditData["require_trace_context"]) : undefined,
    };
  }

  return { provenance, audit };
}

function parseAuth(raw: unknown): Auth | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const data = raw as RawMap;
  const rawMethods = (data["methods"] as RawMap[]) ?? [];
  const methods: AuthMethod[] = rawMethods.map((m) => ({
    type: String(m["type"] ?? ""),
    issuer: m["issuer"] !== undefined ? String(m["issuer"]) : undefined,
    scopes: asStringArray(m["scopes"]),
    header: m["header"] !== undefined ? String(m["header"]) : undefined,
    registration_url: m["registration_url"] !== undefined ? String(m["registration_url"]) : undefined,
  }));
  return { methods };
}

function parseDelegation(raw: unknown): Delegation | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const data = raw as RawMap;
  return {
    max_depth: data["max_depth"] !== undefined ? Number(data["max_depth"]) : undefined,
    require_capability_attenuation:
      data["require_capability_attenuation"] !== undefined
        ? Boolean(data["require_capability_attenuation"])
        : undefined,
    audit_chain:
      data["audit_chain"] !== undefined ? Boolean(data["audit_chain"]) : undefined,
    human_in_the_loop: (() => {
      const raw = data["human_in_the_loop"];
      if (raw === undefined || raw === null) return undefined;
      if (typeof raw === "object" && !Array.isArray(raw)) {
        const h = raw as Record<string, unknown>;
        return {
          required: h["required"] !== undefined ? Boolean(h["required"]) : undefined,
          approval_mechanism: h["approval_mechanism"] !== undefined ? String(h["approval_mechanism"]) : undefined,
          docs_url: h["docs_url"] !== undefined ? String(h["docs_url"]) : undefined,
        };
      }
      return undefined;
    })(),
  };
}

function parseCompliance(raw: unknown): Compliance | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const data = raw as RawMap;
  return {
    data_residency:
      data["data_residency"] !== undefined
        ? asStringArray(data["data_residency"])
        : undefined,
    sensitivity:
      data["sensitivity"] !== undefined ? String(data["sensitivity"]) : undefined,
    regulations:
      data["regulations"] !== undefined
        ? asStringArray(data["regulations"])
        : undefined,
    restrictions:
      data["restrictions"] !== undefined
        ? asStringArray(data["restrictions"])
        : undefined,
  };
}

function parseRateLimits(raw: unknown): RateLimits | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const data = raw as RawMap;
  const def = data["default"];
  if (!def || typeof def !== "object" || Array.isArray(def)) return {};
  const d = def as RawMap;
  return {
    default: {
      requests_per_minute: d["requests_per_minute"] !== undefined ? Number(d["requests_per_minute"]) : undefined,
      requests_per_day: d["requests_per_day"] !== undefined ? Number(d["requests_per_day"]) : undefined,
    },
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
    hints:
      data["hints"] !== undefined && typeof data["hints"] === "object" && !Array.isArray(data["hints"])
        ? (data["hints"] as Record<string, unknown>)
        : undefined,
    trust: parseTrust(data["trust"]),
    auth: parseAuth(data["auth"]),
    delegation: parseDelegation(data["delegation"]),
    compliance: parseCompliance(data["compliance"]),
    payment:
      data["payment"] !== undefined && typeof data["payment"] === "object" && !Array.isArray(data["payment"])
        ? (data["payment"] as Record<string, unknown>)
        : undefined,
    rate_limits: parseRateLimits(data["rate_limits"]),
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
