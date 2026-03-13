// KCP data model — TypeScript interfaces matching the Python/Java parser models

export type LicenseValue = string | Record<string, unknown>;
export type IndexingValue = string | Record<string, unknown>;

export interface KnowledgeUnit {
  id: string;
  path: string;
  intent: string;
  scope: string;           // "global" | "project" | "module"
  audience: string[];
  kind?: string;           // "knowledge" | "schema" | "service" | "policy" | "executable"
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
  payment?: Record<string, unknown>;
  rate_limits?: RateLimits;
  delegation?: Delegation;
  compliance?: Compliance;
  external_depends_on: ExternalDependency[];  // defaults to []
}

export interface Relationship {
  from_id: string;
  to_id: string;
  type: string;            // "enables" | "context" | "supersedes" | "contradicts" | "depends_on" | "governs"
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
  type: string;            // "none" | "oauth2" | "api_key" (core types)
  issuer?: string;         // OAuth 2.1 issuer URL
  scopes?: string[];       // OAuth 2.1 scopes
  header?: string;         // API key header name (default: "X-API-Key")
  registration_url?: string;
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
}

/** Audit requirements within the trust block. See SPEC.md §3.2. */
export interface TrustAudit {
  agent_must_log?: boolean;
  require_trace_context?: boolean;
}

/** Root-level trust block. See SPEC.md §3.2. */
export interface Trust {
  provenance?: TrustProvenance;
  audit?: TrustAudit;
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

/** Rate limits default tier — root-level and per-unit override. See SPEC.md §4.15. */
export interface RateLimitsDefault {
  requests_per_minute?: number;
  requests_per_day?: number;
}

/** Rate limits block — root-level and per-unit override. See SPEC.md §4.15. */
export interface RateLimits {
  default?: RateLimitsDefault;
}

export interface KnowledgeManifest {
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
  payment?: Record<string, unknown>;
  rate_limits?: RateLimits;
  relationships: Relationship[];  // defaults to []
  manifests: ManifestRef[];       // defaults to []
  external_relationships: ExternalRelationship[];  // defaults to []
}

export interface ValidationResult {
  errors: string[];
  warnings: string[];
  isValid: boolean;
}
