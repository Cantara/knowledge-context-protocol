from datetime import date
from pathlib import Path, PurePosixPath
from typing import Optional, Union

import yaml

from .model import KnowledgeManifest, KnowledgeUnit, Relationship


def _to_date(value) -> Optional[date]:
    if value is None:
        return None
    if isinstance(value, date):
        return value
    return date.fromisoformat(str(value))


def _validate_unit_path(raw: str) -> str:
    """Validate that a unit path does not traverse outside the manifest root.

    Spec §12 requires parsers to reject paths containing '..' that escape
    the root. Raises ValueError for invalid paths.
    """
    if raw is None:
        return raw
    # Reject absolute paths
    if raw.startswith("/") or raw.startswith("\\"):
        raise ValueError(f"Unit path must be relative: {raw!r}")
    # Normalise with PurePosixPath (forward-slash semantics, no OS calls)
    normalised = PurePosixPath(raw)
    if normalised.parts and normalised.parts[0] == "..":
        raise ValueError(f"Unit path escapes manifest root: {raw!r}")
    return raw


def parse(path: Union[str, Path]) -> KnowledgeManifest:
    """Parse a knowledge.yaml file from disk."""
    with Path(path).open(encoding="utf-8") as f:
        data = yaml.safe_load(f)
    return parse_dict(data)


def parse_dict(data: dict) -> KnowledgeManifest:
    """Parse a knowledge manifest from a pre-loaded dict."""
    units = [
        KnowledgeUnit(
            id=u["id"],
            path=_validate_unit_path(u["path"]),
            intent=u["intent"],
            scope=u.get("scope", "global"),
            audience=u.get("audience", []),
            validated=_to_date(u.get("validated")),
            depends_on=u.get("depends_on", []),
            supersedes=u.get("supersedes"),
            triggers=u.get("triggers", []),
        )
        for u in data.get("units", [])
    ]
    relationships = [
        Relationship(from_id=r["from"], to_id=r["to"], type=r["type"])
        for r in data.get("relationships", [])
    ]
    return KnowledgeManifest(
        project=data["project"],
        version=data.get("version", ""),
        kcp_version=data.get("kcp_version"),
        updated=_to_date(data.get("updated")),
        units=units,
        relationships=relationships,
    )
