from datetime import date
from pathlib import Path, PurePosixPath
from typing import Optional, Union

import yaml

from .model import (
    AgentIdentity, Auth, AuthMethod, Authority, Compliance, ContentHash, ContentStructure,
    Delegation, Discovery, ExternalDependency, ExternalRelationship, FreshnessPolicy,
    KnowledgeManifest, KnowledgeUnit, ManifestRef, Payment, PaymentMethod, Serving,
    RateLimit, RateLimits, RateLimitHeaders, RateLimitTokens, RateLimitTokensTier, Relationship,
    Trust, TrustAudit, TrustAgentRequirements, TrustProvenance, Visibility,
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
            publisher_did=prov_data.get("publisher_did"),
        )
    audit = None
    audit_data = data.get("audit")
    if audit_data is not None:
        audit = TrustAudit(
            agent_must_log=audit_data.get("agent_must_log"),
            require_trace_context=audit_data.get("require_trace_context"),
            provides_access_receipts=audit_data.get("provides_access_receipts"),
            receipt_format=audit_data.get("receipt_format"),
        )
    agent_requirements = None
    ar_data = data.get("agent_requirements")
    if ar_data is not None:
        agent_requirements = TrustAgentRequirements(
            require_attestation=ar_data.get("require_attestation"),
            trusted_providers=ar_data.get("trusted_providers", []),
            attestation_url=ar_data.get("attestation_url"),
            attestation_jwks=ar_data.get("attestation_jwks"),
            propagate_to_governed=ar_data.get("propagate_to_governed"),
        )
    return Trust(provenance=provenance, audit=audit, agent_requirements=agent_requirements)


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
            trust_domain=m.get("trust_domain"),
            supported_methods=m.get("supported_methods", []),
            key_id=m.get("key_id"),
            algorithm=m.get("algorithm"),
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
        require_delegation_proof=data.get("require_delegation_proof"),
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


def _parse_rate_limit_tier(data: Optional[dict]) -> Optional[RateLimit]:
    if not isinstance(data, dict):
        return None
    return RateLimit(
        requests_per_minute=data.get("requests_per_minute"),
        requests_per_hour=data.get("requests_per_hour"),
        requests_per_day=data.get("requests_per_day"),
    )


def _parse_rate_limit_tokens_tier(data: Optional[dict]) -> Optional[RateLimitTokensTier]:
    if not isinstance(data, dict):
        return None
    return RateLimitTokensTier(
        tokens_per_minute=data.get("tokens_per_minute"),
        tokens_per_day=data.get("tokens_per_day"),
    )


def _parse_rate_limits(data: Optional[dict]) -> Optional[RateLimits]:
    """Parse a rate_limits block (root-level or per-unit). See SPEC.md §4.15."""
    if data is None:
        return None
    tokens = None
    tk = data.get("tokens")
    if isinstance(tk, dict):
        tokens = RateLimitTokens(
            default=_parse_rate_limit_tokens_tier(tk.get("default")),
            authenticated=_parse_rate_limit_tokens_tier(tk.get("authenticated")),
            premium=_parse_rate_limit_tokens_tier(tk.get("premium")),
        )
    headers = None
    hd = data.get("headers")
    if isinstance(hd, dict):
        headers = RateLimitHeaders(
            remaining=hd.get("remaining"),
            reset=hd.get("reset"),
            retry_after=hd.get("retry_after"),
        )
    return RateLimits(
        default=_parse_rate_limit_tier(data.get("default")),
        authenticated=_parse_rate_limit_tier(data.get("authenticated")),
        premium=_parse_rate_limit_tier(data.get("premium")),
        tokens=tokens,
        headers=headers,
        backoff=data.get("backoff"),
    )


def _parse_payment_method(data: dict) -> PaymentMethod:
    if not isinstance(data, dict):
        data = {}
    networks = data.get("networks")
    return PaymentMethod(
        type=data.get("type", ""),
        currency=data.get("currency"),
        price_per_request=data.get("price_per_request"),
        networks=[str(n) for n in networks] if isinstance(networks, list) else None,
        wallet=data.get("wallet"),
        provider=data.get("provider"),
        plans_url=data.get("plans_url"),
        free_tier=data.get("free_tier"),
        free_requests_per_day=data.get("free_requests_per_day"),
        upgrade_url=data.get("upgrade_url"),
    )


def _parse_payment(data: Optional[dict]) -> Optional[Payment]:
    """Parse a payment block (root-level or per-unit). See SPEC.md §4.14."""
    if not isinstance(data, dict):
        return None
    methods = data.get("methods")
    return Payment(
        default_tier=data.get("default_tier"),
        methods=[_parse_payment_method(m) for m in methods] if isinstance(methods, list) else None,
        billing_contact=data.get("billing_contact"),
    )


def _parse_serving(data: Optional[dict]) -> Optional[Serving]:
    """Parse a serving block (root-level). See SPEC.md §3.12 (v0.26)."""
    if not isinstance(data, dict):
        return None
    return Serving(
        manifest=[str(u) for u in data["manifest"]] if isinstance(data.get("manifest"), list) else None,
        mcp=[str(u) for u in data["mcp"]] if isinstance(data.get("mcp"), list) else None,
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
        verified_by=data.get("verified_by"),
        evidence=data.get("evidence"),
        confidence=data.get("confidence"),
        contradicted_by=data.get("contradicted_by"),
    )


def _as_string_list(raw, default=None):
    """Coerce a YAML value to a list of strings: a list passes through
    (elements stringified), a scalar becomes a single-element list, None
    yields ``default``. Mirrors the TypeScript parsers' asStringArray so a
    common authoring mistake (scalar where a list is expected) degrades
    identically across implementations instead of iterating characters.
    """
    if raw is None:
        return default
    if isinstance(raw, list):
        return [str(x) for x in raw if x is not None]
    return [str(raw)]


def _parse_content_structure(data) -> Optional[ContentStructure]:
    """Parse a content_structure block (per-unit). See RFC-0016 (v0.17).

    A non-mapping value (scalar, list) is treated as absent rather than
    crashing the parse — forward-compat, mirroring the TypeScript parsers.
    """
    if not isinstance(data, dict):
        return None
    return ContentStructure(
        primary=data.get("primary"),
        contains=_as_string_list(data.get("contains"), default=[]),
        density=data.get("density"),
    )


def _parse_content_hash(data) -> Optional[ContentHash]:
    """Parse a content_hash block (per-unit). See RFC-0019 (draft).

    A declared-but-malformed block (scalar, list) parses to an empty
    ``ContentHash`` so the validator can flag it; only an absent block
    parses to ``None``. Mirrors the TypeScript parsers.
    """
    if data is None:
        return None
    if not isinstance(data, dict):
        return ContentHash()
    return ContentHash(
        algorithm=str(data["algorithm"]) if data.get("algorithm") is not None else None,
        value=str(data["value"]) if data.get("value") is not None else None,
    )


def _parse_temporal(data) -> Optional["Temporal"]:
    """Parse a temporal block (unit or manifest root). See RFC-0010 / §4.22 (v0.19)."""
    if data is None:
        return None
    if not isinstance(data, dict):
        return None
    from kcp.model import Temporal
    return Temporal(
        valid_from=str(data["valid_from"]) if data.get("valid_from") is not None else None,
        valid_until=str(data["valid_until"]) if data.get("valid_until") is not None else None,
        recorded_at=str(data["recorded_at"]) if data.get("recorded_at") is not None else None,
        superseded_by=str(data["superseded_by"]) if data.get("superseded_by") is not None else None,
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
        temporal=_parse_temporal(data.get("temporal")),
        context=[str(c) for c in data["context"]] if isinstance(data.get("context"), list) else None,
        agent_identity=_parse_agent_identity(data.get("agent_identity")),
    )


def _parse_agent_identity(data: Optional[dict]) -> Optional[AgentIdentity]:
    """Parse a manifests[].agent_identity block (v0.24)."""
    if not isinstance(data, dict):
        return None
    return AgentIdentity(
        required=data.get("required"),
        credential_hint=data.get("credential_hint"),
        issuer_hint=data.get("issuer_hint"),
        docs_url=data.get("docs_url"),
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
            aliases=[str(a) for a in u["aliases"]] if isinstance(u.get("aliases"), list) else None,
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
            payment=_parse_payment(u.get("payment")),
            rate_limits=_parse_rate_limits(u.get("rate_limits")),
            delegation=_parse_delegation(u.get("delegation")),
            compliance=_parse_compliance(u.get("compliance")),
            auth=_parse_auth(u.get("auth")),
            external_depends_on=[
                _parse_external_dependency(ed)
                for ed in u.get("external_depends_on", [])
            ],
            requires_capabilities=u.get("requires_capabilities", []),
            freshness_policy=_parse_freshness_policy(u.get("freshness_policy")),
            visibility=_parse_visibility(u.get("visibility")),
            authority=_parse_authority(u.get("authority")),
            discovery=_parse_discovery(u.get("discovery")),
            not_for=_as_string_list(u.get("not_for"), default=[]),
            not_for_strict=None if u.get("not_for_strict") is None else bool(u.get("not_for_strict")),
            content_structure=_parse_content_structure(u.get("content_structure")),
            content_hash=_parse_content_hash(u.get("content_hash")),
            temporal=_parse_temporal(u.get("temporal")),
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
        payment=_parse_payment(data.get("payment")),
        rate_limits=_parse_rate_limits(data.get("rate_limits")),
        serving=_parse_serving(data.get("serving")),
        units=units,
        relationships=relationships,
        manifests=manifests,
        external_relationships=external_relationships,
        freshness_policy=_parse_freshness_policy(data.get("freshness_policy")),
        visibility=_parse_visibility(data.get("visibility")),
        authority=_parse_authority(data.get("authority")),
        discovery=_parse_discovery(data.get("discovery")),
        not_for=_as_string_list(data.get("not_for"), default=[]),
        temporal=_parse_temporal(data.get("temporal")),
    )
