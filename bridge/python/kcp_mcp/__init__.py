"""KCP MCP Bridge — expose knowledge.yaml units as MCP resources."""

from .server import create_server

__all__ = ["create_server"]
