# Using kcp-mcp with GitHub Copilot

`kcp-mcp` works with GitHub Copilot in all supported IDEs (VS Code, JetBrains, Eclipse, Xcode) and the Copilot CLI. This guide shows you how to set it up and what you get.

## What you get

A `knowledge.yaml` file maps your project's documentation — what each file answers, its audience, and how docs relate to each other. See the [KCP specification](https://github.com/Cantara/knowledge-context-protocol) for the schema and a starter template.

Once connected, Copilot can:

- **Search your knowledge** — call `search_knowledge("oauth")` to find relevant units without loading everything
- **Read any unit** — call `get_unit("api-overview")` to fetch content on demand
- **Get CLI syntax** — call `get_command_syntax("git rebase")` for instant flag reference (requires `--commands-dir`)
- **Use structured prompts** — `/sdd-review` for architecture review, `/kcp-explore <topic>` to navigate knowledge

## Which option should I use?

| Situation | Option |
|-----------|--------|
| You can run Node.js or Java in your IDE | **Option A** — MCP server (full features: dynamic search, on-demand content, CLI syntax) |
| Quick setup or GitHub.com Copilot chat | **Option B** — `--generate-instructions` (single static file, no runtime) |
| Enterprise / MCP blocked / need path-scoped context | **Option C** — `--generate-all` (three-tier: compact index + path-split + agent, no runtime) |

When in doubt, start with Option A if you can, Option C if you cannot.

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
      "args": ["kcp-mcp@0.10.0", "knowledge.yaml"]
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
        "kcp-mcp@0.10.0",
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
| Arguments | `kcp-mcp@0.10.0 knowledge.yaml` |

Or add `mcp.json` to your project root (same format as VS Code).

### Copilot CLI

Add to `~/.copilot/mcp-config.json`:

```json
{
  "servers": {
    "project-knowledge": {
      "type": "stdio",
      "command": "npx",
      "args": ["kcp-mcp@0.10.0", "knowledge.yaml"]
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

## Option C — Three-tier static instructions (enterprise, no infrastructure)

For enterprise environments that need path-specific context without running an MCP server. This approach generates three tiers of static files that Copilot loads automatically based on context.

### The three tiers

| Tier | File | What it does | When Copilot loads it |
|------|------|--------------|-----------------------|
| 1. Global index | `.github/copilot-instructions.md` | Compact table of all knowledge units | Every chat in the repository |
| 2. Path-specific | `.github/instructions/*.instructions.md` | Scoped units with `applyTo` frontmatter | When editing files matching the patterns |
| 3. Agent | `.github/agents/kcp-expert.agent.md` | Full knowledge navigator agent | When invoked with `@kcp-expert` |

### Quick start: `--generate-all`

```bash
npx kcp-mcp --generate-all knowledge.yaml
```

This single command writes all three tiers:

```
.github/
  copilot-instructions.md          # Tier 1: compact index (always loaded)
  instructions/
    docs.instructions.md           # Tier 2: path-specific (loaded when editing docs/**)
    src.instructions.md            #         (loaded when editing src/**)
    ...
  agents/
    kcp-expert.agent.md            # Tier 3: full agent (invoked with @kcp-expert)
```

Commit the `.github/` directory. No server needed — Copilot reads these files natively.

### Splitting strategies (`--split-by`)

Control how units are grouped into path-specific instruction files:

| Strategy | Behavior | Best for |
|----------|----------|----------|
| `directory` (default) | Groups by the top-level directory of each unit's `path` | Most projects |
| `scope` | Groups by scope (`global`, `project`, `module`) | Small manifests with clear scope boundaries |
| `unit` | One file per unit | Maximum granularity |
| `none` | Single file with all units | Simple projects |

```bash
# Custom split strategy
npx kcp-mcp --generate-instructions knowledge.yaml --output-dir .github/instructions --split-by scope
```

### Output formats (`--output-format`)

```bash
# Full (default) — verbose headings per unit
npx kcp-mcp --generate-instructions knowledge.yaml --output-format full

# Compact — markdown table (used by --generate-all for Tier 1)
npx kcp-mcp --generate-instructions knowledge.yaml --output-format compact

# Agent — table with navigator instructions
npx kcp-mcp --generate-instructions knowledge.yaml --output-format agent
```

### CI/CD: keep instructions in sync

Add this GitHub Actions workflow to regenerate instruction files when your knowledge manifest or docs change:

```yaml
name: KCP Sync Instructions
on:
  push:
    branches: [main]
    paths: ['knowledge.yaml', 'docs/**']
  schedule:
    - cron: '0 6 * * 1'
  workflow_dispatch:
permissions:
  contents: write
jobs:
  sync:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          token: ${{ secrets.GITHUB_TOKEN }}
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
      - run: npx kcp-mcp --generate-all knowledge.yaml
      - name: Commit if changed
        run: |
          git diff --quiet .github/ && exit 0
          git config user.name "github-actions[bot]"
          git config user.email "github-actions[bot]@users.noreply.github.com"
          git add .github/
          git commit -m "chore: sync KCP instruction artifacts"
          git push
```

### VS Code setting

Enable path-specific instruction loading in VS Code by adding to `.vscode/settings.json`:

```json
{
  "chat.includeApplyingInstructions": true
}
```

### Using the `@kcp-expert` agent

Once Tier 3 is committed, invoke the agent in Copilot Chat:

```
@kcp-expert How does authentication work in this project?
@kcp-expert What units are related to deployment?
```

The agent will scan the knowledge table, read the relevant files, and summarize with cross-references.

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
    args: ["kcp-mcp@0.10.0", "knowledge.yaml"]
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

## Comparison: Copilot integration options

| Capability | MCP server (Option A) | Static single-file (Option B) | Static 3-tier (Option C) | Claude Code (hooks + MCP) |
|---|---|---|---|---|
| Knowledge search | ✅ `search_knowledge` tool | ❌ static text | ❌ static text | ✅ same as MCP |
| Knowledge content | ✅ `get_unit` tool | ❌ all units loaded | ✅ path-scoped loading | ✅ same as MCP |
| CLI syntax | ✅ `get_command_syntax` tool | ❌ not available | ❌ not available | ✅ Phase A hook |
| CLI output filtering | ❌ not available | ❌ not available | ❌ not available | ✅ Phase B hook |
| Path-specific context | ❌ all-or-nothing | ❌ all-or-nothing | ✅ `applyTo` frontmatter | ✅ automatic |
| Custom agent | ✅ `.agent.md` + MCP | ❌ | ✅ `@kcp-expert` (static) | ✅ skills (automatic) |
| Infrastructure required | Node.js | none | none | Node.js |
| Works on GitHub.com | ❌ | ✅ | ✅ | ❌ |
| CI/CD sync | not needed | manual | ✅ GitHub Actions | not needed |
| Skill depth | 30K char limit per agent | full file | full file | unlimited |

**Recommendation:**
- **Option A (MCP)** when you can run Node.js and want dynamic search + on-demand content
- **Option B (single file)** for quick setup or GitHub.com-only use
- **Option C (3-tier)** for enterprise teams that need path-specific context without infrastructure
- **Claude Code** for maximum integration depth (hooks, skills, unlimited context)

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
