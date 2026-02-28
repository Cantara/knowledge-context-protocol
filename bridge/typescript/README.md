# kcp-mcp — TypeScript MCP Bridge for KCP

Exposes a [`knowledge.yaml`](https://github.com/Cantara/knowledge-context-protocol) manifest as [MCP resources](https://modelcontextprotocol.io/specification/draft/server/resources). Drop a `knowledge.yaml` in your project. Every AI agent that speaks MCP can now navigate your knowledge — without loading everything at once.

## Install

```bash
npm install -g kcp-mcp
```

Or run without installing:

```bash
npx kcp-mcp knowledge.yaml
```

## Usage

```bash
# Serve ./knowledge.yaml via stdio (default)
kcp-mcp

# Specify a path
kcp-mcp path/to/knowledge.yaml

# Only expose units with audience: [agent]
kcp-mcp knowledge.yaml --agent-only

# HTTP transport
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
      "command": "npx",
      "args": ["kcp-mcp", "knowledge.yaml"]
    }
  }
}
```

Or with a global install:

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

On connection, the agent can call `resources/list` to see all knowledge units. Each unit becomes an MCP resource:

| MCP field | Source |
|-----------|--------|
| `uri` | `knowledge://{project-slug}/{unit.id}` |
| `name` | `unit.id` |
| `title` | `unit.intent` (≤80 chars) |
| `description` | intent + audience + scope + triggers + depends_on |
| `mimeType` | resolved from `content_type` → `format` → file extension |
| `annotations.priority` | `global=1.0`, `project=0.7`, `module=0.5` |
| `annotations.audience` | `["assistant"]` if `agent` in audience |

A synthetic **manifest resource** at `knowledge://{slug}/manifest` returns the full unit index as JSON — the recommended entry point for agents.

## Use as a library

```typescript
import { createKcpServer } from "kcp-mcp";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

const { server } = createKcpServer("knowledge.yaml", { agentOnly: true });
const transport = new StdioServerTransport();
await server.connect(transport);
```

Or use the KCP parser independently:

```typescript
import { parseFile, validate } from "kcp-mcp";

const manifest = parseFile("knowledge.yaml");
const result = validate(manifest, process.cwd());
if (!result.isValid) console.error(result.errors);
```

## Development

```bash
npm install
npm run build
npm test
```

## License

Apache-2.0 — same as the KCP specification.
