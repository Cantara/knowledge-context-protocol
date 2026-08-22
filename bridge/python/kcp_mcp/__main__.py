"""
KCP MCP Bridge CLI entry point.

Usage:
    kcp-mcp [path/to/knowledge.yaml] [--agent-only]
            [--transport stdio|http|streamable-http] [--port N]
            [--bearer-token-env VAR] [--sub-manifests path ...]
"""
import argparse
import asyncio
import glob as glob_module
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
        choices=["stdio", "http", "streamable-http"],
        default="stdio",
        help=(
            "MCP transport (default: stdio). 'http' is the legacy SSE transport "
            "(/sse + /messages/, unauthenticated) kept for backward compatibility. "
            "'streamable-http' is the current MCP HTTP transport (single /mcp "
            "endpoint, stateless, supports --bearer-token-env) and is the one to "
            "use for a real deployment."
        ),
    )
    parser.add_argument(
        "--port",
        type=int,
        default=8000,
        help="Port for HTTP transport (default: 8000)",
    )
    parser.add_argument(
        "--bearer-token-env",
        default=None,
        metavar="VAR",
        help=(
            "Name of an environment variable holding a static bearer token. "
            "Only meaningful with --transport streamable-http (the legacy 'http' "
            "SSE transport has no auth hook and ignores this). If unset, the "
            "streamable-http endpoint is unauthenticated — a warning is printed, "
            "it is not silently allowed."
        ),
    )
    parser.add_argument(
        "--no-warnings",
        action="store_true",
        default=False,
        help="Suppress KCP validation warnings",
    )
    parser.add_argument(
        "--sub-manifests",
        nargs="*",
        default=[],
        metavar="PATH",
        help="Additional knowledge.yaml paths to merge (supports glob wildcards)",
    )
    parser.add_argument(
        "--commands-dir",
        default=None,
        metavar="DIR",
        help="Directory of kcp-commands YAML files (enables get_command_syntax tool)",
    )
    args = parser.parse_args()

    manifest_path = Path(args.manifest)
    if not manifest_path.exists():
        sys.stderr.write(f"Error: manifest not found: {manifest_path}\n")
        sys.exit(1)

    # Expand any glob patterns in --sub-manifests
    sub_manifest_paths: list[Path] = []
    for pattern in (args.sub_manifests or []):
        matches = glob_module.glob(pattern, recursive=True)
        if matches:
            sub_manifest_paths.extend(Path(m) for m in sorted(matches))
        else:
            sub_manifest_paths.append(Path(pattern))

    from .server import create_server

    commands_dir = Path(args.commands_dir) if args.commands_dir else None

    try:
        server = create_server(
            manifest_path,
            agent_only=args.agent_only,
            warn_on_validation=not args.no_warnings,
            sub_manifests=sub_manifest_paths,
            commands_dir=commands_dir,
        )
    except Exception as e:
        sys.stderr.write(f"Error: {e}\n")
        sys.exit(1)

    if args.transport == "streamable-http":
        _run_streamable_http(server, args.port, args.bearer_token_env)
    elif args.transport == "http":
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


def _run_streamable_http(server, port: int, bearer_token_env: str | None) -> None:
    """Serve over the current MCP HTTP transport: one stateless POST endpoint at
    /mcp, matching the transport every other MCP server in the Sunstone/Mynder
    stack already speaks (e.g. Sunstone Atlas Canvas's own /mcp). Unlike --transport
    http (legacy SSE, no auth hook at all), this transport supports a static bearer
    token — the only auth model this bridge needs, matching Canvas's own
    SUNSTONE_CANVAS_ACCESS_TOKEN convention rather than inventing OAuth machinery
    this bridge has no use for.
    """
    import hmac
    import os

    from mcp.server.streamable_http_manager import StreamableHTTPSessionManager
    from starlette.applications import Starlette
    from starlette.requests import Request
    from starlette.responses import JSONResponse
    from starlette.routing import Route
    from starlette.types import Receive, Scope, Send
    import uvicorn

    bearer_token = os.environ.get(bearer_token_env) if bearer_token_env else None
    if bearer_token_env and not bearer_token:
        sys.stderr.write(
            f"Error: --bearer-token-env {bearer_token_env} was given but that "
            "environment variable is unset or empty\n"
        )
        sys.exit(1)

    session_manager = StreamableHTTPSessionManager(app=server, stateless=True)

    class _BearerGatedMcpApp:
        """A real ASGI-callable class, not a plain function — Starlette's Route
        wraps bare async functions as request/response endpoints (single Request
        arg) rather than passing them the raw ASGI (scope, receive, send) triple,
        which silently breaks a streaming protocol like this one. The official
        mcp.server.fastmcp.server.StreamableHTTPASGIApp is the same three-line
        shape for the same reason — mirrored here instead of imported so this
        bridge doesn't pull in the whole fastmcp package for one wrapper class.
        """

        async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
            if bearer_token:
                request = Request(scope, receive)
                auth = request.headers.get("authorization", "")
                presented = auth[7:] if auth.startswith("Bearer ") else ""
                # hmac.compare_digest, not ==: constant-time, same discipline as
                # Canvas's own crypto.timingSafeEqual bearer check (server.mjs).
                if not presented or not hmac.compare_digest(presented, bearer_token):
                    response = JSONResponse({"error": "unauthorized"}, status_code=401)
                    await response(scope, receive, send)
                    return
            await session_manager.handle_request(scope, receive, send)

    starlette_app = Starlette(
        routes=[Route("/mcp", endpoint=_BearerGatedMcpApp(), methods=["POST", "GET"])],
        lifespan=lambda app: session_manager.run(),
    )

    if not bearer_token:
        sys.stderr.write(
            "[kcp-mcp] WARNING: no --bearer-token-env set — this endpoint is "
            "UNAUTHENTICATED. Fine for localhost testing, not for a real "
            "deployment.\n"
        )
    sys.stderr.write(f"[kcp-mcp] Streamable HTTP transport on http://localhost:{port}/mcp\n")
    uvicorn.run(starlette_app, host="0.0.0.0", port=port)


if __name__ == "__main__":
    main()
