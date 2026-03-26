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
    read?: string;
    summarize?: string;
    modify?: string;
    share_externally?: string;
    execute?: string;
    [key: string]: string | undefined;
}
/** Discovery provenance block. See SPEC.md §4.18 (v0.12). */
export interface Discovery {
    verification_status?: string;
    source?: string;
    observed_at?: string;
    verified_at?: string;
    confidence?: number;
    contradicted_by?: string;
}
/** Freshness policy for a unit or manifest default. See SPEC.md §3.7 (v0.11). */
export interface FreshnessPolicy {
    max_age_days?: number;
    on_stale?: string;
    review_contact?: string;
}
export interface KnowledgeUnit {
    id: string;
    path: string;
    intent: string;
    scope: string;
    audience: string[];
    kind?: string;
    format?: string;
    content_type?: string;
    language?: string;
    license?: LicenseValue;
    validated?: string;
    update_frequency?: string;
    indexing?: IndexingValue;
    depends_on: string[];
    supersedes?: string;
    triggers: string[];
    hints?: Record<string, unknown>;
    access?: string;
    auth_scope?: string;
    sensitivity?: string;
    deprecated?: boolean;
    payment?: Record<string, unknown>;
    rate_limits?: RateLimits;
    delegation?: Delegation;
    compliance?: Compliance;
    external_depends_on: ExternalDependency[];
    requires_capabilities?: string[];
    freshness_policy?: FreshnessPolicy;
    visibility?: Visibility;
    authority?: Authority;
    discovery?: Discovery;
}
export interface Relationship {
    from_id: string;
    to_id: string;
    type: string;
}
/** A reference to an external KCP manifest in the federation. See SPEC.md §3.6. */
export interface ManifestRef {
    id: string;
    url: string;
    label?: string;
    relationship?: string;
    auth?: Auth;
    update_frequency?: string;
    local_mirror?: string;
    version_pin?: string;
    version_policy?: string;
}
/** A cross-manifest dependency for a knowledge unit. See SPEC.md §3.6. */
export interface ExternalDependency {
    manifest: string;
    unit: string;
    on_failure: string;
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
    type: string;
    issuer?: string;
    scopes?: string[];
    header?: string;
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
    approval_mechanism?: string;
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
    updated?: string;
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
    relationships: Relationship[];
    manifests: ManifestRef[];
    external_relationships: ExternalRelationship[];
    freshness_policy?: FreshnessPolicy;
    visibility?: Visibility;
    authority?: Authority;
    discovery?: Discovery;
}
export interface ValidationResult {
    errors: string[];
    warnings: string[];
    isValid: boolean;
}
//# sourceMappingURL=model.d.ts.map