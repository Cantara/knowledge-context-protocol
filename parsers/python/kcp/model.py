from dataclasses import dataclass, field
from datetime import date
from typing import Any, List, Optional, Union


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
    verified_by: Optional[str] = None  # RFC-0020 §2.3 / §4.18 — verifier key id or identity
    evidence: Optional[str] = None  # RFC-0020 §2.3 / §4.18 — URL/path to verification artifact
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
    require_delegation_proof: Optional[bool] = None  # v0.23 (SPEC §3.4)
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
class ContentStructure:
    """Content structure metadata for a knowledge unit. See RFC-0016 (v0.17).

    Vocabulary (forward-compatible — unknown values pass through):
    - ``primary`` / ``contains`` modalities: prose | table | code | list | diagram | reference | mixed
    - ``density``: sparse | normal | dense
    """
    primary: Optional[str] = None
    contains: list[str] = field(default_factory=list)
    density: Optional[str] = None


@dataclass
class ContentHash:
    """Per-unit content hash binding referenced content to the signed
    manifest. See RFC-0019 (draft).

    - ``algorithm``: sha256 | sha384 | sha512
    - ``value``: hex digest per RFC-0019 §3.2 (file bytes, or the
      bytewise-sorted ``relpath\\0hexdigest\\n`` entries of a directory)
    """
    algorithm: Optional[str] = None
    value: Optional[str] = None


@dataclass
class Temporal:
    """Bi-temporal validity block for a unit or manifest root default.
    See SPEC.md §4.22 (v0.19) and §15.13 (v0.20).

    - ``valid_from``: ISO 8601 date — unit becomes active on this date
    - ``valid_until``: ISO 8601 date — unit expires after this date (null = open-ended)
    - ``recorded_at``: ISO 8601 date — when this version was added (informational)
    - ``superseded_by``: id of the unit within this manifest that replaces this one
    """
    valid_from: Optional[str] = None
    valid_until: Optional[str] = None
    recorded_at: Optional[str] = None
    superseded_by: Optional[str] = None


@dataclass
class FreshnessPolicy:
    """Freshness policy for a knowledge unit or manifest default. See SPEC.md §3.7 (v0.11)."""
    max_age_days: Optional[int] = None
    on_stale: Optional[str] = None  # "warn" | "degrade" | "block"
    review_contact: Optional[str] = None


@dataclass
class Spend:
    """What a `kind: skill` procedure may buy. See SPEC.md §4.3a.1.

    Governs the buy *decision*, fail-closed — an unlisted vendor, an over-cap amount or a
    currency mismatch is held. KCP never settles a payment; a runtime wallet does.
    """
    max_spend: Optional[float] = None  # per-purchase cap, denominated in ``currency``
    allowed_vendors: Optional[List[str]] = None  # allowlist of vendor/payee identifiers
    currency: Optional[str] = None  # ISO 4217 code (USD, EUR) or asset ticker (USDC)


@dataclass
class ActionScope:
    """The envelope bounding a ``kind: skill`` unit. See SPEC.md §4.3a.

    Absent is not the same as empty: a unit with no ``action_scope`` authorizes nothing,
    so the parser yields ``None`` rather than an empty object. Sub-fields mirror
    ``shared/src/parser.ts`` ``parseActionScope``.
    """
    tools: Optional[List[str]] = None  # tool names the procedure may invoke
    paths: Optional[List[str]] = None  # paths (globs permitted) it may read or write
    capabilities: Optional[List[str]] = None  # named capabilities it requires or exercises
    spend: Optional[Spend] = None  # purchases it may make (§4.3a.1)


@dataclass
class PlaybookStep:
    """One step of a ``kind: playbook`` composition. See SPEC.md §4.3b (v0.29, RFC-0027).

    The step — not the playbook — is the unit of governance. ``authority_level`` is a
    ceiling on this step alone; effective authority is the minimum across it, the
    playbook's, the task-type grant_ceiling, any tenant ceiling, and the enacting
    agent's own grant, so a playbook can never raise authority.

    Mirrors ``shared/src/parser.ts`` ``parseSteps`` and the Java ``PlaybookStep`` record.
    """
    id: str
    uses: Optional[str] = None  # unit id this step enacts; SHOULD name a kind: skill unit
    action: Optional[str] = None  # inline description, when no unit exists yet
    depends_on: Optional[List[str]] = None  # step ids that must succeed first
    authority_level: Optional[str] = None  # RFC-0025 scale; ceiling semantics
    escalation: Optional[List[str]] = None  # RFC-0026 triggers; disjunctive, pre-enactment
    success_condition: Optional[str] = None  # prose assertion; never evaluated by the protocol
    on_failure: Optional[str] = None  # abort | continue | escalate; default abort
    timeout: Optional[str] = None  # ISO 8601 duration; elapsing constitutes failure


@dataclass
class KnowledgeUnit:
    id: str
    path: str
    intent: str
    scope: str
    audience: list[str]
    aliases: Optional[list[str]] = None  # RFC-0023 / §4.2a (v0.26)
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
    payment: Optional["Payment"] = None
    rate_limits: Optional["RateLimits"] = None
    delegation: Optional[Delegation] = None
    compliance: Optional[Compliance] = None
    auth: Optional["Auth"] = None  # v0.23 — per-unit auth override (SPEC §3.3)
    external_depends_on: list[ExternalDependency] = field(default_factory=list)
    requires_capabilities: list[str] = field(default_factory=list)
    freshness_policy: Optional["FreshnessPolicy"] = None
    visibility: Optional[Visibility] = None
    authority: Optional[Authority] = None
    discovery: Optional[Discovery] = None
    not_for: list[str] = field(default_factory=list)  # RFC-0015 (v0.17)
    not_for_strict: Optional[bool] = None  # RFC-0015 (v0.17), default false
    content_structure: Optional[ContentStructure] = None  # RFC-0016 (v0.17)
    content_hash: Optional[ContentHash] = None  # RFC-0019 (draft)
    temporal: Optional[Temporal] = None  # RFC-0010 / §4.22 (v0.19)
    action_scope: Optional[ActionScope] = None  # §4.3a (v0.26.1) — what a kind: skill procedure may touch
    steps: Optional[List["PlaybookStep"]] = None  # §4.3b (v0.29) — ordered composition; required for kind: playbook
    authority_level: Optional[str] = None  # RFC-0025 / §4.23 (v0.27) — ceiling on the root authority_level_scale


# A rate-limit count is a positive int or the sentinel "unlimited" (v0.25).
RateLimitCount = Union[int, str]


@dataclass
class RateLimit:
    """Rate limits for one authentication tier — part of the rate_limits block. See SPEC.md §4.15."""
    requests_per_minute: Optional[RateLimitCount] = None
    requests_per_hour: Optional[RateLimitCount] = None   # v0.25
    requests_per_day: Optional[RateLimitCount] = None


@dataclass
class RateLimitTokensTier:
    """Token-based limits for one authentication tier. See SPEC.md §4.15 (v0.25)."""
    tokens_per_minute: Optional[RateLimitCount] = None
    tokens_per_day: Optional[RateLimitCount] = None


@dataclass
class RateLimitTokens:
    """Token-based limits, mirroring the tier structure. See SPEC.md §4.15 (v0.25)."""
    default: Optional[RateLimitTokensTier] = None
    authenticated: Optional[RateLimitTokensTier] = None
    premium: Optional[RateLimitTokensTier] = None


@dataclass
class RateLimitHeaders:
    """Response-header names carrying live limit state. See SPEC.md §4.15 (v0.25)."""
    remaining: Optional[str] = None
    reset: Optional[str] = None
    retry_after: Optional[str] = None


@dataclass
class RateLimits:
    """Rate limits block — root-level and per-unit override. See SPEC.md §4.15."""
    default: Optional[RateLimit] = None
    authenticated: Optional[RateLimit] = None            # v0.25
    premium: Optional[RateLimit] = None                  # v0.25
    tokens: Optional[RateLimitTokens] = None             # v0.25
    headers: Optional[RateLimitHeaders] = None           # v0.25
    backoff: Optional[str] = None                        # v0.25


@dataclass
class PaymentMethod:
    """A single accepted payment method. See SPEC.md §4.14 (v0.25)."""
    type: str = ""
    currency: Optional[str] = None
    price_per_request: Optional[str] = None
    networks: Optional[List[str]] = None
    wallet: Optional[str] = None
    provider: Optional[str] = None
    plans_url: Optional[str] = None
    free_tier: Optional[bool] = None
    free_requests_per_day: Optional[int] = None
    upgrade_url: Optional[str] = None


@dataclass
class Payment:
    """Monetisation block — root-level and per-unit override. See SPEC.md §4.14."""
    default_tier: Optional[str] = None
    methods: Optional[List[PaymentMethod]] = None        # v0.25
    billing_contact: Optional[str] = None                # v0.25


@dataclass
class Serving:
    """Signed declaration of authoritative serving endpoints. See SPEC.md §3.12 (v0.26)."""
    manifest: Optional[List[str]] = None   # HTTPS URLs serving this manifest
    mcp: Optional[List[str]] = None        # HTTPS URLs of authorized MCP endpoints


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
    temporal: Optional["Temporal"] = None  # RFC-0021 / §3.6 (v0.21) — source-level validity window
    context: Optional[List[str]] = None  # RFC-0011 / §3.6 (v0.24) — environment labels this ref is valid for
    agent_identity: Optional["AgentIdentity"] = None  # RFC-0011 / §3.6 (v0.24) — pre-fetch credential hint


@dataclass
class AgentIdentity:
    """Pre-fetch credential-planning hint on a manifests[] entry. See SPEC.md §3.6 (v0.24)."""
    required: Optional[bool] = None       # default false
    credential_hint: Optional[str] = None  # github_pat | oauth2 | confluence_pat | api_key | none
    issuer_hint: Optional[str] = None     # for oauth2: issuer URL
    docs_url: Optional[str] = None        # where a developer finds credential instructions


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
    # Extended method sub-fields (RFC-0002, v0.22):
    trust_domain: Optional[str] = None       # spiffe
    supported_methods: list[str] = field(default_factory=list)  # did
    key_id: Optional[str] = None             # http_signature
    algorithm: Optional[str] = None          # http_signature


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
    publisher_did: Optional[str] = None  # v0.23 — W3C DID publisher identity


@dataclass
class TrustAudit:
    """Audit requirements within the trust block. See SPEC.md section 3.2."""
    agent_must_log: Optional[bool] = None
    require_trace_context: Optional[bool] = None
    provides_access_receipts: Optional[bool] = None  # v0.23
    receipt_format: Optional[str] = None             # v0.23


@dataclass
class TrustAgentRequirements:
    """Agent attestation requirements within the trust block. See SPEC.md section 3.2 (v0.22)."""
    require_attestation: Optional[bool] = None
    trusted_providers: list[str] = field(default_factory=list)
    attestation_url: Optional[str] = None
    attestation_jwks: Optional[str] = None
    propagate_to_governed: Optional[bool] = None


@dataclass
class Trust:
    """Root-level trust block. See SPEC.md section 3.2."""
    provenance: Optional[TrustProvenance] = None
    audit: Optional[TrustAudit] = None
    agent_requirements: Optional[TrustAgentRequirements] = None


@dataclass
class TaskType:
    """A task-type declaration. See SPEC.md §3.13 (RFC-0025, v0.27)."""
    id: str
    intent: Optional[str] = None
    authority_level: Optional[str] = None  # this task-type's own declared ceiling


@dataclass
class Agent:
    """An agent declaration (Capability Profile). See SPEC.md §3.13 (RFC-0025, v0.27)."""
    id: str
    name: Optional[str] = None
    authority_level: Optional[str] = None  # this agent's own capability ceiling, across all tasks


@dataclass
class GrantCeilingSource:
    """One ceiling source in a grant_ceiling computation. Exactly one of
    ``authority_level``, ``unit_ref``, ``task_type_ref``, ``agent_ref`` SHOULD be present —
    the resolved value is either the inline ``authority_level`` or looked up from the
    referenced entity's own declared ceiling. See SPEC.md §3.13 (RFC-0025, v0.27).
    """
    id: str
    authority_level: Optional[str] = None
    unit_ref: Optional[str] = None
    task_type_ref: Optional[str] = None
    agent_ref: Optional[str] = None


@dataclass
class GrantCeiling:
    """Multi-source minimum ceiling computation. See SPEC.md §3.13 (RFC-0025, v0.27)."""
    sources: list[GrantCeilingSource] = field(default_factory=list)
    mandatory_sources: Optional[list[str]] = None  # source ids that MUST appear in every grant_ceiling


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
    payment: Optional["Payment"] = None
    rate_limits: Optional[RateLimits] = None
    serving: Optional["Serving"] = None  # RFC-0024 / §3.12 (v0.26)
    relationships: list[Relationship] = field(default_factory=list)
    manifests: list[ManifestRef] = field(default_factory=list)
    external_relationships: list[ExternalRelationship] = field(default_factory=list)
    freshness_policy: Optional[FreshnessPolicy] = None
    visibility: Optional[Visibility] = None
    authority: Optional[Authority] = None
    discovery: Optional[Discovery] = None
    not_for: list[str] = field(default_factory=list)  # RFC-0015 (v0.17), manifest-level
    temporal: Optional[Temporal] = None  # RFC-0010 / §4.22 (v0.19) — manifest-level defaults
    authority_level_scale: Optional[list[str]] = None  # RFC-0025 / §3.13 (v0.27) — fixed ordinal scale
    task_types: list[TaskType] = field(default_factory=list)  # RFC-0025 / §3.13 (v0.27)
    agents: list[Agent] = field(default_factory=list)  # RFC-0025 / §3.13 (v0.27)
    grant_ceiling: Optional[GrantCeiling] = None  # RFC-0025 / §3.13 (v0.27)
