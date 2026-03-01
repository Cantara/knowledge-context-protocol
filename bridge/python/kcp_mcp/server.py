"""
KCP MCP server — low-level Server API matching the TypeScript bridge pattern.
"""
import sys
from pathlib import Path

from mcp.server import Server
from mcp.server.lowlevel.server import ReadResourceContents
from mcp.types import (
    Annotations,
    Resource,
)
from pydantic import AnyUrl

from kcp import parse
from kcp.model import KnowledgeManifest

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
) -> Server:
    """
    Parse knowledge.yaml and return a configured MCP Server.

    Args:
        manifest_path:      Path to knowledge.yaml
        agent_only:         If True, only expose units with audience: [agent]
        warn_on_validation: Log validation warnings to stderr
        sub_manifests:      Additional manifest paths whose units merge into the primary namespace
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

    return server
