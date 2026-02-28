# kcp-mcp — Python MCP Bridge for KCP

Exposes a [`knowledge.yaml`](https://github.com/Cantara/knowledge-context-protocol) manifest as [MCP resources](https://modelcontextprotocol.io/specification/draft/server/resources). Every AI agent that speaks MCP can navigate your knowledge without loading everything at once.

## Install

```bash
pip install kcp-mcp
```

## Usage

```bash
# Serve ./knowledge.yaml via stdio (default)
kcp-mcp

# Specify a path
kcp-mcp path/to/knowledge.yaml

# Only expose units with audience: [agent]
kcp-mcp knowledge.yaml --agent-only

# HTTP/SSE transport
kcp-mcp knowledge.yaml --transport http --port 8000

# Suppress validation warnings
kcp-mcp knowledge.yaml --no-warnings
```

## Configure in Claude Code

Add to your project's `.mcp.json` or `~/.claude/mcp.json`:

```json
{
  "mcpServers": {
    "project-knowledge": {
      "command": "kcp-mcp",
      "args": ["knowledge.yaml"]
    }
  }
}
```

## What the agent sees

On connection, the agent calls `resources/list` to see all knowledge units. Each unit is an MCP resource:

| MCP field | Source |
|-----------|--------|
| `uri` | `knowledge://{project-slug}/{unit.id}` |
| `name` | `unit.id` |
| `title` | `unit.intent` |
| `description` | intent + triggers + depends_on |
| `mimeType` | resolved from `content_type` → `format` → file extension |
| `annotations.priority` | `global=1.0`, `project=0.7`, `module=0.5` |
| `annotations.audience` | `["assistant"]` if `agent` in audience |

A synthetic **manifest resource** at `knowledge://{slug}/manifest` returns the full unit index as JSON — the recommended entry point for agents.

## Use as a library

```python
from kcp_mcp import create_server
from mcp.server.stdio import stdio_server
import asyncio

server = create_server("knowledge.yaml", agent_only=True)

async def run():
    async with stdio_server() as (r, w):
        await server.run(r, w, server.create_initialization_options())

asyncio.run(run())
```

## Development

```bash
# Install the KCP parser from the local repo, then the bridge
pip install -e ../../parsers/python
pip install -e ".[test]"

# Run tests
pytest tests/ -v
```

## License

Apache-2.0 — same as the KCP specification.
