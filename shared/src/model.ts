// KCP data model — TypeScript interfaces matching the Python/Java parser models

export type LicenseValue = string | Record<string, unknown>;
export type IndexingValue = string | Record<string, unknown>;

/** Conditional access block. See SPEC.md §4.16 (v0.12). */
export interface VisibilityConditionWhen {
  environment?: string | string[];
  agent_role?: string | string[];
}
export interface VisibilityConditionThen {
  sensitivity?: string;
  requires_auth?: boolean;
  authority?: Authority;
}
export interface VisibilityCondition {
  when: VisibilityConditionWhen;
  then: VisibilityConditionThen;
}
export interface Visibility {
  default?: string;
  conditions?: VisibilityCondition[];
}

/** Action permission block. See SPEC.md §4.17 (v0.12). */
export interface Authority {
  read?: string;           // initiative | requires_approval | denied
  summarize?: string;
  modify?: string;
  share_externally?: string;
  execute?: string;
  [key: string]: string | undefined;  // custom actions
}

/** Discovery provenance block. See SPEC.md §4.18 (v0.12). */
export interface Discovery {
  verification_status?: string;  // rumored | observed | verified | deprecated
  source?: string;               // manual | web_traversal | openapi | llm_inference
  observed_at?: string;
  verified_at?: string;
  verified_by?: string;          // RFC-0020 §2.3 / §4.18 — verifier key id or identity
  evidence?: string;             // RFC-0020 §2.3 / §4.18 — URL/path to verification artifact
  confidence?: number;
  contradicted_by?: string;
}

/** Bi-temporal validity block. See SPEC.md §4.22 (v0.19) and §15.13 (v0.20). */
export interface Temporal {
  valid_from?: string;    // ISO 8601 date — unit becomes active on this date
  valid_until?: string;   // ISO 8601 date — unit expires after this date (null = open-ended)
  recorded_at?: string;   // ISO 8601 date — when this version was added (informational)
  superseded_by?: string; // id of the unit within this manifest that replaces this one
}

/** Per-unit content hash. See RFC-0019 §3 (draft). */
export interface ContentHash {
  algorithm?: string;      // sha256 | sha384 | sha512
  value?: string;          // hex digest per RFC-0019 §3.2
}

/** Content structure block. See RFC-0016 (v0.17). */
export interface ContentStructure {
  primary?: string;        // prose | table | code | list | diagram | reference | mixed
  contains?: string[];     // list of modalities
  density?: string;        // sparse | normal | dense
}

/** Freshness policy for a unit or manifest default. See SPEC.md §3.7 (v0.11). */
export interface FreshnessPolicy {
  max_age_days?: number;
  on_stale?: string;     // "warn" | "degrade" | "block"
  review_contact?: string;
}

export interface KnowledgeUnit {
  id: string;
  aliases?: string[];      // RFC-0023 / §4.2a (v0.26) — additional identifiers resolving to this unit
  path: string;
  intent: string;
  scope: string;           // "global" | "project" | "module"
  audience: string[];
  kind?: string;           // "knowledge" | "schema" | "service" | "policy" | "executable" | "skill"
  action_scope?: ActionScope;  // §4.3a (v0.26) — tools/paths/capabilities a kind: skill procedure may touch
  steps?: PlaybookStep[];      // §4.3b (v0.29) — ordered composition; REQUIRED and non-empty for kind: playbook
  /**
   * §4.3c (v0.30, RFC-0028) — the explicit eligibility grant §16.3 C4 requires.
   *
   * Defined only for the governed kinds, `skill` and `playbook`. Absent means NOT
   * eligible: a governed procedure fails closed and renders as a pointer an agent may
   * read but not enact. It is a grant, not a capability claim — it does not widen
   * `action_scope` and does not raise `authority_level`.
   */
  load_eligible?: boolean;
  format?: string;         // "markdown" | "pdf" | "openapi" | "json-schema" | etc.
  content_type?: string;
  language?: string;
  license?: LicenseValue;
  validated?: string;      // ISO 8601 date string "YYYY-MM-DD"
  update_frequency?: string;
  indexing?: IndexingValue;
  depends_on: string[];    // defaults to []
  supersedes?: string;
  triggers: string[];      // defaults to []
  hints?: Record<string, unknown>;
  access?: string;         // "public" | "authenticated" | "restricted"
  auth_scope?: string;     // opaque scope token, meaningful when access is "restricted"
  sensitivity?: string;    // "public" | "internal" | "confidential" | "restricted"
  deprecated?: boolean;
  payment?: Payment;
  rate_limits?: RateLimits;
  delegation?: Delegation;
  compliance?: Compliance;
  auth?: Auth;             // v0.23 — per-unit auth override (SPEC §3.3)
  external_depends_on: ExternalDependency[];  // defaults to []
  requires_capabilities?: string[];
  freshness_policy?: FreshnessPolicy;
  visibility?: Visibility;
  authority?: Authority;
  discovery?: Discovery;
  not_for?: string[];      // RFC-0015 (v0.17) — negative-space declarations
  not_for_strict?: boolean;  // RFC-0015 (v0.17) — default false
  content_structure?: ContentStructure;  // RFC-0016 (v0.17)
  content_hash?: ContentHash;  // RFC-0019 (draft)
  temporal?: Temporal;          // RFC-0010 / §4.22 (v0.19)
  authority_level?: string;    // RFC-0025 / §4.23 (v0.27) — ceiling on the root authority_level_scale
}

/** Purchases a `kind: skill` procedure may make. See SPEC.md §4.3a (v0.26). */
export interface Spend {
  max_spend?: number;         // per-purchase cap, denominated in `currency`
  allowed_vendors?: string[]; // allowlist of vendor/payee identifiers the procedure may pay
  currency?: string;          // ISO 4217 code or crypto ticker `max_spend` is denominated in
}

/**
 * Explicit negative scope on a `kind: skill` action_scope. See SPEC.md §4.3a (v0.31, RFC-0029).
 *
 * Same {tools, paths, capabilities} shape as the allowlist, but every entry is a
 * PROHIBITION: a token listed here is denied even when the allowlist grants it.
 * `deny` is checked in addition to — and overrides — the allowlist, fail-closed.
 */
export interface DenyScope {
  tools?: string[];         // tool names the procedure MUST NOT invoke, even if allowlisted
  paths?: string[];         // file-system paths (globs permitted) the procedure MUST NOT touch
  capabilities?: string[];  // named capabilities the procedure MUST NOT exercise
}

/** Envelope of tools/paths/capabilities/spend a `kind: skill` procedure may touch. See SPEC.md §4.3a (v0.26). */
export interface ActionScope {
  tools?: string[];         // tool names the procedure may invoke
  paths?: string[];         // file-system paths (globs permitted) the procedure may read/write
  capabilities?: string[];  // named capabilities the procedure requires or exercises
  spend?: Spend;            // purchases the procedure may make (per-purchase cap + vendor allowlist)
  deny?: DenyScope;     // §4.3a (v0.31, RFC-0029) — explicit prohibitions; override the allowlist, fail-closed
}

/**
 * One step of a `kind: playbook` composition. See SPEC.md §4.3b (v0.29, RFC-0027).
 *
 * The step — not the playbook — is the unit of governance: `authority_level` is a
 * ceiling on this step alone, and effective authority is the minimum across it, the
 * playbook's, the task-type grant_ceiling, any tenant ceiling, and the agent's own
 * grant. A playbook can therefore never raise authority.
 */
export interface PlaybookStep {
  id: string;                  // unique within the playbook
  uses?: string;               // id of the unit this step enacts; SHOULD name a kind: skill unit
  action?: string;             // inline description, when no unit exists yet
  depends_on?: string[];       // step ids that must complete successfully first
  authority_level?: string;    // RFC-0025 scale; ceiling semantics — at most this level
  escalation?: string[];       // RFC-0026 triggers; disjunctive, evaluated before enactment
  success_condition?: string;  // prose assertion; the protocol never evaluates it
  on_failure?: string;         // "abort" | "continue" | "escalate"; default "abort"
  timeout?: string;            // ISO 8601 duration; elapsing constitutes failure
}

export interface Relationship {
  from_id: string;
  to_id: string;
  type: string;            // "enables" | "context" | "supersedes" | "contradicts" | "depends_on" | "governs"
}

/** Pre-fetch credential-planning hint on a manifests[] entry. See SPEC.md §3.6 (v0.24). */
export interface AgentIdentity {
  required?: boolean;       // default false
  credential_hint?: string; // github_pat | oauth2 | confluence_pat | api_key | none
  issuer_hint?: string;     // for oauth2: issuer URL
  docs_url?: string;        // where a developer finds credential instructions
}

/** A reference to an external KCP manifest in the federation. See SPEC.md §3.6. */
export interface ManifestRef {
  id: string;
  url: string;
  label?: string;
  relationship?: string;   // "child" | "foundation" | "governs" | "peer" | "archive"
  auth?: Auth;
  update_frequency?: string;
  local_mirror?: string;
  version_pin?: string;
  version_policy?: string; // "exact" | "minimum" | "compatible" (default: "compatible")
  temporal?: Temporal;     // RFC-0021 / §3.6 (v0.21) — source-level validity window
  context?: string[];      // RFC-0011 / §3.6 (v0.24) — environment labels this ref is valid for
  agent_identity?: AgentIdentity;  // RFC-0011 / §3.6 (v0.24) — pre-fetch credential hint
}

/** A cross-manifest dependency for a knowledge unit. See SPEC.md §3.6. */
export interface ExternalDependency {
  manifest: string;
  unit: string;
  on_failure: string;      // "skip" | "warn" | "degrade" — default "skip"
}

/** An explicit typed relationship between units across manifest boundaries. See SPEC.md §3.6. */
export interface ExternalRelationship {
  from_manifest?: string;
  from_unit: string;
  to_manifest?: string;
  to_unit: string;
  type: string;
}

/** A single authentication method declaration. See SPEC.md §3.3. */
export interface AuthMethod {
  type: string;            // none | oauth2 | api_key | bearer_token | spiffe | did | http_signature
  issuer?: string;         // OAuth 2.1 issuer URL
  scopes?: string[];       // OAuth 2.1 scopes
  header?: string;         // API key header name (default: "X-API-Key")
  registration_url?: string;
  // Extended method sub-fields (RFC-0002, v0.22):
  trust_domain?: string;      // spiffe: accepted SPIFFE trust domain
  supported_methods?: string[]; // did: accepted DID methods (e.g. did:web, did:key)
  key_id?: string;            // http_signature: signing key identifier
  algorithm?: string;         // http_signature: signature algorithm
}

/** Root-level authentication block. See SPEC.md §3.3. */
export interface Auth {
  methods: AuthMethod[];
}

/** Publisher identity within the trust block. See SPEC.md §3.2. */
export interface TrustProvenance {
  publisher?: string;
  publisher_url?: string;
  contact?: string;
  publisher_did?: string;   // v0.23 — W3C DID publisher identity
}

/** Audit requirements within the trust block. See SPEC.md §3.2. */
export interface TrustAudit {
  agent_must_log?: boolean;
  require_trace_context?: boolean;
  provides_access_receipts?: boolean;   // v0.23
  receipt_format?: string;              // v0.23
}

/** Agent attestation requirements within the trust block. See SPEC.md §3.2 (v0.22). */
export interface TrustAgentRequirements {
  require_attestation?: boolean;
  trusted_providers?: string[];   // identity-based (OIDC-A agent_provider)
  attestation_url?: string;       // credential-based (HTTPS)
  attestation_jwks?: string;      // JWKS for verifying attestation_url responses
  propagate_to_governed?: boolean; // governs-edge policy floor (#47)
}

/** Root-level trust block. See SPEC.md §3.2. */
export interface Trust {
  provenance?: TrustProvenance;
  audit?: TrustAudit;
  agent_requirements?: TrustAgentRequirements;
}

/** Human-in-the-loop approval object — see SPEC.md §3.4. */
export interface HumanInTheLoop {
  required?: boolean;
  approval_mechanism?: string;  // oauth_consent | uma | custom
  docs_url?: string;
}

/** Delegation constraints block — root-level and per-unit override. See SPEC.md §3.4. */
export interface Delegation {
  max_depth?: number;
  require_capability_attenuation?: boolean;
  require_delegation_proof?: boolean;   // v0.23 (SPEC §3.4)
  audit_chain?: boolean;
  human_in_the_loop?: HumanInTheLoop;
}

/** Compliance metadata block — root-level and per-unit override. See SPEC.md §3.5. */
export interface Compliance {
  data_residency?: string[];
  sensitivity?: string;
  regulations?: string[];
  restrictions?: string[];
}

/** A request/token count limit: a positive integer or the sentinel "unlimited" (v0.25). */
export type RateLimitCount = number | "unlimited";

/** Rate limits for one authentication tier — root-level and per-unit override. See SPEC.md §4.15. */
export interface RateLimitsDefault {
  requests_per_minute?: RateLimitCount;
  requests_per_hour?: RateLimitCount;   // v0.25
  requests_per_day?: RateLimitCount;
}

/** Token-based limits for one authentication tier. See SPEC.md §4.15 (v0.25). */
export interface RateLimitTokensTier {
  tokens_per_minute?: RateLimitCount;
  tokens_per_day?: RateLimitCount;
}

/** Token-based limits, mirroring the tier structure. See SPEC.md §4.15 (v0.25). */
export interface RateLimitTokens {
  default?: RateLimitTokensTier;
  authenticated?: RateLimitTokensTier;
  premium?: RateLimitTokensTier;
}

/** Response-header names carrying live limit state. See SPEC.md §4.15 (v0.25). */
export interface RateLimitHeaders {
  remaining?: string;
  reset?: string;
  retry_after?: string;
}

/** Rate limits block — root-level and per-unit override. See SPEC.md §4.15. */
export interface RateLimits {
  default?: RateLimitsDefault;
  authenticated?: RateLimitsDefault;   // v0.25
  premium?: RateLimitsDefault;         // v0.25
  tokens?: RateLimitTokens;            // v0.25
  headers?: RateLimitHeaders;          // v0.25
  backoff?: string;                    // v0.25 — "linear" | "exponential" | "none"
}

/** A single accepted payment method. See SPEC.md §4.14 (v0.25). */
export interface PaymentMethod {
  type: string;              // free | x402 | meter | subscription
  currency?: string;         // x402
  price_per_request?: string; // x402 — decimal string
  networks?: string[];       // x402
  wallet?: string;           // x402
  provider?: string;         // meter
  plans_url?: string;        // meter | subscription
  free_tier?: boolean;       // subscription
  free_requests_per_day?: number; // subscription
  upgrade_url?: string;      // subscription
}

/** Monetisation block — root-level and per-unit override. See SPEC.md §4.14. */
export interface Payment {
  default_tier?: string;     // free | metered | subscription
  methods?: PaymentMethod[]; // v0.25
  billing_contact?: string;  // v0.25
}

export interface KnowledgeManifest {
  /**
   * Problems noticed while parsing, which no later stage can reconstruct (#166).
   *
   * The parse layer makes decisions it used to be unable to report. A value that fails
   * scalar resolution (§2.1) is dropped, and an unknown field is discarded per §2 — so
   * by the time a validator runs, `load_eligible: ture` and `laod_eligible: true` are
   * both indistinguishable from a field that was never written. Three manifests, one
   * mistake each, all reported "✓ Valid — no errors or warnings", and only one of them
   * granted anything.
   *
   * Diagnostics report; they never rescue. A malformed value still reads as undeclared
   * and an unknown field is still ignored, because §2's forward-compatibility rule
   * requires it — ignoring a field is not the same as being unable to mention it.
   */
  parse_diagnostics?: string[];
  project: string;
  version: string;
  units: KnowledgeUnit[];
  kcp_version?: string;
  updated?: string;        // ISO 8601 date string "YYYY-MM-DD"
  language?: string;
  license?: LicenseValue;
  indexing?: IndexingValue;
  hints?: Record<string, unknown>;
  trust?: Trust;
  auth?: Auth;
  delegation?: Delegation;
  compliance?: Compliance;
  payment?: Payment;
  rate_limits?: RateLimits;
  serving?: Serving;       // RFC-0024 / §3.12 (v0.26) — authoritative serving endpoints
  relationships: Relationship[];  // defaults to []
  manifests: ManifestRef[];       // defaults to []
  external_relationships: ExternalRelationship[];  // defaults to []
  freshness_policy?: FreshnessPolicy;
  visibility?: Visibility;
  authority?: Authority;
  discovery?: Discovery;
  not_for?: string[];      // RFC-0015 (v0.17) — manifest-level negative-space declarations
  temporal?: Temporal;     // RFC-0010 / §4.22 (v0.19) — manifest-level defaults
  authority_level_scale?: string[];  // RFC-0025 / §3.13 (v0.27) — fixed ordinal scale, e.g. [observe, explain, suggest, prepare, commit]
  task_types: TaskType[];  // RFC-0025 / §3.13 (v0.27) — defaults to []
  agents: Agent[];         // RFC-0025 / §3.13 (v0.27) — defaults to []
  grant_ceiling?: GrantCeiling;  // RFC-0025 / §3.13 (v0.27)
}

/** Signed declaration of authoritative serving endpoints. See SPEC.md §3.12 (v0.26). */
export interface Serving {
  manifest?: string[];     // HTTPS URLs authoritatively serving this manifest
  mcp?: string[];          // HTTPS URLs of authorized MCP endpoints
}

/** A task-type declaration. See SPEC.md §3.13 (RFC-0025, v0.27). */
export interface TaskType {
  id: string;
  intent?: string;
  authority_level?: string;   // this task-type's own declared ceiling
}

/** An agent declaration (Capability Profile). See SPEC.md §3.13 (RFC-0025, v0.27). */
export interface Agent {
  id: string;
  name?: string;
  authority_level?: string;   // this agent's own capability ceiling, across all tasks
}

/**
 * One ceiling source in a `grant_ceiling` computation. Exactly one of `authority_level`,
 * `unit_ref`, `task_type_ref`, `agent_ref` SHOULD be present — the resolved value is either
 * the inline `authority_level` or looked up from the referenced entity's own declared ceiling.
 * See SPEC.md §3.13 (RFC-0025, v0.27).
 */
export interface GrantCeilingSource {
  id: string;
  authority_level?: string;
  unit_ref?: string;
  task_type_ref?: string;
  agent_ref?: string;
}

/** Multi-source minimum ceiling computation. See SPEC.md §3.13 (RFC-0025, v0.27). */
export interface GrantCeiling {
  sources: GrantCeilingSource[];   // defaults to []
  mandatory_sources?: string[];    // source ids that MUST appear in every grant_ceiling in this manifest
}

export interface ValidationResult {
  errors: string[];
  warnings: string[];
  isValid: boolean;
}
