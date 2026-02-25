from dataclasses import dataclass, field
from datetime import date
from typing import Optional


@dataclass
class KnowledgeUnit:
    id: str
    path: str
    intent: str
    scope: str
    audience: list[str]
    validated: Optional[date] = None
    depends_on: list[str] = field(default_factory=list)
    supersedes: Optional[str] = None
    triggers: list[str] = field(default_factory=list)


@dataclass
class Relationship:
    from_id: str
    to_id: str
    type: str


@dataclass
class KnowledgeManifest:
    project: str
    version: str
    units: list[KnowledgeUnit]
    kcp_version: Optional[str] = None
    updated: Optional[date] = None
    relationships: list[Relationship] = field(default_factory=list)
