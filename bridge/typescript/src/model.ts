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
}

export interface Relationship {
  from_id: string;
  to_id: string;
  type: string;            // "enables" | "context" | "supersedes" | "contradicts"
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
  relationships: Relationship[];  // defaults to []
}

export interface ValidationResult {
  errors: string[];
  warnings: string[];
  isValid: boolean;
}
