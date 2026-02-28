"""
KCP → MCP resource mapping.

Pure functions — no file I/O, no MCP SDK imports.
This module is independently testable and identical in logic to the TypeScript mapper.
"""
import json
import re
from pathlib import PurePosixPath
from typing import Optional

from kcp.model import KnowledgeManifest, KnowledgeUnit

# ── MIME resolution tables ────────────────────────────────────────────────────

_FORMAT_TO_MIME: dict[str, str] = {
    "markdown":    "text/markdown",
    "pdf":         "application/pdf",
    "openapi":     "application/vnd.oai.openapi+yaml",
    "json-schema": "application/schema+json",
    "jupyter":     "application/x-ipynb+json",
    "html":        "text/html",
    "asciidoc":    "text/asciidoc",
    "rst":         "text/x-rst",
    "vtt":         "text/vtt",
    "yaml":        "application/yaml",
    "json":        "application/json",
    "csv":         "text/csv",
    "text":        "text/plain",
}

_EXT_TO_MIME: dict[str, str] = {
    ".md":    "text/markdown",
    ".yaml":  "application/yaml",
    ".yml":   "application/yaml",
    ".json":  "application/json",
    ".html":  "text/html",
    ".htm":   "text/html",
    ".txt":   "text/plain",
    ".pdf":   "application/pdf",
    ".csv":   "text/csv",
    ".rst":   "text/x-rst",
    ".adoc":  "text/asciidoc",
    ".ipynb": "application/x-ipynb+json",
    ".vtt":   "text/vtt",
}

_SCOPE_PRIORITY: dict[str, float] = {
    "global":  1.0,
    "project": 0.7,
    "module":  0.5,
}

_BINARY_PREFIXES = (
    "application/pdf",
    "image/",
    "audio/",
    "video/",
    "application/octet-stream",
)


def is_binary_mime(mime: str) -> bool:
    return any(mime.startswith(p) for p in _BINARY_PREFIXES)


# ── Slug / URI ────────────────────────────────────────────────────────────────

def project_slug(project: str) -> str:
    """Convert a project name to a URI-safe slug."""
    slug = project.lower().replace(" ", "-")
    slug = re.sub(r"[^a-z0-9\-]", "", slug)
    slug = re.sub(r"-+", "-", slug).strip("-")
    return slug or "project"


def unit_uri(slug: str, unit_id: str) -> str:
    return f"knowledge://{slug}/{unit_id}"


def manifest_uri(slug: str) -> str:
    return f"knowledge://{slug}/manifest"


# ── MIME resolution ───────────────────────────────────────────────────────────

def resolve_mime(unit: KnowledgeUnit) -> str:
    if unit.content_type:
        return unit.content_type
    if unit.format and unit.format in _FORMAT_TO_MIME:
        return _FORMAT_TO_MIME[unit.format]
    ext = PurePosixPath(unit.path).suffix.lower()
    return _EXT_TO_MIME.get(ext, "text/plain")


# ── Audience mapping ──────────────────────────────────────────────────────────

def map_audience(audience: list[str]) -> list[str]:
    """Map KCP audience values to MCP Role values ('user' / 'assistant')."""
    roles: set[str] = set()
    for a in audience:
        if a == "agent":
            roles.add("assistant")
        else:
            roles.add("user")
    if not roles:
        roles.add("user")
    return sorted(roles)  # deterministic: ["assistant"] or ["user"] or ["assistant", "user"]


# ── Per-unit resource descriptor ─────────────────────────────────────────────

def unit_resource_dict(slug: str, unit: KnowledgeUnit) -> dict:
    """Return a plain dict of MCP Resource fields for a single unit."""
    description = unit.intent
    if unit.triggers:
        description += f"\nTriggers: {', '.join(unit.triggers)}"
    if unit.depends_on:
        description += f"\nDepends on: {', '.join(unit.depends_on)}"

    last_modified: Optional[str] = None
    if unit.validated:
        last_modified = f"{unit.validated}T00:00:00Z"

    return {
        "uri":         unit_uri(slug, unit.id),
        "name":        unit.id,
        "title":       unit.intent,
        "description": description,
        "mimeType":    resolve_mime(unit),
        "annotations": {
            "audience":     map_audience(unit.audience),
            "priority":     _SCOPE_PRIORITY.get(unit.scope, 0.5),
            "lastModified": last_modified,
        },
    }


# ── Manifest meta-resource ───────────────────────────────────────────────────

def manifest_resource_dict(slug: str, manifest: KnowledgeManifest) -> dict:
    n = len(manifest.units)
    return {
        "uri":   manifest_uri(slug),
        "name":  "manifest",
        "title": f"Knowledge index: {manifest.project}",
        "description": (
            f"Complete index of all {n} knowledge unit(s) in '{manifest.project}'. "
            f"Read this first to navigate the knowledge graph. "
            f"Returns JSON with all units, their intents, dependencies, and relationships."
        ),
        "mimeType": "application/json",
        "annotations": {
            "audience": ["assistant", "user"],
            "priority": 1.0,
            "lastModified": None,
        },
    }


def build_manifest_json(manifest: KnowledgeManifest, slug: str) -> str:
    """Build the JSON body for the manifest meta-resource."""
    units_data = []
    for u in manifest.units:
        entry: dict = {
            "id":       u.id,
            "uri":      unit_uri(slug, u.id),
            "intent":   u.intent,
            "scope":    u.scope,
            "audience": u.audience,
            "kind":     u.kind or "knowledge",
        }
        if u.depends_on:
            entry["depends_on"] = u.depends_on
        if u.triggers:
            entry["triggers"] = u.triggers
        if u.validated:
            entry["validated"] = str(u.validated)
        if u.update_frequency:
            entry["update_frequency"] = u.update_frequency
        if u.supersedes:
            entry["supersedes"] = u.supersedes
        units_data.append(entry)

    doc = {
        "project":       manifest.project,
        "version":       manifest.version or "",
        "kcp_version":   manifest.kcp_version or "0.3",
        "updated":       str(manifest.updated) if manifest.updated else None,
        "unit_count":    len(manifest.units),
        "units":         units_data,
        "relationships": [
            {"from": r.from_id, "to": r.to_id, "type": r.type}
            for r in manifest.relationships
        ],
    }
    return json.dumps(doc, indent=2)
