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
    language: Optional[str] = None
    license: Optional[Union[str, dict]] = None
    indexing: Optional[Union[str, dict]] = None
    relationships: list[Relationship] = field(default_factory=list)
