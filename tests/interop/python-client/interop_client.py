#!/usr/bin/env python3
"""Interop test client -- Python

Connects to the KCP MCP bridge via stdio, lists resources and reads each one,
then prints structured results as JSON for parity comparison.

Usage: python interop_client.py <manifest-path> [output-path]
"""

import asyncio
import json
import sys
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


async def main():
    manifest_path = sys.argv[1] if len(sys.argv) > 1 else None
    output_path = sys.argv[2] if len(sys.argv) > 2 else "python-results.json"

    if not manifest_path:
        print("Usage: python interop_client.py <manifest-path> [output-path]",
              file=sys.stderr)
        sys.exit(1)

    manifest_path = str(Path(manifest_path).resolve())

    # Find the Python bridge
    repo_root = Path(__file__).resolve().parent.parent.parent.parent
    bridge_python = repo_root / "bridge" / "python"

    # Use the Python bridge via stdio
    server_params = StdioServerParameters(
        command=sys.executable,
        args=["-m", "kcp_mcp", manifest_path],
        cwd=str(bridge_python),
    )

    resources = []

    async with stdio_client(server_params) as (read_stream, write_stream):
        async with ClientSession(read_stream, write_stream) as session:
            await session.initialize()

            # List all resources
            list_result = await session.list_resources()

            for res in list_result.resources:
                entry = {
                    "uri": str(res.uri),
                    "name": res.name,
                    "description": getattr(res, "description", None),
                    "mimeType": getattr(res, "mimeType", None),
                }

                # Read each resource
                try:
                    read_result = await session.read_resource(res.uri)
                    contents = read_result.contents
                    if contents and len(contents) > 0:
                        first = contents[0]
                        text = getattr(first, "text", None)
                        if text:
                            entry["content_preview"] = text[:200]
                except Exception as e:
                    print(f"Failed to read {res.uri}: {e}", file=sys.stderr)

                resources.append(entry)

    # Sort by URI for deterministic comparison
    resources.sort(key=lambda r: r["uri"])

    result = {
        "resource_count": len(resources),
        "resources": resources,
    }

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)

    print(f"Wrote {len(resources)} resources to {output_path}")


if __name__ == "__main__":
    asyncio.run(main())
