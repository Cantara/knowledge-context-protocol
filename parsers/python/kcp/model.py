from dataclasses import dataclass, field
from datetime import date
from typing import Optional, Union


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


@dataclass
class Relationship:
    from_id: str
    to_id: str
    type: str


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
    payment: Optional[dict] = None
    relationships: list[Relationship] = field(default_factory=list)
