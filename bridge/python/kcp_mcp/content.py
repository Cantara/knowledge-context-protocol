"""
File content reading for MCP resource serving.
Handles text/binary dispatch and path traversal guard.
"""
import base64
from pathlib import Path

from .mapper import is_binary_mime


class ResourceNotFoundError(Exception):
    pass


class PathTraversalError(Exception):
    pass


def read_resource_content(
    manifest_dir: Path,
    unit_path: str,
    mime: str,
) -> tuple[str, bool]:
    """
    Read a unit's file content from disk.

    Returns (content, is_binary) where:
    - is_binary=False: content is UTF-8 text
    - is_binary=True:  content is base64-encoded bytes

    Raises PathTraversalError if the resolved path escapes manifest_dir.
    Raises ResourceNotFoundError if the file does not exist.
    """
    resolved = (manifest_dir / unit_path).resolve()
    root = manifest_dir.resolve()

    try:
        resolved.relative_to(root)
    except ValueError:
        raise PathTraversalError(f"Path escapes manifest root: {unit_path!r}")

    if not resolved.exists():
        raise ResourceNotFoundError(f"Resource not found: {unit_path}")

    if is_binary_mime(mime):
        data = resolved.read_bytes()
        return base64.b64encode(data).decode("ascii"), True
    else:
        return resolved.read_text(encoding="utf-8"), False
