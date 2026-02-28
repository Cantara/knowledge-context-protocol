"""
KCP MCP Bridge CLI entry point.

Usage:
    kcp-mcp [path/to/knowledge.yaml] [--agent-only] [--transport stdio|http] [--port N]
"""
import argparse
import asyncio
import sys
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(
        prog="kcp-mcp",
        description="KCP MCP Bridge — serve a knowledge.yaml as MCP resources",
    )
    parser.add_argument(
        "manifest",
        nargs="?",
        default="knowledge.yaml",
        help="Path to knowledge.yaml (default: ./knowledge.yaml)",
    )
    parser.add_argument(
        "--agent-only",
        action="store_true",
        default=False,
        help="Only expose units with audience: [agent]",
    )
    parser.add_argument(
        "--transport",
        choices=["stdio", "http"],
        default="stdio",
        help="MCP transport (default: stdio)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=8000,
        help="Port for HTTP transport (default: 8000)",
    )
    parser.add_argument(
        "--no-warnings",
        action="store_true",
        default=False,
        help="Suppress KCP validation warnings",
    )
    args = parser.parse_args()

    manifest_path = Path(args.manifest)
    if not manifest_path.exists():
        sys.stderr.write(f"Error: manifest not found: {manifest_path}\n")
        sys.exit(1)

    from .server import create_server

    try:
        server = create_server(
            manifest_path,
            agent_only=args.agent_only,
            warn_on_validation=not args.no_warnings,
        )
    except Exception as e:
        sys.stderr.write(f"Error: {e}\n")
        sys.exit(1)

    if args.transport == "http":
        _run_http(server, args.port)
    else:
        _run_stdio(server)


def _run_stdio(server) -> None:
    from mcp.server.stdio import stdio_server

    async def _run():
        async with stdio_server() as (read_stream, write_stream):
            await server.run(
                read_stream,
                write_stream,
                server.create_initialization_options(),
            )

    asyncio.run(_run())


def _run_http(server, port: int) -> None:
    from mcp.server.sse import SseServerTransport
    from starlette.applications import Starlette
    from starlette.routing import Mount, Route
    import uvicorn

    sse = SseServerTransport("/messages/")

    async def handle_sse(request):
        async with sse.connect_sse(
            request.scope, request.receive, request._send
        ) as streams:
            await server.run(
                streams[0], streams[1], server.create_initialization_options()
            )

    starlette_app = Starlette(
        routes=[
            Route("/sse", endpoint=handle_sse),
            Mount("/messages/", app=sse.handle_post_message),
        ]
    )

    sys.stderr.write(f"[kcp-mcp] HTTP/SSE transport on http://localhost:{port}/sse\n")
    uvicorn.run(starlette_app, host="0.0.0.0", port=port)


if __name__ == "__main__":
    main()
