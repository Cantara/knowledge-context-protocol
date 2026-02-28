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
) -> Server:
    """
    Parse knowledge.yaml and return a configured MCP Server.

    Args:
        manifest_path:      Path to knowledge.yaml
        agent_only:         If True, only expose units with audience: [agent]
        warn_on_validation: Log validation warnings to stderr
    """
    manifest: KnowledgeManifest = parse(manifest_path)
    manifest_dir = manifest_path.parent
    slug = project_slug(manifest.project)
    m_uri = manifest_uri(slug)

    # Build static resource list
    resource_list: list[Resource] = [
        _build_resource(manifest_resource_dict(slug, manifest))
    ]
    for unit in manifest.units:
        if agent_only and "agent" not in unit.audience:
            continue
        resource_list.append(_build_resource(unit_resource_dict(slug, unit)))

    # Index units by id
    unit_index = {u.id: u for u in manifest.units}

    # Log startup info
    agent_note = " (agent-only filter active)" if agent_only else ""
    sys.stderr.write(
        f"[kcp-mcp] Serving '{manifest.project}' — {len(manifest.units)} units{agent_note}\n"
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
        unit = unit_index.get(unit_id)
        if unit is None:
            raise ValueError(f"No unit with id '{unit_id}'")

        mime = resolve_mime(unit)
        try:
            content, is_binary = read_resource_content(manifest_dir, unit.path, mime)
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
