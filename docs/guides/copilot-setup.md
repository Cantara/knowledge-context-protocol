# Using kcp-mcp with GitHub Copilot

`kcp-mcp` works with GitHub Copilot in all supported IDEs (VS Code, JetBrains, Eclipse, Xcode) and the Copilot CLI. This guide shows you how to set it up and what you get.

## What you get

Once connected, Copilot can:

- **Search your knowledge** — call `search_knowledge("oauth")` to find relevant units without loading everything
- **Read any unit** — call `get_unit("api-overview")` to fetch content on demand
- **Get CLI syntax** — call `get_command_syntax("git rebase")` for instant flag reference (requires `--commands-dir`)
- **Use structured prompts** — `/sdd-review` for architecture review, `/kcp-explore <topic>` to navigate knowledge

---

## Option A — MCP server (recommended)

MCP is GA in all Copilot environments as of mid-2025. This is the full-featured path.

### VS Code

Create `.vscode/mcp.json` in your project root:

```json
{
  "servers": {
    "project-knowledge": {
      "type": "stdio",
      "command": "npx",
      "args": ["kcp-mcp@0.6.0", "knowledge.yaml"]
    }
  }
}
```

With kcp-commands (adds CLI syntax tool — 284 commands including git, mvn, docker, kubectl):

```json
{
  "servers": {
    "project-knowledge": {
      "type": "stdio",
      "command": "npx",
      "args": [
        "kcp-mcp@0.6.0",
        "knowledge.yaml",
        "--commands-dir",
        "${workspaceFolder}/node_modules/kcp-commands/commands"
      ]
    }
  }
}
```

To install kcp-commands manifests locally:

```bash
npm install --save-dev kcp-commands
```

Reload VS Code. The server appears in the MCP panel (Copilot icon → MCP Servers).

### JetBrains (IntelliJ, WebStorm, etc.)

Open **Settings → Tools → GitHub Copilot → MCP Servers** → Add server:

| Field | Value |
|-------|-------|
| Name | `project-knowledge` |
| Type | `stdio` |
| Command | `npx` |
| Arguments | `kcp-mcp@0.6.0 knowledge.yaml` |

Or add `mcp.json` to your project root (same format as VS Code).

### Copilot CLI

Add to `~/.copilot/mcp-config.json`:

```json
{
  "servers": {
    "project-knowledge": {
      "type": "stdio",
      "command": "npx",
      "args": ["kcp-mcp@0.6.0", "knowledge.yaml"]
    }
  }
}
```

---

## Option B — Static instructions (zero infrastructure)

For environments that cannot run MCP servers (locked-down enterprise environments, GitHub.com Copilot chat, no Node.js on CI).

Generate `.github/copilot-instructions.md` from your `knowledge.yaml`:

```bash
npx kcp-mcp --generate-instructions knowledge.yaml > .github/copilot-instructions.md
```

Agent-facing units only:

```bash
npx kcp-mcp --generate-instructions knowledge.yaml --audience agent > .github/copilot-instructions.md
```

Commit the file. Copilot automatically injects its contents into every chat in the repository.

**Trade-offs vs MCP:**

| | MCP server | Static instructions |
|---|---|---|
| Dynamic search | ✅ `search_knowledge` tool | ❌ static text |
| On-demand content | ✅ `get_unit` tool | ❌ all units loaded every prompt |
| CLI syntax | ✅ `get_command_syntax` tool | ❌ not available |
| Infrastructure required | Node.js | none |
| Works on GitHub.com | ❌ | ✅ |

---

## Using the tools in Copilot Chat

Once the MCP server is connected, Copilot has three tools available. You can invoke them explicitly or let Copilot call them automatically when relevant.

### `search_knowledge`

Ask Copilot to find knowledge about a topic:

> "What do we have documented about authentication?"

Copilot calls `search_knowledge("authentication")` and returns the top matching units with links to their content.

Or invoke directly in chat:

> "Use search_knowledge to find units about deployment"

### `get_unit`

Fetch a specific unit by id:

> "Get the content of the 'api-overview' knowledge unit"

### `get_command_syntax`

Get CLI syntax for any command (requires `--commands-dir`):

> "What's the syntax for git rebase?"
> "How do I use docker build?"
> "Get command syntax for mvn clean package"

Returns a compact reference with key flags and preferred invocations — same as kcp-commands Phase A output.

---

## Using the prompts

### `/sdd-review`

Review code or architecture using SDD (Skill-Driven Development) methodology principles.

In Copilot Chat:
```
/sdd-review
/sdd-review focus=security
/sdd-review focus=architecture
```

### `/kcp-explore`

Navigate available knowledge units for a topic:
```
/kcp-explore authentication
/kcp-explore deployment
/kcp-explore database migrations
```

---

## Creating a `.agent.md` for your project

Copilot supports custom agents via `.agent.md` files (VS Code, Copilot CLI). You can create a project-specific agent that combines your knowledge manifest with methodology instructions:

Create `.copilot/agents/project-expert.agent.md`:

```yaml
---
name: project-expert
description: "Expert in this project's architecture, patterns, and conventions"
mcp-servers:
  project-knowledge:
    type: stdio
    command: npx
    args: ["kcp-mcp@0.6.0", "knowledge.yaml"]
---

You are an expert in this project. When answering questions:

1. Always start by searching for relevant knowledge units using `search_knowledge`
2. Read the most relevant units with `get_unit` before answering
3. For CLI commands, use `get_command_syntax` for accurate flag reference

When reviewing code, focus on:
- Alignment with documented architecture patterns
- Consistency with existing conventions
- Knowledge documentation gaps (is this pattern documented in knowledge.yaml?)
```

Invoke in Copilot Chat: `@project-expert explain the authentication flow`

---

## Comparison: Copilot vs Claude Code

Both get access to the same knowledge via MCP. The difference is in what happens at the tool layer:

| Capability | Copilot (MCP) | Claude Code (hooks + MCP) |
|---|---|---|
| Knowledge search | ✅ `search_knowledge` tool | ✅ same |
| Knowledge content | ✅ `get_unit` tool | ✅ same |
| CLI syntax injection | ✅ `get_command_syntax` tool | ✅ Phase A hook (automatic, pre-execution) |
| CLI output filtering | ❌ not available | ✅ Phase B hook (automatic, post-execution) |
| SDD skill auto-loading | ❌ manual via `.agent.md` | ✅ automatic by project context |
| Skill depth | 30K char limit per agent | unlimited |

The MCP knowledge layer is identical. Claude Code adds automatic hook-based injection and deeper skill integration.

---

## Troubleshooting

**Server doesn't appear in VS Code MCP panel**
- Check `.vscode/mcp.json` syntax (valid JSON, correct path to `knowledge.yaml`)
- Ensure Node.js ≥18 is installed: `node --version`
- Check Output panel → Copilot MCP for error messages

**`get_command_syntax` returns "No command manifests loaded"**
- Verify `--commands-dir` points to a directory containing `.yaml` files
- Check the path is correct relative to where kcp-mcp is started
- Try an absolute path first to rule out working directory issues

**`search_knowledge` returns no results**
- Ensure your `knowledge.yaml` units have `triggers` fields populated
- Try a broader query matching unit `id` or words in `intent`
- Check server is running: in VS Code, hover the MCP server indicator

**Static instructions file is too large**
- Use `--audience agent` to filter to agent-facing units only
- Split into path-specific instruction files using `.github/instructions/`
