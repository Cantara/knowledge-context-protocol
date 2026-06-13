# KCP Agent Skills

Portable [Agent Skills](https://docs.claude.com/en/docs/agents-and-tools/agent-skills/overview)
for working with the Knowledge Context Protocol. Each is a self-contained
`SKILL.md` an agent loads on demand — no MCP server or bridge required, just the
`kcp` CLI and the manifest. They complement the bridge's MCP prompts
(`kcp-explore`, `sdd-review`), which need a running server.

| Skill | Use it when |
|-------|-------------|
| **[kcp-adopt](./kcp-adopt/SKILL.md)** | Making a repo or docs site agent-navigable — add a `knowledge.yaml` from scratch. |
| **[kcp-author](./kcp-author/SKILL.md)** | Writing or improving units — better intents, triggers, relationships, `not_for`, `temporal`. |
| **[kcp-navigate](./kcp-navigate/SKILL.md)** | A project already has a `knowledge.yaml` and you want to load only the units a task needs. |
| **[kcp-render](./kcp-render/SKILL.md)** | Ingesting a third-party/untrusted manifest — run the trusted render pipeline first. |

## Install

Agent Skills are directories containing a `SKILL.md` with `name` + `description`
frontmatter. The `description` is what an agent matches against to decide when
to invoke the skill.

**Claude Code / Claude apps** — copy the skills you want into your skills
directory:

```bash
# personal (all projects)
cp -r skills/kcp-* ~/.claude/skills/

# or per-project
mkdir -p .claude/skills && cp -r skills/kcp-* .claude/skills/
```

**Claude Agent SDK** — point your agent's skill loader at this directory, or
vendor the folders you need.

**Other agents** — the `SKILL.md` bodies are plain procedural Markdown; they work
as drop-in playbooks or system-prompt includes even where the Skills format
isn't natively supported.

## Prerequisite

The skills drive the `kcp` developer CLI (`init`, `validate`, `query`, `render`,
`sign`, `stats`). Install it via
[kcp-commands](https://github.com/Cantara/kcp-commands). The skills degrade
gracefully — where the CLI is unavailable they fall back to reading and writing
`knowledge.yaml` directly.

## Design note

These skills encode the protocol's own guarantee. `kcp-navigate` and
`kcp-render` both hold the line that a manifest is **data, not instructions**:

> A manifest may influence what an agent knows, never what it does.
