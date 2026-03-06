# kcp-mcp — MCP Bridge for the Knowledge Context Protocol

Exposes a [`knowledge.yaml`](https://github.com/Cantara/knowledge-context-protocol) manifest as an MCP server. Every AI agent that speaks MCP — Claude Code, GitHub Copilot, Cursor, Windsurf — can navigate your knowledge, search units, and get CLI syntax guidance without loading everything at once.

**v0.6.0:** Added MCP tools (`search_knowledge`, `get_unit`, `get_command_syntax`), MCP prompts, and `--generate-instructions` for zero-infra Copilot support.

## Install

```bash
npm install -g kcp-mcp
```

Or run without installing:

```bash
npx kcp-mcp knowledge.yaml
```

## Quick start

```bash
# Serve ./knowledge.yaml via stdio
kcp-mcp

# Serve with kcp-commands syntax guidance
kcp-mcp knowledge.yaml --commands-dir /path/to/kcp-commands/commands

# Generate .github/copilot-instructions.md (no server needed)
kcp-mcp --generate-instructions knowledge.yaml > .github/copilot-instructions.md

# Agent-only units, HTTP transport
kcp-mcp knowledge.yaml --agent-only --transport http --port 8000
```

## Configure in Claude Code

Add to `.mcp.json` in your project root or `~/.claude/mcp.json`:

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

With kcp-commands syntax injection:

```json
{
  "mcpServers": {
    "project-knowledge": {
      "command": "npx",
      "args": [
        "kcp-mcp", "knowledge.yaml",
        "--commands-dir", "/path/to/kcp-commands/commands"
      ]
    }
  }
}
```

## Configure in GitHub Copilot (VS Code / JetBrains / CLI)

Add `.vscode/mcp.json` to your project:

```json
{
  "servers": {
    "project-knowledge": {
      "type": "stdio",
      "command": "npx",
      "args": ["kcp-mcp", "knowledge.yaml"]
    }
  }
}
```

With kcp-commands (enables `get_command_syntax` tool):

```json
{
  "servers": {
    "project-knowledge": {
      "type": "stdio",
      "command": "npx",
      "args": [
        "kcp-mcp", "knowledge.yaml",
        "--commands-dir", "${workspaceFolder}/node_modules/kcp-commands/commands"
      ]
    }
  }
}
```

See [Copilot setup guide](../../docs/guides/copilot-setup.md) for detailed instructions per IDE.

## MCP capabilities

### Resources

Every knowledge unit becomes an MCP resource at `knowledge://{project-slug}/{unit.id}`.
A manifest meta-resource at `knowledge://{slug}/manifest` returns the full unit index as JSON — the recommended entry point for agents.

| MCP field | Source |
|-----------|--------|
| `uri` | `knowledge://{project-slug}/{unit.id}` |
| `name` | `unit.id` |
| `title` | `unit.intent` (≤80 chars) |
| `description` | intent + audience + scope + triggers + depends_on |
| `mimeType` | resolved from `content_type` → `format` → file extension |
| `annotations.priority` | `global=1.0`, `project=0.7`, `module=0.5` |
| `annotations.audience` | `["assistant"]` if `agent` in audience |

### Tools (v0.6.0)

**`search_knowledge`** — Find units by keyword. Agents call this instead of loading the entire manifest.

```
Input:  { query: string, audience?: string, scope?: string }
Output: JSON array of top-5 matching units with id, intent, path, uri, score
```

Scoring: trigger match = 5 pts, intent match = 3 pts, id/path match = 1 pt.

**`get_unit`** — Fetch the content of a specific unit by id.

```
Input:  { unit_id: string }
Output: Full text content of the unit file
```

**`get_command_syntax`** — Get CLI syntax guidance from kcp-commands manifests.
Only available when `--commands-dir` is set.

```
Input:  { command: string }   e.g. "git commit", "mvn", "docker build"
Output: Compact syntax block with usage, key flags, and preferred invocations
```

Example output:
```
[kcp] git commit: Record staged changes to the repository
Usage: git commit [<options>]
Key flags:
  -m '<message>': Commit message inline  → Simple one-line commits
  --amend: Replace the last commit  → Fixing typo — never after push
Preferred:
  git commit -m 'Add feature X'  # Standard single-line commit
```

### Prompts (v0.6.0)

**`sdd-review`** — Review code or architecture using SDD (Skill-Driven Development) methodology.
Optional argument: `focus` (`architecture` | `quality` | `security` | `performance`).

**`kcp-explore`** — Explore available knowledge units for a topic.
Required argument: `topic`.

Invoke in Copilot Chat: `/sdd-review` or `/kcp-explore authentication`.

## Zero-infra option: `--generate-instructions`

For teams that cannot run MCP servers (locked-down enterprise environments, GitHub.com Copilot):

```bash
# Generate .github/copilot-instructions.md
kcp-mcp --generate-instructions knowledge.yaml > .github/copilot-instructions.md

# Agent-facing units only
kcp-mcp --generate-instructions knowledge.yaml --audience agent > .github/copilot-instructions.md
```

The output is a static markdown file that Copilot injects into every chat interaction in the repository. No server, no runtime, no configuration beyond committing the file.

## Sub-manifests

Merge multiple `knowledge.yaml` files into a single MCP namespace. Units from sub-manifests are merged under the primary project slug; the primary manifest wins on duplicate ids.

```bash
# Merge all knowledge.yaml files found under fragments/
kcp-mcp knowledge.yaml --sub-manifests "fragments/*/knowledge.yaml"
```

## CLI reference

```
kcp-mcp [path/to/knowledge.yaml] [options]

Options:
  --agent-only              Only expose units with audience: [agent]
  --sub-manifests <glob>    Additional manifests to merge
  --commands-dir <path>     Load kcp-commands manifests (enables get_command_syntax tool)
  --generate-instructions   Write copilot-instructions.md to stdout instead of starting server
  --audience <value>        Filter units by audience (use with --generate-instructions)
  --transport <type>        stdio (default) or http
  --port <number>           Port for HTTP transport (default: 8000)
  --no-warnings             Suppress KCP validation warnings
  --help, -h                Show help
```

## Use as a library

```typescript
import { createKcpServer } from "kcp-mcp";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { loadCommandManifests } from "kcp-mcp";

// Basic server
const { server } = createKcpServer("knowledge.yaml", { agentOnly: true });
await server.connect(new StdioServerTransport());

// With command syntax tools
const commandManifests = loadCommandManifests("/path/to/kcp-commands/commands");
const { server } = createKcpServer("knowledge.yaml", { commandManifests });
await server.connect(new StdioServerTransport());
```

Parser only:

```typescript
import { parseFile, validate } from "kcp-mcp";

const manifest = parseFile("knowledge.yaml");
const result = validate(manifest, process.cwd());
if (!result.isValid) console.error(result.errors);
```

Instructions generator:

```typescript
import { generateInstructions } from "kcp-mcp";

const md = generateInstructions("knowledge.yaml", { audience: "agent" });
process.stdout.write(md);
```

## Development

```bash
npm install
npm run build   # TypeScript compile
npm test        # 108 tests
```

## License

Apache-2.0 — same as the KCP specification.
