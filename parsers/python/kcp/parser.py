from datetime import date
from pathlib import Path, PurePosixPath
from typing import List, Optional, Union

import re
import yaml

from .model import (
    ActionScope,
    Agent, AgentIdentity, Auth, AuthMethod, Authority, Compliance, ContentHash, ContentStructure,
    Delegation, Discovery, ExternalDependency, ExternalRelationship, FreshnessPolicy,
    GrantCeiling, GrantCeilingSource, KnowledgeManifest, KnowledgeUnit, ManifestRef, Payment,
    PaymentMethod, Serving, RateLimit, RateLimits, RateLimitHeaders, RateLimitTokens,
    PlaybookStep,
    RateLimitTokensTier, Relationship, TaskType,
    Spend,
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
        data = yaml.load(f, Loader=Yaml12SafeLoader)
    return parse_dict(data)


_YAML_12_BOOL = re.compile(r"^(?:true|True|TRUE|false|False|FALSE)$")


class Yaml12SafeLoader(yaml.SafeLoader):
    """A SafeLoader that resolves booleans per YAML 1.2, as SPEC.md §2 requires.

    PyYAML implements YAML 1.1, in which ``yes``/``no``/``on``/``off`` resolve to
    booleans. §2 mandates YAML 1.2, in which they are plain strings — and the JSON
    schema types these fields as ``boolean``, so such a value is a schema violation
    rather than a shorthand to be rescued.

    Left alone, the divergence is invisible from inside this package: PyYAML converts
    ``yes`` to ``True`` before any KCP code runs, so no coercion helper downstream can
    tell it from a manifest that wrote ``true``. The fix has to happen at the loader
    (#156).

    Only the boolean resolver is narrowed. Everything else — ints, floats, null, merge
    keys, timestamps — keeps PyYAML's behaviour, because §2's requirement bites here
    and nowhere this parser has been shown to diverge.
    """


def _install_yaml_12_bool_resolver() -> None:
    """Replace the 1.1 bool resolvers on Yaml12SafeLoader with the 1.2 core schema."""
    # yaml_implicit_resolvers is inherited by reference; copy before mutating or this
    # narrows SafeLoader for every other consumer in the process.
    Yaml12SafeLoader.yaml_implicit_resolvers = {
        ch: [(tag, rx) for (tag, rx) in resolvers if tag != "tag:yaml.org,2002:bool"]
        for ch, resolvers in yaml.SafeLoader.yaml_implicit_resolvers.items()
    }
    Yaml12SafeLoader.add_implicit_resolver(
        "tag:yaml.org,2002:bool", _YAML_12_BOOL, list("tTfF")
    )


_install_yaml_12_bool_resolver()


def _as_bool(value):
    """Return a bool, or None when the value is not one.

    The loader resolves booleans per YAML 1.2 (§2), so ``yes``/``no``/``on``/``off``
    arrive here as strings and are rejected exactly as a typo is. An earlier revision
    mapped those words to booleans, which made the three parsers agree — on an answer
    the schema rejects (#156).

    None means the field reads as *undeclared*, so the unit falls back to its documented
    default rather than silently switching a flag on. That a rejected value is silent
    rather than reported is a known gap: the parse layer has no diagnostics channel.
    """
    return value if isinstance(value, bool) else None


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
            agent_must_log=_as_bool(audit_data.get("agent_must_log")),
            require_trace_context=_as_bool(audit_data.get("require_trace_context")),
            provides_access_receipts=_as_bool(audit_data.get("provides_access_receipts")),
            receipt_format=audit_data.get("receipt_format"),
        )
    agent_requirements = None
    ar_data = data.get("agent_requirements")
    if ar_data is not None:
        agent_requirements = TrustAgentRequirements(
            require_attestation=_as_bool(ar_data.get("require_attestation")),
            trusted_providers=ar_data.get("trusted_providers", []),
            attestation_url=ar_data.get("attestation_url"),
            attestation_jwks=ar_data.get("attestation_jwks"),
            propagate_to_governed=_as_bool(ar_data.get("propagate_to_governed")),
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


def _parse_spend(data: Optional[dict]) -> Optional["Spend"]:
    if not isinstance(data, dict):
        return None
    return Spend(
        max_spend=data.get("max_spend"),
        allowed_vendors=data.get("allowed_vendors"),
        currency=data.get("currency"),
    )


def _parse_steps(data) -> Optional[List["PlaybookStep"]]:
    """§4.3b (v0.29, RFC-0027): the ordered composition a ``kind: playbook`` declares.

    Anything that is not a list yields None, matching ``_parse_action_scope``: a
    malformed block must not take down the whole parse, and None reads as "declares no
    steps", which the validator then rejects for a playbook. Entries that are not
    mappings, or carry no ``id``, are dropped rather than half-parsed — a step with no
    identity cannot be named by ``depends_on``, so it cannot join the graph at all.
    """
    if not isinstance(data, list):
        return None
    steps: List["PlaybookStep"] = []
    for item in data:
        if not isinstance(item, dict) or item.get("id") is None:
            continue
        steps.append(
            PlaybookStep(
                id=str(item["id"]),
                uses=item.get("uses"),
                action=item.get("action"),
                depends_on=item.get("depends_on"),
                authority_level=item.get("authority_level"),
                escalation=_parse_escalation(item.get("escalation")),
                success_condition=item.get("success_condition"),
                on_failure=item.get("on_failure"),
                timeout=item.get("timeout"),
            )
        )
    return steps


def _parse_escalation(raw) -> Optional[List[str]]:
    """§4.3b: a bare string is shorthand for a single-element list.

    The triggers are disjunctive, so a scalar and a one-element list mean the same
    thing. Normalising here means no consumer has to handle both shapes.
    """
    if raw is None:
        return None
    if isinstance(raw, str):
        return [raw]
    if isinstance(raw, list):
        return [str(x) for x in raw]
    return None


def _parse_action_scope(data: Optional[dict]) -> Optional["ActionScope"]:
    # A scalar or list where an object belongs yields None rather than raising: a
    # malformed envelope must not take down the whole parse, and None correctly reads
    # as "declares nothing", which authorizes nothing.
    if not isinstance(data, dict):
        return None
    return ActionScope(
        tools=data.get("tools"),
        paths=data.get("paths"),
        capabilities=data.get("capabilities"),
        spend=_parse_spend(data.get("spend")),
    )


def _parse_delegation(data: Optional[dict]) -> Optional[Delegation]:
    """Parse a delegation block (root-level or per-unit)."""
    if data is None:
        return None
    return Delegation(
        max_depth=data.get("max_depth"),
        require_capability_attenuation=data.get("require_capability_attenuation"),
        require_delegation_proof=data.get("require_delegation_proof"),
        audit_chain=_as_bool(data.get("audit_chain")),
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
        free_tier=_as_bool(data.get("free_tier")),
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


def _string_list_or_none(raw: object) -> Optional[list]:
    """v0.26: coerce a raw value into a string list for aliases/serving. A non-list is
    *absent* (None) and non-string entries are dropped — so the TypeScript, Python, and Java
    parsers agree byte-for-byte on malformed input rather than one coercing a scalar to a
    one-element list while another drops it. Structural validity (must be a list of strings)
    is the JSON schema's job; this keeps the runtime parsers in lockstep."""
    if not isinstance(raw, list):
        return None
    return [x for x in raw if isinstance(x, str)]


def _parse_serving(data: Optional[dict]) -> Optional[Serving]:
    """Parse a serving block (root-level). See SPEC.md §3.12 (v0.26)."""
    if not isinstance(data, dict):
        return None
    return Serving(
        manifest=_string_list_or_none(data.get("manifest")),
        mcp=_string_list_or_none(data.get("mcp")),
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
        required=_as_bool(data.get("required")),
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


def _parse_task_type(data: dict) -> TaskType:
    """§3.13 (RFC-0025, v0.27): a task-type declaration."""
    return TaskType(
        id=str(data.get("id") or ""),
        intent=str(data["intent"]) if data.get("intent") is not None else None,
        authority_level=str(data["authority_level"]) if data.get("authority_level") is not None else None,
    )


def _parse_agent(data: dict) -> Agent:
    """§3.13 (RFC-0025, v0.27): an agent declaration (Capability Profile)."""
    return Agent(
        id=str(data.get("id") or ""),
        name=str(data["name"]) if data.get("name") is not None else None,
        authority_level=str(data["authority_level"]) if data.get("authority_level") is not None else None,
    )


def _parse_grant_ceiling_source(data: dict) -> GrantCeilingSource:
    """§3.13 (RFC-0025, v0.27): one source in a grant_ceiling minimum computation."""
    return GrantCeilingSource(
        id=str(data.get("id") or ""),
        authority_level=str(data["authority_level"]) if data.get("authority_level") is not None else None,
        unit_ref=str(data["unit_ref"]) if data.get("unit_ref") is not None else None,
        task_type_ref=str(data["task_type_ref"]) if data.get("task_type_ref") is not None else None,
        agent_ref=str(data["agent_ref"]) if data.get("agent_ref") is not None else None,
    )


def _parse_grant_ceiling(data: Optional[dict]) -> Optional[GrantCeiling]:
    """§3.13 (RFC-0025, v0.27): multi-source minimum ceiling computation."""
    if not isinstance(data, dict):
        return None
    sources = data.get("sources") or []
    return GrantCeiling(
        sources=[_parse_grant_ceiling_source(s) for s in sources],
        mandatory_sources=_string_list_or_none(data.get("mandatory_sources")),
    )


def parse_dict(data: dict) -> KnowledgeManifest:
    """Parse a knowledge manifest from a pre-loaded dict."""
    units = [
        KnowledgeUnit(
            id=u["id"],
            aliases=_string_list_or_none(u.get("aliases")),
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
            deprecated=_as_bool(u.get("deprecated")),
            payment=_parse_payment(u.get("payment")),
            rate_limits=_parse_rate_limits(u.get("rate_limits")),
            action_scope=_parse_action_scope(u.get("action_scope")),
            steps=_parse_steps(u.get("steps")),
            load_eligible=_as_bool(u.get("load_eligible")),
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
            not_for_strict=_as_bool(u.get("not_for_strict")),
            content_structure=_parse_content_structure(u.get("content_structure")),
            content_hash=_parse_content_hash(u.get("content_hash")),
            temporal=_parse_temporal(u.get("temporal")),
            authority_level=str(u["authority_level"]) if u.get("authority_level") is not None else None,
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
        authority_level_scale=_string_list_or_none(data.get("authority_level_scale")),
        task_types=[_parse_task_type(tt) for tt in data.get("task_types", [])],
        agents=[_parse_agent(a) for a in data.get("agents", [])],
        grant_ceiling=_parse_grant_ceiling(data.get("grant_ceiling")),
    )
