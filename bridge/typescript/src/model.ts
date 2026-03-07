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
}

export interface Relationship {
  from_id: string;
  to_id: string;
  type: string;            // "enables" | "context" | "supersedes" | "contradicts"
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
  payment?: Record<string, unknown>;
  relationships: Relationship[];  // defaults to []
}

export interface ValidationResult {
  errors: string[];
  warnings: string[];
  isValid: boolean;
}
