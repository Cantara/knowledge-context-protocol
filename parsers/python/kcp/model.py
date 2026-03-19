from dataclasses import dataclass, field
from datetime import date
from typing import Any, Optional, Union


@dataclass
class Discovery:
    """Discovery metadata for a knowledge unit or manifest default. See SPEC.md §RFC-0012 (v0.12).

    Normative rules:
    - ``rumored`` MUST have confidence < 0.5
    - ``verified`` SHOULD have confidence >= 0.8
    - ``deprecated`` SHOULD NOT be loaded for live operation
    """
    verification_status: Optional[str] = None  # rumored | observed | verified | deprecated
    source: Optional[str] = None  # manual | web_traversal | openapi | llm_inference
    observed_at: Optional[str] = None  # ISO 8601 datetime
    verified_at: Optional[str] = None  # ISO 8601 datetime
    confidence: Optional[float] = None  # 0.0–1.0, default 1.0
    contradicted_by: Optional[str] = None  # unit id


@dataclass
class Authority:
    """Authority block declaring action permissions. See SPEC.md §RFC-0009 (v0.12).

    Each action value is one of: ``initiative`` | ``requires_approval`` | ``denied``.
    Safe defaults: read=initiative, summarize=initiative, all others=denied.
    """
    read: Optional[str] = None           # initiative | requires_approval | denied
    summarize: Optional[str] = None      # initiative | requires_approval | denied
    modify: Optional[str] = None         # initiative | requires_approval | denied
    share_externally: Optional[str] = None  # initiative | requires_approval | denied
    execute: Optional[str] = None        # initiative | requires_approval | denied


@dataclass
class Visibility:
    """Visibility block for conditional access control. See SPEC.md §RFC-0009 (v0.12).

    The YAML field ``default`` maps to ``default_sensitivity`` to avoid collision with
    the Python built-in ``default`` keyword in some contexts; however Python does allow
    ``default`` as an attribute name — we use ``default_sensitivity`` for clarity,
    consistent with the Java parser's ``defaultSensitivity``.
    """
    default_sensitivity: Optional[str] = None  # public | internal | confidential | restricted
    conditions: list[dict] = field(default_factory=list)


@dataclass
class Delegation:
    """Delegation constraints block — root-level and per-unit override. See SPEC.md §3.4."""
    max_depth: Optional[int] = None
    require_capability_attenuation: Optional[bool] = None
    audit_chain: Optional[bool] = None
    human_in_the_loop: Optional[Any] = None  # dict per spec §3.4


@dataclass
class Compliance:
    """Compliance metadata block — root-level and per-unit override. See SPEC.md §3.5."""
    data_residency: list[str] = field(default_factory=list)
    sensitivity: Optional[str] = None
    regulations: list[str] = field(default_factory=list)
    restrictions: list[str] = field(default_factory=list)


@dataclass
class ExternalDependency:
    """A cross-manifest dependency for a knowledge unit. See SPEC.md §3.6."""
    manifest: str
    unit: str
    on_failure: str = "skip"



@dataclass
class FreshnessPolicy:
    """Freshness policy for a knowledge unit or manifest default. See SPEC.md §3.7 (v0.11)."""
    max_age_days: Optional[int] = None
    on_stale: Optional[str] = None  # "warn" | "degrade" | "block"
    review_contact: Optional[str] = None


@dataclass
class KnowledgeUnit:
    id: str
    path: str
    intent: str
    scope: str
    audience: list[str]
    kind: Optional[str] = None
    format: Optional[str] = None
    content_type: Optional[str] = None
    language: Optional[str] = None
    license: Optional[Union[str, dict]] = None
    validated: Optional[date] = None
    update_frequency: Optional[str] = None
    indexing: Optional[Union[str, dict]] = None
    depends_on: list[str] = field(default_factory=list)
    supersedes: Optional[str] = None
    triggers: list[str] = field(default_factory=list)
    hints: Optional[dict] = None
    access: Optional[str] = None
    auth_scope: Optional[str] = None
    sensitivity: Optional[str] = None
    deprecated: Optional[bool] = None
    payment: Optional[dict] = None
    rate_limits: Optional["RateLimits"] = None
    delegation: Optional[Delegation] = None
    compliance: Optional[Compliance] = None
    external_depends_on: list[ExternalDependency] = field(default_factory=list)
    requires_capabilities: list[str] = field(default_factory=list)
    freshness_policy: Optional["FreshnessPolicy"] = None
    visibility: Optional[Visibility] = None
    authority: Optional[Authority] = None
    discovery: Optional[Discovery] = None


@dataclass
class RateLimit:
    """Default rate limit tier — part of the rate_limits block. See SPEC.md §4.15."""
    requests_per_minute: Optional[int] = None
    requests_per_day: Optional[int] = None


@dataclass
class RateLimits:
    """Rate limits block — root-level and per-unit override. See SPEC.md §4.15."""
    default: Optional[RateLimit] = None


@dataclass
class Relationship:
    from_id: str
    to_id: str
    type: str


@dataclass
class ManifestRef:
    """A reference to an external KCP manifest in the federation. See SPEC.md §3.6."""
    id: str
    url: str
    label: Optional[str] = None
    relationship: Optional[str] = None
    auth: Optional["Auth"] = None
    update_frequency: Optional[str] = None
    local_mirror: Optional[str] = None
    version_pin: Optional[str] = None
    version_policy: Optional[str] = None  # "exact" | "minimum" | "compatible" (default)


@dataclass
class ExternalRelationship:
    """An explicit typed relationship between units across manifest boundaries. See SPEC.md §3.6."""
    from_unit: str
    to_unit: str
    type: str
    from_manifest: Optional[str] = None
    to_manifest: Optional[str] = None


@dataclass
class AuthMethod:
    """A single authentication method declaration."""
    type: str
    issuer: Optional[str] = None
    scopes: list[str] = field(default_factory=list)
    header: Optional[str] = None
    registration_url: Optional[str] = None


@dataclass
class Auth:
    """Root-level authentication block. See SPEC.md section 3.3."""
    methods: list[AuthMethod] = field(default_factory=list)


@dataclass
class TrustProvenance:
    """Publisher identity within the trust block. See SPEC.md section 3.2."""
    publisher: Optional[str] = None
    publisher_url: Optional[str] = None
    contact: Optional[str] = None


@dataclass
class TrustAudit:
    """Audit requirements within the trust block. See SPEC.md section 3.2."""
    agent_must_log: Optional[bool] = None
    require_trace_context: Optional[bool] = None


@dataclass
class Trust:
    """Root-level trust block. See SPEC.md section 3.2."""
    provenance: Optional[TrustProvenance] = None
    audit: Optional[TrustAudit] = None


@dataclass
class KnowledgeManifest:
    project: str
    version: str
    units: list[KnowledgeUnit]
    kcp_version: Optional[str] = None
    updated: Optional[date] = None
    language: Optional[str] = None
    license: Optional[Union[str, dict]] = None
    indexing: Optional[Union[str, dict]] = None
    hints: Optional[dict] = None
    trust: Optional[Trust] = None
    auth: Optional[Auth] = None
    delegation: Optional[Delegation] = None
    compliance: Optional[Compliance] = None
    payment: Optional[dict] = None
    rate_limits: Optional[RateLimits] = None
    relationships: list[Relationship] = field(default_factory=list)
    manifests: list[ManifestRef] = field(default_factory=list)
    external_relationships: list[ExternalRelationship] = field(default_factory=list)
    freshness_policy: Optional[FreshnessPolicy] = None
    visibility: Optional[Visibility] = None
    authority: Optional[Authority] = None
    discovery: Optional[Discovery] = None
