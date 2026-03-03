# opencode-kcp-plugin

An [OpenCode](https://github.com/anomalyco/opencode) plugin that uses a
[`knowledge.yaml`](https://github.com/Cantara/knowledge-context-protocol) manifest to reduce
explore-agent tool calls by 73–80%.

## What it does

If your project has a `knowledge.yaml` at its root, this plugin:

1. **Injects a knowledge map into every session's system prompt** — the agent sees a compact
   index of key files with intent descriptions and trigger keywords before it starts any search.
2. **Annotates glob/grep results** — file paths that match KCP units get an inline intent
   description appended, so the agent can identify the right file without reading it first.

Measured in comparable codebases: **67,352 tokens saved per session**, **33.7% of a 200K
context window recovered**, **73–80% fewer tool calls** in explore-heavy sessions.

## Install

```bash
npm install opencode-kcp-plugin
```

Add to your `opencode.json` (or `.opencode/opencode.jsonc`):

```json
{
  "plugin": ["opencode-kcp-plugin"]
}
```

That's it. The plugin is a no-op if no `knowledge.yaml` is present.

## Add a knowledge.yaml to your project

Minimum viable manifest (five fields per unit):

```yaml
kcp_version: "0.5"
project: my-project
version: 1.0.0
units:
  - id: readme
    path: README.md
    intent: "What is this project and how do I get started?"
    scope: global
    audience: [human, agent]
```

Add `triggers` (keywords), `depends_on` (load order), and `hints.load_strategy: always` for
files the agent should always read. See the [KCP spec](https://github.com/Cantara/knowledge-context-protocol)
for the full field set.

`synthesis export --format kcp` will generate a `knowledge.yaml` from an existing Synthesis index.

## How it works

The plugin uses two OpenCode hooks:

| Hook | What it does |
|------|-------------|
| `experimental.chat.system.transform` | Prepends the full knowledge map to the system prompt. The agent reads this before starting any exploration. |
| `tool.execute.after` (glob, grep) | Annotates matching file paths in glob/grep output with KCP intent strings. |

No runtime dependencies beyond `js-yaml`. Zero overhead when `knowledge.yaml` is absent.

## System prompt output (example)

For a 17-unit manifest, the injected section looks like:

```
## Codebase Knowledge Map

This project has a `knowledge.yaml` manifest (KCP). Use this map to find
files directly before running glob/grep searches.
★ = load immediately  ·  space = load on demand

★ [readme] README.md
    What is OpenCode, how to install it, and what makes it different from Claude Code
    keywords: overview, install, getting started, features

★ [agents-md] AGENTS.md
    Coding style conventions, naming rules, and testing practices for this codebase
    keywords: style, naming, conventions, testing, code quality

  [config-schema] packages/opencode/src/config/config.ts
    Full config schema: providers, MCP servers, agents, skills, permissions, keybindings
    keywords: config, opencode.json, settings, schema, provider, MCP, permissions

  [agent-definitions] packages/opencode/src/agent/agent.ts  (after: config-schema)
    How agents (build, plan, explore, general) are defined, configured, and composed
    keywords: agent, build agent, plan agent, explore agent, subagent, permissions
...
```

~800 tokens for 17 units. The agent uses this to resolve "where is the skill system?" in one
lookup instead of 4–8 grep/glob/read calls.

## Spec

[Knowledge Context Protocol v0.5](https://github.com/Cantara/knowledge-context-protocol) —
Apache 2.0. Submitted to the Agentic AI Foundation (Linux Foundation) for neutral governance
alongside MCP and AGENTS.md.
