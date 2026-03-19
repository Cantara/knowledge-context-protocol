from datetime import date
from pathlib import Path, PurePosixPath
from typing import Optional, Union

import yaml

from .model import (
    Auth, AuthMethod, Authority, Compliance, Delegation, Discovery, ExternalDependency,
    ExternalRelationship, FreshnessPolicy, KnowledgeManifest, KnowledgeUnit, ManifestRef,
    RateLimit, RateLimits, Relationship, Trust, TrustAudit, TrustProvenance, Visibility,
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


def _parse_rate_limits(data: Optional[dict]) -> Optional[RateLimits]:
    """Parse a rate_limits block (root-level or per-unit). See SPEC.md §4.15."""
    if data is None:
        return None
    default_data = data.get("default")
    if default_data is None:
        return RateLimits()
    return RateLimits(
        default=RateLimit(
            requests_per_minute=default_data.get("requests_per_minute"),
            requests_per_day=default_data.get("requests_per_day"),
        )
    )


def _parse_freshness_policy(data: Optional[dict]) -> Optional[FreshnessPolicy]:
    """Parse a freshness_policy block (root-level or per-unit). See SPEC.md §3.7 (v0.11)."""
    if data is None:
        return None
    return FreshnessPolicy(
        max_age_days=data.get("max_age_days"),
        on_stale=data.get("on_stale"),
        review_contact=data.get("review_contact"),
    )


def _parse_visibility(data: Optional[dict]) -> Optional[Visibility]:
    """Parse a visibility block (root-level or per-unit). See SPEC.md §RFC-0009 (v0.12)."""
    if data is None:
        return None
    return Visibility(
        default_sensitivity=data.get("default"),
        conditions=data.get("conditions", []),
    )


def _parse_authority(data: Optional[dict]) -> Optional[Authority]:
    """Parse an authority block (root-level or per-unit). See SPEC.md §RFC-0009 (v0.12)."""
    if data is None:
        return None
    return Authority(
        read=data.get("read"),
        summarize=data.get("summarize"),
        modify=data.get("modify"),
        share_externally=data.get("share_externally"),
        execute=data.get("execute"),
    )


def _parse_discovery(data: Optional[dict]) -> Optional[Discovery]:
    """Parse a discovery block (root-level or per-unit). See SPEC.md §RFC-0012 (v0.12)."""
    if data is None:
        return None
    return Discovery(
        verification_status=data.get("verification_status"),
        source=data.get("source"),
        observed_at=data.get("observed_at"),
        verified_at=data.get("verified_at"),
        confidence=data.get("confidence"),
        contradicted_by=data.get("contradicted_by"),
    )


def _parse_external_dependency(data: dict) -> ExternalDependency:
    """Parse an external_depends_on entry."""
    return ExternalDependency(
        manifest=data["manifest"],
        unit=data["unit"],
        on_failure=data.get("on_failure", "skip"),
    )


def _parse_manifest_ref(data: dict) -> ManifestRef:
    """Parse a manifests block entry."""
    return ManifestRef(
        id=data["id"],
        url=data["url"],
        label=data.get("label"),
        relationship=data.get("relationship"),
        auth=_parse_auth(data.get("auth")),
        update_frequency=data.get("update_frequency"),
        local_mirror=data.get("local_mirror"),
        version_pin=data.get("version_pin"),
        version_policy=data.get("version_policy"),
    )


def _parse_external_relationship(data: dict) -> ExternalRelationship:
    """Parse an external_relationships entry."""
    return ExternalRelationship(
        from_manifest=data.get("from_manifest"),
        from_unit=data["from_unit"],
        to_manifest=data.get("to_manifest"),
        to_unit=data["to_unit"],
        type=data["type"],
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
            rate_limits=_parse_rate_limits(u.get("rate_limits")),
            delegation=_parse_delegation(u.get("delegation")),
            compliance=_parse_compliance(u.get("compliance")),
            external_depends_on=[
                _parse_external_dependency(ed)
                for ed in u.get("external_depends_on", [])
            ],
            requires_capabilities=u.get("requires_capabilities", []),
            freshness_policy=_parse_freshness_policy(u.get("freshness_policy")),
            visibility=_parse_visibility(u.get("visibility")),
            authority=_parse_authority(u.get("authority")),
            discovery=_parse_discovery(u.get("discovery")),
        )
        for u in data.get("units", [])
    ]
    relationships = [
        Relationship(from_id=r["from"], to_id=r["to"], type=r["type"])
        for r in data.get("relationships", [])
    ]
    manifests = [
        _parse_manifest_ref(m)
        for m in data.get("manifests", [])
    ]
    external_relationships = [
        _parse_external_relationship(er)
        for er in data.get("external_relationships", [])
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
        rate_limits=_parse_rate_limits(data.get("rate_limits")),
        units=units,
        relationships=relationships,
        manifests=manifests,
        external_relationships=external_relationships,
        freshness_policy=_parse_freshness_policy(data.get("freshness_policy")),
        visibility=_parse_visibility(data.get("visibility")),
        authority=_parse_authority(data.get("authority")),
        discovery=_parse_discovery(data.get("discovery")),
    )
