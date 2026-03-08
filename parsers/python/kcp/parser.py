from datetime import date
from pathlib import Path, PurePosixPath
from typing import Optional, Union

import yaml

from .model import (
    Auth, AuthMethod, Compliance, Delegation, KnowledgeManifest, KnowledgeUnit,
    Relationship, Trust, TrustAudit, TrustProvenance,
)


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


def _parse_trust(data: Optional[dict]) -> Optional[Trust]:
    """Parse the root-level trust block."""
    if data is None:
        return None
    provenance = None
    prov_data = data.get("provenance")
    if prov_data is not None:
        provenance = TrustProvenance(
            publisher=prov_data.get("publisher"),
            publisher_url=prov_data.get("publisher_url"),
            contact=prov_data.get("contact"),
        )
    audit = None
    audit_data = data.get("audit")
    if audit_data is not None:
        audit = TrustAudit(
            agent_must_log=audit_data.get("agent_must_log"),
            require_trace_context=audit_data.get("require_trace_context"),
        )
    return Trust(provenance=provenance, audit=audit)


def _parse_auth(data: Optional[dict]) -> Optional[Auth]:
    """Parse the root-level auth block."""
    if data is None:
        return None
    methods = [
        AuthMethod(
            type=m["type"],
            issuer=m.get("issuer"),
            scopes=m.get("scopes", []),
            header=m.get("header"),
            registration_url=m.get("registration_url"),
        )
        for m in data.get("methods", [])
    ]
    return Auth(methods=methods)


def _parse_delegation(data: Optional[dict]) -> Optional[Delegation]:
    """Parse a delegation block (root-level or per-unit)."""
    if data is None:
        return None
    return Delegation(
        max_depth=data.get("max_depth"),
        require_capability_attenuation=data.get("require_capability_attenuation"),
        audit_chain=data.get("audit_chain"),
        human_in_the_loop=data.get("human_in_the_loop"),
    )


def _parse_compliance(data: Optional[dict]) -> Optional[Compliance]:
    """Parse a compliance block (root-level or per-unit)."""
    if data is None:
        return None
    return Compliance(
        data_residency=data.get("data_residency", []),
        sensitivity=data.get("sensitivity"),
        regulations=data.get("regulations", []),
        restrictions=data.get("restrictions", []),
    )


def parse_dict(data: dict) -> KnowledgeManifest:
    """Parse a knowledge manifest from a pre-loaded dict."""
    units = [
        KnowledgeUnit(
            id=u["id"],
            path=_validate_unit_path(u["path"]),
            intent=u["intent"],
            scope=u.get("scope", "global"),
            audience=u.get("audience", []),
            kind=u.get("kind"),
            format=u.get("format"),
            content_type=u.get("content_type"),
            language=u.get("language"),
            license=u.get("license"),
            validated=_to_date(u.get("validated")),
            update_frequency=u.get("update_frequency"),
            indexing=u.get("indexing"),
            depends_on=u.get("depends_on", []),
            supersedes=u.get("supersedes"),
            triggers=u.get("triggers", []),
            hints=u.get("hints"),
            access=u.get("access"),
            auth_scope=u.get("auth_scope"),
            sensitivity=u.get("sensitivity"),
            deprecated=u.get("deprecated"),
            payment=u.get("payment"),
            delegation=_parse_delegation(u.get("delegation")),
            compliance=_parse_compliance(u.get("compliance")),
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
        language=data.get("language"),
        license=data.get("license"),
        indexing=data.get("indexing"),
        hints=data.get("hints"),
        trust=_parse_trust(data.get("trust")),
        auth=_parse_auth(data.get("auth")),
        delegation=_parse_delegation(data.get("delegation")),
        compliance=_parse_compliance(data.get("compliance")),
        payment=data.get("payment"),
        units=units,
        relationships=relationships,
    )
