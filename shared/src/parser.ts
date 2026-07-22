// KCP YAML parser — TypeScript implementation
// Mirrors the Python parse()/parse_dict() and Java KcpParser.parse()/fromMap() APIs

import { readFileSync } from "node:fs";
import yaml from "js-yaml";
import type {
  ActionScope,
  Auth,
  AuthMethod,
  Authority,
  Compliance,
  ContentHash,
  ContentStructure,
  Delegation,
  Discovery,
  Temporal,
  ExternalDependency,
  ExternalRelationship,
  FreshnessPolicy,
  KnowledgeManifest,
  KnowledgeUnit,
  ManifestRef,
  AgentIdentity,
  RateLimits,
  RateLimitCount,
  RateLimitsDefault,
  RateLimitTokensTier,
  Payment,
  Serving,
  PaymentMethod,
  Relationship,
  Trust,
  TrustAudit,
  TrustAgentRequirements,
  TrustProvenance,
  Visibility,
  VisibilityCondition,
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
    aliases: stringListOrUndefined(raw["aliases"]),
    path: validateUnitPath(String(raw["path"] ?? "")),
    intent: String(raw["intent"] ?? ""),
    scope: String(raw["scope"] ?? "global"),
    audience: asStringArray(raw["audience"]),
    kind: raw["kind"] !== undefined ? String(raw["kind"]) : undefined,
    action_scope: parseActionScope(raw["action_scope"]),
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
    payment: parsePayment(raw["payment"]),
    rate_limits: parseRateLimits(raw["rate_limits"]),
    delegation: parseDelegation(raw["delegation"]),
    compliance: parseCompliance(raw["compliance"]),
    auth: parseAuth(raw["auth"]),
    external_depends_on: ((raw["external_depends_on"] as RawMap[]) ?? []).map(
      parseExternalDependency
    ),
    requires_capabilities: raw["requires_capabilities"] !== undefined
      ? (raw["requires_capabilities"] as string[])
      : undefined,
    freshness_policy: parseFreshnessPolicy(raw["freshness_policy"]),
    visibility: parseVisibility(raw["visibility"]),
    authority: parseAuthority(raw["authority"]),
    discovery: parseDiscovery(raw["discovery"]),
    not_for: raw["not_for"] !== undefined ? asStringArray(raw["not_for"]) : undefined,
    not_for_strict:
      raw["not_for_strict"] !== undefined ? Boolean(raw["not_for_strict"]) : undefined,
    content_structure: parseContentStructure(raw["content_structure"]),
    content_hash: parseContentHash(raw["content_hash"]),
    temporal: parseTemporal(raw["temporal"]),
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
      publisher_did: provData["publisher_did"] !== undefined ? String(provData["publisher_did"]) : undefined,
    };
  }

  let audit: TrustAudit | undefined;
  const auditData = data["audit"] as RawMap | undefined;
  if (auditData && typeof auditData === "object") {
    audit = {
      agent_must_log: auditData["agent_must_log"] !== undefined ? Boolean(auditData["agent_must_log"]) : undefined,
      require_trace_context: auditData["require_trace_context"] !== undefined ? Boolean(auditData["require_trace_context"]) : undefined,
      provides_access_receipts: auditData["provides_access_receipts"] !== undefined ? Boolean(auditData["provides_access_receipts"]) : undefined,
      receipt_format: auditData["receipt_format"] !== undefined ? String(auditData["receipt_format"]) : undefined,
    };
  }

  let agent_requirements: TrustAgentRequirements | undefined;
  const arData = data["agent_requirements"] as RawMap | undefined;
  if (arData && typeof arData === "object") {
    agent_requirements = {
      require_attestation: arData["require_attestation"] !== undefined ? Boolean(arData["require_attestation"]) : undefined,
      trusted_providers: asStringArray(arData["trusted_providers"]),
      attestation_url: arData["attestation_url"] !== undefined ? String(arData["attestation_url"]) : undefined,
      attestation_jwks: arData["attestation_jwks"] !== undefined ? String(arData["attestation_jwks"]) : undefined,
      propagate_to_governed: arData["propagate_to_governed"] !== undefined ? Boolean(arData["propagate_to_governed"]) : undefined,
    };
  }

  return { provenance, audit, agent_requirements };
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
    trust_domain: m["trust_domain"] !== undefined ? String(m["trust_domain"]) : undefined,
    supported_methods: asStringArray(m["supported_methods"]),
    key_id: m["key_id"] !== undefined ? String(m["key_id"]) : undefined,
    algorithm: m["algorithm"] !== undefined ? String(m["algorithm"]) : undefined,
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
    require_delegation_proof:
      data["require_delegation_proof"] !== undefined
        ? Boolean(data["require_delegation_proof"])
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

// A rate-limit count is a positive integer or the sentinel "unlimited" (v0.25).
function parseRateLimitCount(v: unknown): RateLimitCount | undefined {
  if (v === undefined || v === null) return undefined;
  if (typeof v === "string" && v === "unlimited") return "unlimited";
  return Number(v);
}

function parseRateLimitTier(raw: unknown): RateLimitsDefault | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const d = raw as RawMap;
  return {
    requests_per_minute: parseRateLimitCount(d["requests_per_minute"]),
    requests_per_hour: parseRateLimitCount(d["requests_per_hour"]),
    requests_per_day: parseRateLimitCount(d["requests_per_day"]),
  };
}

function parseRateLimitTokensTier(raw: unknown): RateLimitTokensTier | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const d = raw as RawMap;
  return {
    tokens_per_minute: parseRateLimitCount(d["tokens_per_minute"]),
    tokens_per_day: parseRateLimitCount(d["tokens_per_day"]),
  };
}

function parseRateLimits(raw: unknown): RateLimits | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const data = raw as RawMap;
  const result: RateLimits = {};
  const def = parseRateLimitTier(data["default"]);
  if (def) result.default = def;
  const authn = parseRateLimitTier(data["authenticated"]);
  if (authn) result.authenticated = authn;
  const prem = parseRateLimitTier(data["premium"]);
  if (prem) result.premium = prem;
  const tk = data["tokens"];
  if (tk && typeof tk === "object" && !Array.isArray(tk)) {
    const t = tk as RawMap;
    result.tokens = {
      default: parseRateLimitTokensTier(t["default"]),
      authenticated: parseRateLimitTokensTier(t["authenticated"]),
      premium: parseRateLimitTokensTier(t["premium"]),
    };
  }
  const hd = data["headers"];
  if (hd && typeof hd === "object" && !Array.isArray(hd)) {
    const h = hd as RawMap;
    result.headers = {
      remaining: h["remaining"] !== undefined ? String(h["remaining"]) : undefined,
      reset: h["reset"] !== undefined ? String(h["reset"]) : undefined,
      retry_after: h["retry_after"] !== undefined ? String(h["retry_after"]) : undefined,
    };
  }
  if (data["backoff"] !== undefined) result.backoff = String(data["backoff"]);
  return result;
}

function parsePaymentMethod(raw: unknown): PaymentMethod {
  const d = (raw && typeof raw === "object" && !Array.isArray(raw) ? raw : {}) as RawMap;
  return {
    type: String(d["type"] ?? ""),
    currency: d["currency"] !== undefined ? String(d["currency"]) : undefined,
    price_per_request: d["price_per_request"] !== undefined ? String(d["price_per_request"]) : undefined,
    networks: Array.isArray(d["networks"]) ? (d["networks"] as unknown[]).map(String) : undefined,
    wallet: d["wallet"] !== undefined ? String(d["wallet"]) : undefined,
    provider: d["provider"] !== undefined ? String(d["provider"]) : undefined,
    plans_url: d["plans_url"] !== undefined ? String(d["plans_url"]) : undefined,
    free_tier: d["free_tier"] !== undefined ? Boolean(d["free_tier"]) : undefined,
    free_requests_per_day: d["free_requests_per_day"] !== undefined ? Number(d["free_requests_per_day"]) : undefined,
    upgrade_url: d["upgrade_url"] !== undefined ? String(d["upgrade_url"]) : undefined,
  };
}

function parsePayment(raw: unknown): Payment | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const d = raw as RawMap;
  return {
    default_tier: d["default_tier"] !== undefined ? String(d["default_tier"]) : undefined,
    methods: Array.isArray(d["methods"]) ? (d["methods"] as unknown[]).map(parsePaymentMethod) : undefined,
    billing_contact: d["billing_contact"] !== undefined ? String(d["billing_contact"]) : undefined,
  };
}

/**
 * v0.26: coerce a raw value into a string list for `aliases` / `serving`. A non-list is
 * treated as *absent* (undefined) and non-string entries are dropped — so the TypeScript,
 * Python, and Java parsers agree byte-for-byte on malformed input instead of one coercing a
 * scalar to a one-element list while another drops it (a cross-impl divergence that would let
 * the same signed bytes resolve to different trust decisions). Structural validity (must be a
 * list of strings) is the JSON schema's job; this keeps the runtime parsers in lockstep.
 */
function stringListOrUndefined(raw: unknown): string[] | undefined {
  return Array.isArray(raw)
    ? (raw as unknown[]).filter((x): x is string => typeof x === "string")
    : undefined;
}

function parseServing(raw: unknown): Serving | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const d = raw as RawMap;
  return {
    manifest: stringListOrUndefined(d["manifest"]),
    mcp: stringListOrUndefined(d["mcp"]),
  };
}

/** §4.3a (v0.26): the tools/paths/capabilities a `kind: skill` procedure may touch. */
function parseActionScope(raw: unknown): ActionScope | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const d = raw as RawMap;
  return {
    tools: stringListOrUndefined(d["tools"]),
    paths: stringListOrUndefined(d["paths"]),
    capabilities: stringListOrUndefined(d["capabilities"]),
  };
}

function parseFreshnessPolicy(raw: unknown): FreshnessPolicy | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const d = raw as RawMap;
  return {
    max_age_days: d["max_age_days"] !== undefined ? Number(d["max_age_days"]) : undefined,
    on_stale: d["on_stale"] !== undefined ? String(d["on_stale"]) : undefined,
    review_contact: d["review_contact"] !== undefined ? String(d["review_contact"]) : undefined,
  };
}

function parseAuthority(raw: unknown): Authority | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const d = raw as RawMap;
  const result: Authority = {};
  for (const key of Object.keys(d)) {
    if (d[key] !== undefined) {
      result[key] = String(d[key]);
    }
  }
  return result;
}

function parseVisibility(raw: unknown): Visibility | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const d = raw as RawMap;
  const conditions: VisibilityCondition[] | undefined = (() => {
    const rawConds = d["conditions"];
    if (!Array.isArray(rawConds)) return undefined;
    return (rawConds as RawMap[]).map((c) => {
      const when = c["when"] as RawMap | undefined ?? {};
      const then = c["then"] as RawMap | undefined ?? {};
      const envRaw = when["environment"];
      const roleRaw = when["agent_role"];
      return {
        when: {
          environment: envRaw !== undefined
            ? (Array.isArray(envRaw) ? envRaw.map(String) : String(envRaw))
            : undefined,
          agent_role: roleRaw !== undefined
            ? (Array.isArray(roleRaw) ? roleRaw.map(String) : String(roleRaw))
            : undefined,
        },
        then: {
          sensitivity: then["sensitivity"] !== undefined ? String(then["sensitivity"]) : undefined,
          requires_auth: then["requires_auth"] !== undefined ? Boolean(then["requires_auth"]) : undefined,
          authority: parseAuthority(then["authority"]),
        },
      };
    });
  })();
  return {
    default: d["default"] !== undefined ? String(d["default"]) : undefined,
    conditions,
  };
}

function parseDiscovery(raw: unknown): Discovery | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const d = raw as RawMap;
  return {
    verification_status: d["verification_status"] !== undefined ? String(d["verification_status"]) : undefined,
    source: d["source"] !== undefined ? String(d["source"]) : undefined,
    observed_at: d["observed_at"] !== undefined ? String(d["observed_at"]) : undefined,
    verified_at: d["verified_at"] !== undefined ? String(d["verified_at"]) : undefined,
    verified_by: d["verified_by"] !== undefined ? String(d["verified_by"]) : undefined,
    evidence: d["evidence"] !== undefined ? String(d["evidence"]) : undefined,
    confidence: d["confidence"] !== undefined ? Number(d["confidence"]) : undefined,
    contradicted_by: d["contradicted_by"] !== undefined ? String(d["contradicted_by"]) : undefined,
  };
}

// --- Temporal parsing (RFC-0010 §4.22 v0.19; RFC-0021 §3.6 v0.21) ---

function parseTemporal(raw: unknown): Temporal | undefined {
  if (raw === undefined || raw === null) return undefined;
  if (typeof raw !== "object" || Array.isArray(raw)) return {};
  const t = raw as RawMap;
  return {
    valid_from: t["valid_from"] !== undefined && t["valid_from"] !== null ? String(t["valid_from"]) : undefined,
    valid_until: t["valid_until"] !== undefined && t["valid_until"] !== null ? String(t["valid_until"]) : undefined,
    recorded_at: t["recorded_at"] !== undefined && t["recorded_at"] !== null ? String(t["recorded_at"]) : undefined,
    superseded_by: t["superseded_by"] !== undefined && t["superseded_by"] !== null ? String(t["superseded_by"]) : undefined,
  };
}

// --- Content structure parsing (RFC-0016, v0.17) ---

function parseContentStructure(raw: unknown): ContentStructure | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const c = raw as RawMap;
  return {
    primary: c["primary"] !== undefined ? String(c["primary"]) : undefined,
    contains: c["contains"] !== undefined ? asStringArray(c["contains"]) : undefined,
    density: c["density"] !== undefined ? String(c["density"]) : undefined,
  };
}

// --- Content hash parsing (RFC-0019 §3, draft) ---
// A declared-but-malformed block parses to {} so the validator can flag
// it; only an absent block parses to undefined.

function parseContentHash(raw: unknown): ContentHash | undefined {
  if (raw === undefined || raw === null) return undefined;
  if (typeof raw !== "object" || Array.isArray(raw)) return {};
  const c = raw as RawMap;
  return {
    algorithm: c["algorithm"] !== undefined ? String(c["algorithm"]) : undefined,
    value: c["value"] !== undefined ? String(c["value"]) : undefined,
  };
}

// --- Federation parsing (§3.6) ---

function parseExternalDependency(raw: RawMap): ExternalDependency {
  return {
    manifest: String(raw["manifest"] ?? ""),
    unit: String(raw["unit"] ?? ""),
    on_failure: raw["on_failure"] !== undefined ? String(raw["on_failure"]) : "skip",
  };
}

function parseManifestRef(raw: RawMap): ManifestRef {
  return {
    id: String(raw["id"] ?? ""),
    url: String(raw["url"] ?? ""),
    label: raw["label"] !== undefined ? String(raw["label"]) : undefined,
    relationship: raw["relationship"] !== undefined ? String(raw["relationship"]) : undefined,
    auth: parseAuth(raw["auth"]),
    update_frequency: raw["update_frequency"] !== undefined ? String(raw["update_frequency"]) : undefined,
    local_mirror: raw["local_mirror"] !== undefined ? String(raw["local_mirror"]) : undefined,
    version_pin: raw["version_pin"] !== undefined ? String(raw["version_pin"]) : undefined,
    version_policy: raw["version_policy"] !== undefined ? String(raw["version_policy"]) : undefined,
    temporal: parseTemporal(raw["temporal"]),
    context: Array.isArray(raw["context"]) ? (raw["context"] as unknown[]).map(String) : undefined,
    agent_identity: parseAgentIdentity(raw["agent_identity"]),
  };
}

function parseAgentIdentity(raw: unknown): AgentIdentity | undefined {
  if (raw === null || typeof raw !== "object") return undefined;
  const r = raw as RawMap;
  return {
    required: r["required"] !== undefined ? Boolean(r["required"]) : undefined,
    credential_hint: r["credential_hint"] !== undefined ? String(r["credential_hint"]) : undefined,
    issuer_hint: r["issuer_hint"] !== undefined ? String(r["issuer_hint"]) : undefined,
    docs_url: r["docs_url"] !== undefined ? String(r["docs_url"]) : undefined,
  };
}

function parseExternalRelationship(raw: RawMap): ExternalRelationship {
  return {
    from_manifest: raw["from_manifest"] !== undefined ? String(raw["from_manifest"]) : undefined,
    from_unit: String(raw["from_unit"] ?? ""),
    to_manifest: raw["to_manifest"] !== undefined ? String(raw["to_manifest"]) : undefined,
    to_unit: String(raw["to_unit"] ?? ""),
    type: String(raw["type"] ?? ""),
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
  const rawManifests = (data["manifests"] as RawMap[]) ?? [];
  const rawExtRels = (data["external_relationships"] as RawMap[]) ?? [];

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
    payment: parsePayment(data["payment"]),
    rate_limits: parseRateLimits(data["rate_limits"]),
    serving: parseServing(data["serving"]),
    units: rawUnits.map(parseUnit),
    relationships: rawRels.map(parseRelationship),
    manifests: rawManifests.map(parseManifestRef),
    external_relationships: rawExtRels.map(parseExternalRelationship),
    freshness_policy: parseFreshnessPolicy(data["freshness_policy"]),
    visibility: parseVisibility(data["visibility"]),
    authority: parseAuthority(data["authority"]),
    discovery: parseDiscovery(data["discovery"]),
    not_for: data["not_for"] !== undefined ? asStringArray(data["not_for"]) : undefined,
    temporal: parseTemporal(data["temporal"]),
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
