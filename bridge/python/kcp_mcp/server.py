"""
KCP MCP server — low-level Server API matching the TypeScript bridge pattern.
"""
import json
import sys
from datetime import date as _date
from pathlib import Path

from mcp.server import Server
from mcp.server.lowlevel.server import ReadResourceContents
from mcp.types import (
    Annotations,
    Resource,
    TextContent,
    Tool,
)
from pydantic import AnyUrl

from kcp import parse
from kcp.model import KnowledgeManifest

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

    # Load sub-manifests and merge units
    added_total = 0
    for sub_path in sub_manifests:
        sub_path = Path(sub_path).resolve()
        sub_dir = sub_path.parent
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

    # Build static resource list
    resource_list: list[Resource] = [
        _build_resource(manifest_resource_dict(slug, manifest))
    ]
    for unit, _ in unit_context.values():
        if agent_only and "agent" not in unit.audience:
            continue
        resource_list.append(_build_resource(unit_resource_dict(slug, unit)))

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
        return resource_list

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
                    "properties": {},
                    "required": [],
                },
            ),
        ]
        return tools

    @server.call_tool()
    async def call_tool(name: str, arguments: dict) -> list[TextContent]:
        if name == "search_knowledge":
            return _handle_search_knowledge(unit_context, slug, arguments or {})
        if name == "get_unit":
            return _handle_get_unit(unit_context, arguments or {})
        if name == "get_command_syntax":
            return _handle_get_command_syntax(command_manifests, arguments or {})
        if name == "list_manifests":
            return _handle_list_manifests(manifest)
        return [TextContent(type="text", text=f"Unknown tool: {name}")]

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

    hints = unit.hints or {}
    token_estimate = hints.get("token_estimate")
    summary_unit = hints.get("summary_unit")

    return {
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


def _is_temporally_active(unit, as_of: str) -> bool:
    """Return True if unit is active on as_of (§15.13). Units without temporal block are always active."""
    t = getattr(unit, "temporal", None)
    if t is None:
        return True
    if t.valid_from is not None and t.valid_from > as_of:
        return False
    if t.valid_until is not None and t.valid_until < as_of:
        return False
    return True


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
    temporal_date = as_of if as_of is not None else _date.today().isoformat()

    terms = query.split()
    results = []

    for unit_id, (unit, _unit_dir) in unit_context.items():
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
            if _is_temporally_active(unit_context.get(r["id"], (None, None))[0], temporal_date)
        ]

    if not final_results:
        ids = ", ".join(unit_context.keys())
        return [TextContent(type="text", text=f'No units matched query "{query}". Available units: {ids}')]

    final_results.sort(key=lambda r: r["score"], reverse=True)
    top5 = final_results[:5]

    return [TextContent(type="text", text=json.dumps(top5, indent=2))]


def _handle_get_unit(
    unit_context: dict,
    arguments: dict,
) -> list[TextContent]:
    """Fetch the content of a specific knowledge unit by id."""
    unit_id = arguments.get("unit_id", "").strip()
    ctx = unit_context.get(unit_id)
    if ctx is None:
        ids = ", ".join(unit_context.keys())
        return [TextContent(type="text", text=f'Unit not found: "{unit_id}". Available units: {ids}')]

    unit, unit_dir = ctx
    mime = resolve_mime(unit)
    try:
        content, is_binary = read_resource_content(unit_dir, unit.path, mime)
    except (ResourceNotFoundError, PathTraversalError) as e:
        return [TextContent(type="text", text=f"Error reading unit: {e}")]

    if is_binary:
        return [TextContent(type="text", text=f"[Binary content: {mime}, base64 length: {len(content)}]")]
    return [TextContent(type="text", text=content)]


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


def _handle_list_manifests(manifest: KnowledgeManifest) -> list[TextContent]:
    """Return JSON array of declared sub-manifests."""
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
        }
        entries.append(entry)
    return [TextContent(type="text", text=json.dumps(entries, indent=2))]
