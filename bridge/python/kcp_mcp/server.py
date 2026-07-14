"""
KCP MCP server — low-level Server API matching the TypeScript bridge pattern.
"""
import json
import sys
import re
from datetime import date as _date
from datetime import datetime as _datetime
from datetime import timezone as _timezone
from pathlib import Path

from mcp.server import Server
from mcp.server.lowlevel.server import ReadResourceContents
from mcp.types import (
    Annotations,
    GetPromptResult,
    Prompt,
    PromptArgument,
    PromptMessage,
    Resource,
    TextContent,
    Tool,
)
from pydantic import AnyUrl

from kcp import parse
from kcp.model import KnowledgeManifest, Temporal

from .commands import (
    CommandManifest,
    format_syntax_block,
    load_command_manifests,
    lookup_command,
)
from .content import PathTraversalError, ResourceNotFoundError, read_resource_content
from .mapper import (
    build_manifest_json,
    manifest_resource_dict,
    manifest_uri,
    project_slug,
    resolve_mime,
    unit_resource_dict,
    unit_uri,
)


def _build_resource(d: dict) -> Resource:
    ann = d["annotations"]
    last_mod = ann.get("lastModified")
    # Annotations has extra="allow" so lastModified is stored as an extra field
    annotations = Annotations(
        audience=ann["audience"],
        priority=ann["priority"],
        **({"lastModified": last_mod} if last_mod else {}),
    )
    return Resource(
        uri=AnyUrl(d["uri"]),
        name=d["name"],
        title=d.get("title"),
        description=d.get("description"),
        mimeType=d.get("mimeType"),
        annotations=annotations,
    )


def create_server(
    manifest_path: Path,
    agent_only: bool = False,
    warn_on_validation: bool = True,
    sub_manifests: list[Path] | None = None,
    commands_dir: Path | None = None,
) -> Server:
    """
    Parse knowledge.yaml and return a configured MCP Server.

    Args:
        manifest_path:      Path to knowledge.yaml
        agent_only:         If True, only expose units with audience: [agent]
        warn_on_validation: Log validation warnings to stderr
        sub_manifests:      Additional manifest paths whose units merge into the primary namespace
        commands_dir:       Directory of kcp-commands YAML files (enables get_command_syntax tool)
    """
    if sub_manifests is None:
        sub_manifests = []

    manifest: KnowledgeManifest = parse(manifest_path)
    manifest_dir = manifest_path.parent
    slug = project_slug(manifest.project)
    m_uri = manifest_uri(slug)

    # Maps unit_id → (unit, unit_manifest_dir).  Primary manifest wins on duplicate id.
    unit_context: dict[str, tuple] = {
        u.id: (u, manifest_dir) for u in manifest.units
    }

    # Maps unit_id → the federation source's temporal window (manifests[].temporal, §3.6),
    # for units that came from a sub-manifest associated with a manifests[] entry. Primary
    # units and sub-manifests not declared as a local_mirror have no entry (always included).
    source_temporal: dict[str, Temporal] = {}

    # §3.2 / C20 (v0.22): attestation policy from the primary manifest. The bridge checks a
    # credential was presented before serving restricted units; it never calls attestation_url.
    agent_req = manifest.trust.agent_requirements if manifest.trust else None
    require_attestation = bool(agent_req and agent_req.require_attestation)

    # manifests[].id → temporal, for supersession resolution (issue #98 F4).
    ref_temporal_by_id: dict[str, Temporal] = {
        ref.id: ref.temporal for ref in manifest.manifests
    }

    # Associate each federation entry's local_mirror with its manifests[] declaration so a
    # sub-manifest loaded from disk inherits its source temporal window (§3.6 / C18). Path.resolve()
    # canonicalises (follows symlinks), so the association can't fail open on a symlinked path.
    mirror_to_ref: dict[Path, object] = {}
    for ref in manifest.manifests:
        if ref.local_mirror:
            mirror_to_ref[(manifest_dir / ref.local_mirror).resolve()] = ref

    # Load sub-manifests and merge units
    added_total = 0
    for sub_path in sub_manifests:
        sub_path = Path(sub_path).resolve()
        sub_dir = sub_path.parent
        source_ref = mirror_to_ref.get(sub_path)
        # A federation that declares mirrors but loads a sub-manifest matching none means that
        # sub-manifest's units would bypass temporal filtering — surface it (issue #98 F2).
        if source_ref is None and mirror_to_ref:
            sys.stderr.write(
                f"  [kcp-mcp] warning: sub-manifest {sub_path} matched no manifests[].local_mirror — "
                f"its units are not subject to federation temporal filtering (§3.6 / C18)\n"
            )
        try:
            sub_manifest: KnowledgeManifest = parse(sub_path)
        except Exception as e:
            sys.stderr.write(
                f"  [kcp-mcp] warning: could not load sub-manifest {sub_path}: {e}\n"
            )
            continue
        added = 0
        for unit in sub_manifest.units:
            if unit.id in unit_context:
                sys.stderr.write(
                    f"  [kcp-mcp] warning: duplicate unit id '{unit.id}' in {sub_path} — skipping\n"
                )
                continue
            unit_context[unit.id] = (unit, sub_dir)
            if source_ref is not None and getattr(source_ref, "temporal", None) is not None:
                source_temporal[unit.id] = source_ref.temporal
            added += 1
        added_total += added
        sys.stderr.write(
            f"  [kcp-mcp] loaded sub-manifest {sub_path} — {added} unit(s)\n"
        )

    total_units = len(unit_context)

    # Load command manifests (optional — enables get_command_syntax tool)
    command_manifests: dict[str, CommandManifest] = {}
    if commands_dir is not None:
        command_manifests = load_command_manifests(commands_dir)

    # The resource list is built per request in list_resources() so it can omit temporally
    # excluded units against the current date (issue #98 F1) — not cached statically here.

    # Log startup info
    agent_note = " [agent-only]" if agent_only else ""
    sub_note = (
        f" ({len(manifest.units)} primary + {added_total} from {len(sub_manifests)} sub-manifest(s))"
        if sub_manifests else ""
    )
    sys.stderr.write(
        f"[kcp-mcp] Serving '{manifest.project}' — {total_units} unit(s){sub_note}{agent_note}\n"
        f"[kcp-mcp] Start with: {m_uri}\n"
    )

    server = Server(f"kcp-{slug}")

    @server.list_resources()
    async def list_resources() -> list[Resource]:
        # F1: omit temporally-excluded units (source window closed/superseded, or unit window
        # invalid) as of today — not just hidden from search.
        today = _effective_today()
        out: list[Resource] = [_build_resource(manifest_resource_dict(slug, manifest))]
        for uid, (unit, _d) in unit_context.items():
            if agent_only and "agent" not in unit.audience:
                continue
            if not _unit_servable(unit, source_temporal.get(uid), today, ref_temporal_by_id):
                continue
            out.append(_build_resource(unit_resource_dict(slug, unit)))
        return out

    @server.read_resource()
    async def read_resource(uri: AnyUrl):
        uri_str = str(uri)

        # Manifest meta-resource
        if uri_str == m_uri:
            return [ReadResourceContents(
                content=build_manifest_json(manifest, slug),
                mime_type="application/json",
            )]

        # Unit resource
        prefix = f"knowledge://{slug}/"
        if not uri_str.startswith(prefix):
            raise ValueError(f"Unknown resource URI: {uri_str}")

        unit_id = uri_str[len(prefix):]
        ctx = unit_context.get(unit_id)
        if ctx is None:
            raise ValueError(f"No unit with id '{unit_id}'")

        unit, unit_dir = ctx
        # F1: refuse a temporally-excluded unit here too, not just in search.
        today = _effective_today()
        if not _unit_servable(unit, source_temporal.get(unit_id), today, ref_temporal_by_id):
            raise ValueError(
                f"Unit '{unit_id}' is outside its temporal validity window as of {today}"
            )
        # C20: a resource read carries no attestation channel — a restricted unit under a
        # manifest requiring attestation is fetched via get_unit with an attestation argument.
        if _unit_needs_attestation(unit, require_attestation):
            raise ValueError(
                f"Unit '{unit_id}' requires agent attestation (§3.2); fetch it via the get_unit tool with an 'attestation' argument"
            )
        mime = resolve_mime(unit)
        try:
            content, is_binary = read_resource_content(unit_dir, unit.path, mime)
        except ResourceNotFoundError as e:
            raise ValueError(str(e)) from e
        except PathTraversalError as e:
            raise ValueError(str(e)) from e

        if is_binary:
            # content is base64 str; decode back to bytes for the SDK to re-encode
            import base64
            return [ReadResourceContents(content=base64.b64decode(content), mime_type=mime)]
        else:
            return [ReadResourceContents(content=content, mime_type=mime)]

    # ── Tools ────────────────────────────────────────────────────────────────

    @server.list_tools()
    async def list_tools() -> list[Tool]:
        tools = [
            Tool(
                name="search_knowledge",
                description=(
                    "Search knowledge units by query. Matches against triggers, intent, and id."
                ),
                inputSchema={
                    "type": "object",
                    "properties": {
                        "query": {
                            "type": "string",
                            "description": "Search terms (space-separated)",
                        },
                        "audience": {
                            "type": "string",
                            "description": "Filter by audience: agent | developer | architect | operator | human",
                        },
                        "scope": {
                            "type": "string",
                            "description": "Filter by scope: global | project | module",
                        },
                        "sensitivity_max": {
                            "type": "string",
                            "description": "Maximum sensitivity to include: public | internal | confidential | restricted. Units above this level are excluded.",
                        },
                        "exclude_deprecated": {
                            "type": "boolean",
                            "description": "Exclude units marked deprecated: true. Default: true.",
                        },
                        "as_of": {
                            "type": "string",
                            "description": "ISO 8601 date for point-in-time temporal query (§15.13). Default: today.",
                        },
                        "include_all_temporal": {
                            "type": "boolean",
                            "description": "If true, skip temporal filtering and return all units regardless of valid_from/valid_until (§15.13). Mutually exclusive with as_of.",
                        },
                        "attestation": {
                            "type": "string",
                            "description": "Agent attestation credential (§3.2). When presented, restricted units are not marked requires_attestation. Presence is checked; the credential is not verified.",
                        },
                    },
                    "required": ["query"],
                },
            ),
            Tool(
                name="get_unit",
                description="Fetch the content of a specific knowledge unit by its id.",
                inputSchema={
                    "type": "object",
                    "properties": {
                        "unit_id": {
                            "type": "string",
                            "description": "The unit id from search_knowledge results",
                        },
                        "attestation": {
                            "type": "string",
                            "description": "Agent attestation credential (§3.2). Required to fetch access: restricted units when the manifest sets require_attestation. Presence is checked; the credential is not verified.",
                        },
                    },
                    "required": ["unit_id"],
                },
            ),
            Tool(
                name="get_command_syntax",
                description="Get syntax guidance for a CLI command from kcp-commands manifests.",
                inputSchema={
                    "type": "object",
                    "properties": {
                        "command": {
                            "type": "string",
                            "description": "Command name e.g. 'git commit', 'mvn', 'docker'",
                        },
                    },
                    "required": ["command"],
                },
            ),
            Tool(
                name="list_manifests",
                description=(
                    "List the sub-manifests declared in this knowledge.yaml federation block."
                ),
                inputSchema={
                    "type": "object",
                    "properties": {
                        "as_of": {
                            "type": "string",
                            "description": "ISO 8601 date (YYYY-MM-DD) to evaluate temporally_active against (§3.6). Default: today (UTC).",
                        },
                    },
                    "required": [],
                },
            ),
        ]
        return tools

    @server.call_tool()
    async def call_tool(name: str, arguments: dict) -> list[TextContent]:
        if name == "search_knowledge":
            return _handle_search_knowledge(unit_context, slug, arguments or {}, source_temporal, ref_temporal_by_id, require_attestation)
        if name == "get_unit":
            return _handle_get_unit(unit_context, arguments or {}, source_temporal, ref_temporal_by_id, require_attestation, agent_req)
        if name == "get_command_syntax":
            return _handle_get_command_syntax(command_manifests, arguments or {})
        if name == "list_manifests":
            return _handle_list_manifests(manifest, arguments or {}, ref_temporal_by_id)
        return [TextContent(type="text", text=f"Unknown tool: {name}")]

    # ── Prompts ──────────────────────────────────────────────────────────────

    @server.list_prompts()
    async def list_prompts() -> list[Prompt]:
        return [
            Prompt(
                name="sdd-review",
                description="Review code or architecture using SDD (Skill-Driven Development) methodology",
                arguments=[
                    PromptArgument(
                        name="focus",
                        description="Focus area: architecture | quality | security | performance",
                        required=False,
                    ),
                ],
            ),
            Prompt(
                name="kcp-explore",
                description="Explore available knowledge units for a topic",
                arguments=[
                    PromptArgument(
                        name="topic",
                        description="Topic to explore e.g. 'authentication', 'deployment'",
                        required=True,
                    ),
                ],
            ),
        ]

    @server.get_prompt()
    async def get_prompt(name: str, arguments: dict[str, str] | None = None) -> GetPromptResult:
        args = arguments or {}
        if name == "sdd-review":
            return _handle_sdd_review(args)
        if name == "kcp-explore":
            return _handle_kcp_explore(args)
        raise ValueError(f"Unknown prompt: {name!r}")

    return server


_SENSITIVITY_ORDER = {"public": 0, "internal": 1, "confidential": 2, "restricted": 3}


def _score_unit(unit, terms: list[str], slug: str) -> dict:
    """Score a unit against query terms (RFC-0007 algorithm)."""
    score = 0
    match_reason: list[str] = []
    lower_triggers = [t.lower() for t in (unit.triggers or [])]
    lower_intent = (unit.intent or "").lower()
    lower_id = unit.id.lower()
    lower_path = (unit.path or "").lower()
    aliases = unit.aliases or []
    # §4.2a (v0.26): an alias is an alternative reference to this unit; a query term that hits
    # one scores like an id match and surfaces the matched alias in the result.
    matched_alias: str | None = None

    for term in terms:
        lterm = term.lower()

        # Trigger match — 5 pts per matching trigger
        for trig in lower_triggers:
            if lterm in trig:
                score += 5
                if "trigger" not in match_reason:
                    match_reason.append("trigger")

        if lterm in lower_intent:
            score += 3
            if "intent" not in match_reason:
                match_reason.append("intent")

        if lterm in lower_id:
            score += 1
            if "id" not in match_reason:
                match_reason.append("id")

        if lterm in lower_path:
            score += 1
            if "path" not in match_reason:
                match_reason.append("path")

        # Alias match — 1 pt; prefer an exact alias hit for matched_alias surfacing (§4.2a).
        for alias in aliases:
            lalias = alias.lower()
            if lterm in lalias:
                score += 1
                if "alias" not in match_reason:
                    match_reason.append("alias")
                if matched_alias is None or lalias == lterm:
                    matched_alias = alias

    hints = unit.hints or {}
    token_estimate = hints.get("token_estimate")
    summary_unit = hints.get("summary_unit")

    result = {
        "id": unit.id,
        "intent": unit.intent,
        "path": unit.path,
        "uri": unit_uri(slug, unit.id),
        "score": score,
        "match_reason": match_reason,
        "token_estimate": int(token_estimate) if token_estimate is not None else None,
        "summary_unit": str(summary_unit) if summary_unit is not None else None,
        "caution": None,
    }
    if matched_alias is not None:
        result["matched_alias"] = matched_alias
    return result


def _is_temporally_included(t, as_of: str) -> bool:
    """Unified bi-temporal inclusion check (§4.22 unit / §3.6 source, §15.13). True when the
    `temporal` block is valid on as_of; a None block is always included. One predicate for both
    unit and source temporal — callers pass unit.temporal or ref.temporal (issue #98 F7)."""
    if t is None:
        return True
    if t.valid_from is not None and t.valid_from > as_of:
        return False
    if t.valid_until is not None and t.valid_until < as_of:
        return False
    return True


def _effective_today() -> str:
    """UTC effective date (YYYY-MM-DD). Pinned to UTC so all three bridges agree at a
    timezone boundary (issue #98 F6)."""
    return _datetime.now(_timezone.utc).date().isoformat()


_AS_OF_RE = re.compile(r"^\d{4}-\d{2}-\d{2}([T ][0-9:.+\-Z]*)?$")


def _is_valid_as_of(s: str) -> bool:
    """Validate as_of as an ISO-8601 date or datetime; reject unparseable values rather than
    feeding them to lexicographic comparison (issue #98 F3)."""
    if not _AS_OF_RE.match(s):
        return False
    try:
        _datetime.fromisoformat(s.replace("Z", "+00:00") if len(s) > 10 else s)
        return True
    except ValueError:
        return False


def _is_source_servable(temporal, as_of: str, ref_temporal_by_id: dict) -> bool:
    """§3.6 / C18 manifest-level inclusion with supersession (issue #98 F4). A source is included
    iff its window is valid on as_of AND it is not superseded by another manifests[] entry whose
    own window is active — once a successor is live, the superseded source is dropped, not co-served."""
    if not _is_temporally_included(temporal, as_of):
        return False
    succ = getattr(temporal, "superseded_by", None) if temporal is not None else None
    if succ is not None and succ in ref_temporal_by_id and _is_temporally_included(ref_temporal_by_id[succ], as_of):
        return False
    return True


def _unit_servable(unit, source_t, as_of: str, ref_temporal_by_id: dict) -> bool:
    """A unit is servable on a date iff its federation source is servable (window valid AND not
    superseded by an active successor) AND its own unit-level window is valid. Every retrieval
    path gates on this so get_unit / read_resource / list_resources can't leak temporally
    excluded content that search_knowledge already hides (issue #98 F1)."""
    return _is_source_servable(source_t, as_of, ref_temporal_by_id) and _is_temporally_included(
        getattr(unit, "temporal", None), as_of
    )


def _unit_needs_attestation(unit, require_attestation: bool) -> bool:
    """§3.2 / C20: a restricted unit under a manifest requiring attestation must not be served
    unless the client presents a credential. The bridge checks presence only — it never calls
    attestation_url (verification is the agent's job)."""
    return require_attestation and getattr(unit, "access", None) == "restricted"


def _attestation_presented(arguments: dict) -> bool:
    a = arguments.get("attestation")
    return a is not None and a != ""


def _attestation_requirement_data(agent_req) -> dict:
    out = {"require_attestation": True}
    if agent_req is not None:
        if getattr(agent_req, "trusted_providers", None):
            out["trusted_providers"] = agent_req.trusted_providers
        if getattr(agent_req, "attestation_url", None):
            out["attestation_url"] = agent_req.attestation_url
    return out


def _match_not_for(unit, terms: list[str]) -> str | None:
    """Return the first not_for phrase matched by any query term, or None (§15.11)."""
    not_for = getattr(unit, "not_for", None) or []
    for phrase in not_for:
        lphrase = phrase.lower()
        for term in terms:
            if term.lower() in lphrase:
                return phrase
    return None


def _handle_search_knowledge(
    unit_context: dict,
    slug: str,
    arguments: dict,
    source_temporal: dict | None = None,
    ref_temporal_by_id: dict | None = None,
    require_attestation: bool = False,
) -> list[TextContent]:
    """Search knowledge units by query (RFC-0007 query baseline)."""
    query = arguments.get("query", "").strip()
    if not query:
        return [TextContent(type="text", text="Please provide a search query.")]

    audience_filter = arguments.get("audience")
    scope_filter = arguments.get("scope")
    sensitivity_max = arguments.get("sensitivity_max")
    exclude_deprecated = arguments.get("exclude_deprecated", True)
    as_of = arguments.get("as_of")
    include_all_temporal = arguments.get("include_all_temporal", False)
    if as_of is not None and include_all_temporal:
        return [TextContent(type="text", text=json.dumps({
            "error": "temporal_query_conflict",
            "message": "as_of and include_all_temporal are mutually exclusive.",
        }))]
    # F3: reject an unparseable as_of rather than feeding it to lexicographic comparison.
    if as_of is not None and not _is_valid_as_of(as_of):
        return [TextContent(type="text", text=json.dumps({
            "error": "invalid_as_of",
            "message": f"as_of must be an ISO-8601 date (YYYY-MM-DD); got {as_of!r}",
        }))]
    temporal_date = as_of if as_of is not None else _effective_today()

    source_temporal = source_temporal or {}
    ref_temporal_by_id = ref_temporal_by_id or {}
    terms = query.split()
    results = []

    for unit_id, (unit, _unit_dir) in unit_context.items():
        # §3.6 / C18: manifest-level (federation source) temporal filter, applied before
        # scoring and before unit-level temporal. A source outside its window (or superseded
        # by an active successor, F4) is skipped entirely. Bypassed by include_all_temporal.
        if not include_all_temporal and not _is_source_servable(
            source_temporal.get(unit_id), temporal_date, ref_temporal_by_id
        ):
            continue
        # Filter: audience
        if audience_filter and audience_filter not in (unit.audience or []):
            continue
        # Filter: scope
        if scope_filter and getattr(unit, "scope", None) != scope_filter:
            continue
        # Filter: exclude_deprecated (default True)
        if exclude_deprecated and getattr(unit, "deprecated", None) is True:
            continue
        # Filter: sensitivity_max
        if sensitivity_max is not None:
            max_level = _SENSITIVITY_ORDER.get(sensitivity_max, 99)
            unit_sensitivity = getattr(unit, "sensitivity", None) or "public"
            unit_level = _SENSITIVITY_ORDER.get(unit_sensitivity, 0)
            if unit_level > max_level:
                continue

        scored = _score_unit(unit, terms, slug)
        if scored["score"] > 0:
            results.append(scored)

    # §15.11 not_for filter: strict exclusion, soft demotion (score → not_for → top-N per §15.12)
    final_results = []
    for r in results:
        unit, _ = unit_context.get(r["id"], (None, None))
        matched = _match_not_for(unit, terms) if unit else None
        if not matched:
            final_results.append(r)
            continue
        if getattr(unit, "not_for_strict", False):
            continue
        final_results.append({**r, "score": max(1, r["score"] // 2), "caution": f"not_for match: '{matched}'"})

    # §15.13 temporal filter: applied after not_for, before top-N cut
    if not include_all_temporal:
        final_results = [
            r for r in final_results
            if _is_temporally_included(
                getattr(unit_context.get(r["id"], (None, None))[0], "temporal", None), temporal_date
            )
        ]
    else:
        # F5: mark every result so a bypassed (possibly out-of-window) result is observable.
        marked = []
        for r in final_results:
            existing = r.get("caution")
            note = f"{existing}; temporal filtering bypassed" if existing else "temporal filtering bypassed (include_all_temporal)"
            marked.append({**r, "caution": note})
        final_results = marked

    if not final_results:
        ids = ", ".join(unit_context.keys())
        return [TextContent(type="text", text=f'No units matched query "{query}". Available units: {ids}')]

    # C20: mark restricted units needing attestation (dropped if a credential was presented).
    attested = _attestation_presented(arguments)
    if require_attestation and not attested:
        for r in final_results:
            u = unit_context.get(r["id"], (None, None))[0]
            if u is not None and getattr(u, "access", None) == "restricted":
                r["requires_attestation"] = True

    final_results.sort(key=lambda r: r["score"], reverse=True)
    top5 = final_results[:5]

    return [TextContent(type="text", text=json.dumps(top5, indent=2))]


def _handle_get_unit(
    unit_context: dict,
    arguments: dict,
    source_temporal: dict | None = None,
    ref_temporal_by_id: dict | None = None,
    require_attestation: bool = False,
    agent_req=None,
) -> list[TextContent]:
    """Fetch the content of a specific knowledge unit by id."""
    source_temporal = source_temporal or {}
    ref_temporal_by_id = ref_temporal_by_id or {}
    requested_id = arguments.get("unit_id", "").strip()
    # §4.2a (v0.26): resolve a declared alias to its canonical unit. The canonical id wins;
    # matched_alias is set only when the lookup came in via an alias, and the first-declared
    # unit wins an alias collision (mirrors the duplicate-id rule).
    matched_alias: str | None = None
    unit_id = requested_id
    ctx = unit_context.get(requested_id)
    if ctx is None:
        for uid, (u, _d) in unit_context.items():
            if requested_id in (u.aliases or []):
                ctx = unit_context[uid]
                matched_alias = requested_id
                unit_id = uid
                break
    if ctx is None:
        ids = ", ".join(unit_context.keys())
        return [TextContent(type="text", text=f'Unit not found: "{requested_id}". Available units: {ids}')]

    unit, unit_dir = ctx
    # F1: refuse a temporally-excluded unit by id, matching search / read_resource. Default
    # effective date is today (UTC); historical access is via search_knowledge's as_of.
    today = _effective_today()
    if not _unit_servable(unit, source_temporal.get(unit_id), today, ref_temporal_by_id):
        return [TextContent(type="text", text=json.dumps({
            "error": "temporally_unavailable",
            "message": f"Unit '{unit_id}' is outside its temporal validity window as of {today}",
        }))]
    # C20: refuse restricted-unit content unless an attestation credential is presented.
    if _unit_needs_attestation(unit, require_attestation) and not _attestation_presented(arguments):
        return [TextContent(type="text", text=json.dumps({
            "error": "attestation_required",
            "message": f"Unit '{unit_id}' is access: restricted and this manifest requires attestation (§3.2). Re-call get_unit with an 'attestation' argument.",
            "agent_requirements": _attestation_requirement_data(agent_req),
        }))]
    mime = resolve_mime(unit)
    try:
        content, is_binary = read_resource_content(unit_dir, unit.path, mime)
    except (ResourceNotFoundError, PathTraversalError) as e:
        return [TextContent(type="text", text=f"Error reading unit: {e}")]

    # §4.2a (v0.26): when the lookup resolved through an alias, lead with a metadata block
    # surfacing both the matched alias and the canonical id (L2). Direct id lookups are
    # unchanged — the content is the sole item, as before.
    alias_note = (
        [TextContent(type="text", text=json.dumps({"matched_alias": matched_alias, "canonical_id": unit_id}))]
        if matched_alias is not None else []
    )

    if is_binary:
        return alias_note + [TextContent(type="text", text=f"[Binary content: {mime}, base64 length: {len(content)}]")]
    return alias_note + [TextContent(type="text", text=content)]


def _handle_get_command_syntax(
    command_manifests: dict,
    arguments: dict,
) -> list[TextContent]:
    """Return syntax guidance for a CLI command."""
    if not command_manifests:
        return [TextContent(type="text", text="No command manifests loaded \u2014 start kcp-mcp with --commands-dir")]

    cmd_query = arguments.get("command", "").strip()
    found = lookup_command(command_manifests, cmd_query)
    if found is None:
        available = ", ".join(sorted({m.command for m in command_manifests.values()}))
        return [TextContent(type="text", text=f'Unknown command: "{cmd_query}". Available commands: {available}')]

    return [TextContent(type="text", text=format_syntax_block(found))]


def _temporal_to_dict(temporal) -> dict | None:
    """Serialize a Temporal block to a plain dict for JSON output, or None when absent."""
    if temporal is None:
        return None
    out: dict = {}
    for field_name in ("valid_from", "valid_until", "recorded_at", "superseded_by"):
        value = getattr(temporal, field_name, None)
        if value is not None:
            out[field_name] = value
    return out


def _handle_list_manifests(
    manifest: KnowledgeManifest,
    arguments: dict | None = None,
    ref_temporal_by_id: dict | None = None,
) -> list[TextContent]:
    """Return JSON array of declared sub-manifests."""
    arguments = arguments or {}
    ref_temporal_by_id = ref_temporal_by_id or {r.id: r.temporal for r in manifest.manifests}
    # F9: temporally_active reflects as_of (else today, UTC), so it can't contradict a
    # search_knowledge call made with the same historical as_of. F4: supersession-aware.
    as_of = arguments.get("as_of")
    if as_of is not None and not _is_valid_as_of(as_of):
        return [TextContent(type="text", text=json.dumps({
            "error": "invalid_as_of",
            "message": f"as_of must be an ISO-8601 date (YYYY-MM-DD); got {as_of!r}",
        }))]
    lm_date = as_of if as_of is not None else _effective_today()
    entries = []
    for m in manifest.manifests:
        entry: dict = {
            "id": m.id,
            "url": m.url,
            "label": m.label,
            "relationship": m.relationship,
            "has_local_mirror": bool(m.local_mirror),
            "update_frequency": m.update_frequency,
            "version_pin": m.version_pin,
            "version_policy": m.version_policy,
            "temporal": _temporal_to_dict(m.temporal),
            "temporally_active": _is_source_servable(m.temporal, lm_date, ref_temporal_by_id),
        }
        entries.append(entry)
    return [TextContent(type="text", text=json.dumps(entries, indent=2))]


def _handle_sdd_review(args: dict) -> GetPromptResult:
    focus = args.get("focus", "architecture")
    focus_guidance: dict[str, str] = {
        "architecture": "\n".join([
            "1. **Intent Clarity**: Does each component have a single, clearly stated purpose?",
            "2. **Component Boundaries**: Are module boundaries clean? Can you describe each module's responsibility in one sentence?",
            "3. **Dependency Direction**: Do dependencies flow from concrete to abstract? Are there circular dependencies?",
            "4. **Knowledge Documentation**: Is there a knowledge.yaml or equivalent that maps the architecture for AI assistants?",
            "5. **Skill Decomposition**: Could an AI agent understand and modify each component independently?",
        ]),
        "quality": "\n".join([
            "1. **Test Coverage**: Are critical paths covered? Do tests verify intent, not implementation details?",
            "2. **Error Handling**: Are errors handled at the right level? Do error messages help diagnosis?",
            "3. **Naming**: Do names reflect domain concepts? Would a new developer understand the code from names alone?",
            "4. **Code Duplication**: Are there repeated patterns that should be extracted into shared utilities?",
            "5. **Documentation Freshness**: Does the documentation match the current implementation?",
        ]),
        "security": "\n".join([
            "1. **Input Validation**: Are all external inputs validated before use?",
            "2. **Authentication & Authorization**: Are auth boundaries clearly defined and enforced?",
            "3. **Secret Management**: Are secrets externalized? No hardcoded credentials?",
            "4. **Dependency Security**: Are dependencies up to date? Any known CVEs?",
            "5. **Path Traversal**: Are file paths validated against traversal attacks?",
        ]),
        "performance": "\n".join([
            "1. **Hot Paths**: Are the most-called code paths optimized? Are there unnecessary allocations?",
            "2. **Caching**: Are expensive computations cached appropriately? Is cache invalidation correct?",
            "3. **I/O Patterns**: Are I/O operations batched where possible? Any N+1 query patterns?",
            "4. **Concurrency**: Are concurrent operations safe? Are there potential deadlocks or race conditions?",
            "5. **Resource Cleanup**: Are resources (connections, file handles, timers) properly cleaned up?",
        ]),
    }
    criteria = focus_guidance.get(focus, focus_guidance["architecture"])
    text = "\n".join([
        f"## SDD Review: {focus}",
        "",
        "You are reviewing code using the Skill-Driven Development (SDD) methodology.",
        "SDD emphasizes clear intent, modular components that AI agents can understand,",
        "and structured knowledge documentation.",
        "",
        f"### Review Criteria ({focus}):",
        "",
        criteria,
        "",
        "### Instructions",
        "",
        "Review the code or architecture against these criteria. For each item:",
        "- State whether it passes, needs improvement, or fails",
        "- Provide specific examples from the code",
        "- Suggest concrete improvements where needed",
        "",
        "Start by examining the project structure, then drill into the focus area.",
        "Use `search_knowledge` to find relevant project knowledge units first.",
    ])
    return GetPromptResult(
        description=f"SDD review with focus: {focus}",
        messages=[PromptMessage(role="user", content=TextContent(type="text", text=text))],
    )


def _handle_kcp_explore(args: dict) -> GetPromptResult:
    topic = args.get("topic", "")
    text = "\n".join([
        f"## Explore Knowledge: {topic}",
        "",
        f'Find and present all knowledge units related to "{topic}".',
        "",
        "### Steps",
        "",
        f'1. Call the `search_knowledge` tool with query: "{topic}"',
        "2. For each result, summarize:",
        "   - **Unit ID** and relevance score",
        "   - **Intent**: what this unit teaches",
        "   - **Path**: where to find it",
        "   - **Audience**: who it is written for",
        "3. Suggest a reading order based on dependencies (check depends_on fields)",
        "4. Highlight which units are most relevant to the topic",
        "",
        "Present the results as a navigable knowledge map that helps the user",
        "understand what information is available and where to start.",
    ])
    return GetPromptResult(
        description=f"Explore knowledge units for: {topic}",
        messages=[PromptMessage(role="user", content=TextContent(type="text", text=text))],
    )
