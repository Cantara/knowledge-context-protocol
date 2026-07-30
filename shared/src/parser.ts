// KCP YAML parser — TypeScript implementation
// Mirrors the Python parse()/parse_dict() and Java KcpParser.parse()/fromMap() APIs

import { readFileSync } from "node:fs";
import yaml from "js-yaml";
import type {
  ActionScope,
  ForbidScope,
  PlaybookStep,
  Spend,
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
  TaskType,
  Agent,
  GrantCeiling,
  GrantCeilingSource,
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

/**
 * Coerce a YAML scalar to a boolean. Only a real boolean is one.
 *
 * js-yaml implements YAML 1.2, which SPEC.md §2 mandates, so `yes`/`no`/`on`/`off`
 * arrive here as strings. They are rejected exactly as a typo is: the JSON schema types
 * these fields as `boolean`, so a string is a schema violation rather than a shorthand
 * to be rescued.
 *
 * Two earlier revisions got this wrong in opposite directions. `Boolean()` accepted every
 * non-empty string, so every negative and every typo read as `true` (#151). The fix then
 * mapped the YAML 1.1 words to booleans, which made the three parsers agree — on an
 * answer the schema rejects (#156). The Python and Java parsers now resolve booleans per
 * YAML 1.2 at the loader, so all three see the same strings and reject them alike.
 *
 * `undefined` means the field reads as *undeclared*, so the unit falls back to its
 * documented default rather than silently switching a flag on. That a rejected value is
 * silent rather than reported is a known gap: the parse layer has no diagnostics channel.
 */
function asBool(value: unknown): boolean | undefined {
  return typeof value === "boolean" ? value : undefined;
}

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

/** Fields a unit may declare. Used only to spot near misses — see nearMiss below. */
const KNOWN_UNIT_FIELDS = new Set([
  "id", "aliases", "path", "intent", "scope", "audience", "kind", "action_scope",
  "steps", "load_eligible", "format", "content_type", "language", "license",
  "validated", "update_frequency", "indexing", "depends_on", "supersedes", "triggers",
  "hints", "access", "auth_scope", "sensitivity", "deprecated", "payment",
  "rate_limits", "delegation", "compliance", "auth", "external_depends_on",
  "requires_capabilities", "freshness_policy", "visibility", "authority", "discovery",
  "not_for", "not_for_strict", "content_structure", "content_hash", "temporal",
  "authority_level", "size_tokens", "bytes",
]);

/** Levenshtein distance, capped — we only care whether it is 1 or 2. */
function editDistance(a: string, b: string): number {
  if (Math.abs(a.length - b.length) > 2) return 99;
  const prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  const cur = new Array<number>(b.length + 1);
  for (let i = 1; i <= a.length; i++) {
    cur[0] = i;
    for (let j = 1; j <= b.length; j++) {
      cur[j] = Math.min(
        prev[j]! + 1,
        cur[j - 1]! + 1,
        prev[j - 1]! + (a[i - 1] === b[j - 1] ? 0 : 1),
      );
    }
    for (let j = 0; j <= b.length; j++) prev[j] = cur[j]!;
  }
  return prev[b.length]!;
}

/**
 * The known field an unrecognised key is probably a typo of, or undefined.
 *
 * §2 requires parsers to silently *ignore* unknown fields, which is what lets a v0.31
 * manifest be read by a v0.30 parser. That rule is kept: nothing here changes what is
 * parsed. But a key one or two edits from a known field is far more likely a typo than
 * a field from the future, and saying so costs nothing. A key that resembles nothing
 * stays unmentioned — that is the forward-compatibility case.
 */
function nearMiss(key: string): string | undefined {
  if (KNOWN_UNIT_FIELDS.has(key)) return undefined;
  let best: string | undefined;
  let bestD = 3;
  for (const known of KNOWN_UNIT_FIELDS) {
    const d = editDistance(key, known);
    if (d < bestD) { bestD = d; best = known; }
  }
  return bestD <= 2 ? best : undefined;
}

function parseUnit(raw: RawMap, diagnostics?: string[]): KnowledgeUnit {
  if (diagnostics) {
    const id = raw["id"] !== undefined ? String(raw["id"]) : "(no id)";
    for (const key of Object.keys(raw)) {
      const suggestion = nearMiss(key);
      if (suggestion) {
        diagnostics.push(`unit '${id}': unknown field '${key}' — did you mean '${suggestion}'? (ignored per §2)`);
      }
    }
    // §2.1: a value that is not a boolean is dropped, so the author sees a field
    // that reads as never-written. Report it where the information still exists.
    for (const f of ["deprecated", "not_for_strict", "load_eligible"]) {
      const v = raw[f];
      if (v !== undefined && typeof v !== "boolean") {
        diagnostics.push(`unit '${id}': '${f}' is ${JSON.stringify(v)}, which is not a boolean — YAML 1.2 requires true/false (§2.1); the field reads as undeclared`);
      }
    }
  }
  return {
    id: String(raw["id"] ?? ""),
    aliases: stringListOrUndefined(raw["aliases"]),
    path: validateUnitPath(String(raw["path"] ?? "")),
    intent: String(raw["intent"] ?? ""),
    scope: String(raw["scope"] ?? "global"),
    audience: asStringArray(raw["audience"]),
    kind: raw["kind"] !== undefined ? String(raw["kind"]) : undefined,
    action_scope: parseActionScope(raw["action_scope"]),
    steps: parseSteps(raw["steps"]),
    load_eligible: asBool(raw["load_eligible"]),
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
      asBool(raw["deprecated"]),
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
      asBool(raw["not_for_strict"]),
    content_structure: parseContentStructure(raw["content_structure"]),
    content_hash: parseContentHash(raw["content_hash"]),
    temporal: parseTemporal(raw["temporal"]),
    authority_level:
      raw["authority_level"] !== undefined ? String(raw["authority_level"]) : undefined,
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
      agent_must_log: asBool(auditData["agent_must_log"]),
      require_trace_context: asBool(auditData["require_trace_context"]),
      provides_access_receipts: asBool(auditData["provides_access_receipts"]),
      receipt_format: auditData["receipt_format"] !== undefined ? String(auditData["receipt_format"]) : undefined,
    };
  }

  let agent_requirements: TrustAgentRequirements | undefined;
  const arData = data["agent_requirements"] as RawMap | undefined;
  if (arData && typeof arData === "object") {
    agent_requirements = {
      require_attestation: asBool(arData["require_attestation"]),
      trusted_providers: asStringArray(arData["trusted_providers"]),
      attestation_url: arData["attestation_url"] !== undefined ? String(arData["attestation_url"]) : undefined,
      attestation_jwks: arData["attestation_jwks"] !== undefined ? String(arData["attestation_jwks"]) : undefined,
      propagate_to_governed: asBool(arData["propagate_to_governed"]),
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
      asBool(data["require_capability_attenuation"]),
    require_delegation_proof:
      asBool(data["require_delegation_proof"]),
    audit_chain:
      asBool(data["audit_chain"]),
    human_in_the_loop: (() => {
      const raw = data["human_in_the_loop"];
      if (raw === undefined || raw === null) return undefined;
      if (typeof raw === "object" && !Array.isArray(raw)) {
        const h = raw as Record<string, unknown>;
        return {
          required: asBool(h["required"]),
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
    free_tier: asBool(d["free_tier"]),
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

/** §3.13 (RFC-0025, v0.27): a task-type declaration. */
function parseTaskType(raw: RawMap): TaskType {
  return {
    id: String(raw["id"] ?? ""),
    intent: raw["intent"] !== undefined ? String(raw["intent"]) : undefined,
    authority_level:
      raw["authority_level"] !== undefined ? String(raw["authority_level"]) : undefined,
  };
}

/** §3.13 (RFC-0025, v0.27): an agent declaration (Capability Profile). */
function parseAgent(raw: RawMap): Agent {
  return {
    id: String(raw["id"] ?? ""),
    name: raw["name"] !== undefined ? String(raw["name"]) : undefined,
    authority_level:
      raw["authority_level"] !== undefined ? String(raw["authority_level"]) : undefined,
  };
}

/** §3.13 (RFC-0025, v0.27): one source in a grant_ceiling minimum computation. */
function parseGrantCeilingSource(raw: RawMap): GrantCeilingSource {
  return {
    id: String(raw["id"] ?? ""),
    authority_level:
      raw["authority_level"] !== undefined ? String(raw["authority_level"]) : undefined,
    unit_ref: raw["unit_ref"] !== undefined ? String(raw["unit_ref"]) : undefined,
    task_type_ref:
      raw["task_type_ref"] !== undefined ? String(raw["task_type_ref"]) : undefined,
    agent_ref: raw["agent_ref"] !== undefined ? String(raw["agent_ref"]) : undefined,
  };
}

/** §3.13 (RFC-0025, v0.27): multi-source minimum ceiling computation. */
function parseGrantCeiling(raw: unknown): GrantCeiling | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const d = raw as RawMap;
  const rawSources = (d["sources"] as RawMap[]) ?? [];
  return {
    sources: rawSources.map(parseGrantCeilingSource),
    mandatory_sources: stringListOrUndefined(d["mandatory_sources"]),
  };
}

/** §4.3a (v0.26): the tools/paths/capabilities/spend a `kind: skill` procedure may touch. */
function parseActionScope(raw: unknown): ActionScope | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const d = raw as RawMap;
  return {
    tools: stringListOrUndefined(d["tools"]),
    paths: stringListOrUndefined(d["paths"]),
    capabilities: stringListOrUndefined(d["capabilities"]),
    spend: parseSpend(d["spend"]),
    forbid: parseForbidScope(d["forbid"]),
  };
}

/**
 * §4.3a (PROPOSED, v0.31): the explicit negative scope a `kind: skill` declares.
 *
 * Same {tools, paths, capabilities} shape as the allowlist; every entry is a
 * prohibition. Mirrors parseActionScope's leniency — anything that is not an object
 * yields undefined ("declares no prohibition") rather than failing the whole parse.
 */
function parseForbidScope(raw: unknown): ForbidScope | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const d = raw as RawMap;
  return {
    tools: stringListOrUndefined(d["tools"]),
    paths: stringListOrUndefined(d["paths"]),
    capabilities: stringListOrUndefined(d["capabilities"]),
  };
}

/**
 * §4.3b (v0.29, RFC-0027): the ordered composition a `kind: playbook` declares.
 *
 * Returns undefined for anything that is not a list, matching parseActionScope: a
 * malformed block must not take down the whole parse, and undefined reads as
 * "declares no steps" — which a validator then rejects for kind: playbook. Steps
 * that are not objects, or carry no `id`, are dropped rather than half-parsed; a
 * step without an identity cannot be referenced by depends_on and so cannot
 * participate in the graph at all.
 */
function parseSteps(raw: unknown): PlaybookStep[] | undefined {
  if (!Array.isArray(raw)) return undefined;
  const steps: PlaybookStep[] = [];
  for (const item of raw) {
    if (!item || typeof item !== "object" || Array.isArray(item)) continue;
    const d = item as RawMap;
    if (d["id"] === undefined) continue;
    steps.push({
      id: String(d["id"]),
      uses: d["uses"] !== undefined ? String(d["uses"]) : undefined,
      action: d["action"] !== undefined ? String(d["action"]) : undefined,
      depends_on: stringListOrUndefined(d["depends_on"]),
      authority_level:
        d["authority_level"] !== undefined ? String(d["authority_level"]) : undefined,
      // §4.3b: a bare string is shorthand for a single-element list. Normalising here
      // means every consumer sees one shape; the triggers are disjunctive, so a list
      // of one and a scalar mean the same thing.
      escalation: parseEscalation(d["escalation"]),
      success_condition:
        d["success_condition"] !== undefined ? String(d["success_condition"]) : undefined,
      on_failure: d["on_failure"] !== undefined ? String(d["on_failure"]) : undefined,
      timeout: d["timeout"] !== undefined ? String(d["timeout"]) : undefined,
    });
  }
  return steps;
}

/** §4.3b: `escalation` accepts a single trigger or a list; both normalise to a list. */
function parseEscalation(raw: unknown): string[] | undefined {
  if (raw === undefined || raw === null) return undefined;
  if (typeof raw === "string") return [raw];
  return stringListOrUndefined(raw);
}

/** §4.3a (v0.26): purchases a `kind: skill` procedure may make. */
function parseSpend(raw: unknown): Spend | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const d = raw as RawMap;
  return {
    max_spend: d["max_spend"] !== undefined ? Number(d["max_spend"]) : undefined,
    allowed_vendors: stringListOrUndefined(d["allowed_vendors"]),
    currency: d["currency"] !== undefined ? String(d["currency"]) : undefined,
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
          requires_auth: asBool(then["requires_auth"]),
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
    required: asBool(r["required"]),
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
  // #166: problems the parse layer notices and no later stage can reconstruct.
  const diagnostics: string[] = [];
  const rawUnits = (data["units"] as RawMap[]) ?? [];
  const rawRels = (data["relationships"] as RawMap[]) ?? [];
  const rawManifests = (data["manifests"] as RawMap[]) ?? [];
  const rawExtRels = (data["external_relationships"] as RawMap[]) ?? [];
  const rawTaskTypes = (data["task_types"] as RawMap[]) ?? [];
  const rawAgents = (data["agents"] as RawMap[]) ?? [];

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
    units: rawUnits.map((u) => parseUnit(u, diagnostics)),
    parse_diagnostics: diagnostics,
    relationships: rawRels.map(parseRelationship),
    manifests: rawManifests.map(parseManifestRef),
    external_relationships: rawExtRels.map(parseExternalRelationship),
    freshness_policy: parseFreshnessPolicy(data["freshness_policy"]),
    visibility: parseVisibility(data["visibility"]),
    authority: parseAuthority(data["authority"]),
    discovery: parseDiscovery(data["discovery"]),
    not_for: data["not_for"] !== undefined ? asStringArray(data["not_for"]) : undefined,
    temporal: parseTemporal(data["temporal"]),
    authority_level_scale: stringListOrUndefined(data["authority_level_scale"]),
    task_types: rawTaskTypes.map(parseTaskType),
    agents: rawAgents.map(parseAgent),
    grant_ceiling: parseGrantCeiling(data["grant_ceiling"]),
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
