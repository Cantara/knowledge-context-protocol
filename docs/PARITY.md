# Bridge Parity

All three bridges (TypeScript, Java, Python) are required to stay at feature parity on **MCP tools and prompts**.
Static generation CLI flags (Tier 2) are currently TS + Java only — Python support is planned.

**Current version:** 0.14.0 (all three bridges)

---

## Rule

> Never ship a version where one bridge has MCP tools or prompts the others lack.

When adding any MCP capability:
1. Implement in TypeScript first (`bridge/typescript/src/`)
2. Implement in Java immediately after (`bridge/java/src/main/java/no/cantara/kcp/mcp/`)
3. Implement in Python (`bridge/python/kcp_mcp/`)
4. Add tests in all three (vitest for TS, JUnit for Java, pytest for Python)
5. Bump all three to the same version and update this file

---

## Tier 1: MCP Tools & Prompts (parity-required — all three bridges)

| Feature | TypeScript | Java | Python | Notes |
|---------|-----------|------|--------|-------|
| MCP Resources (list + read) | ✅ | ✅ | ✅ | |
| `search_knowledge` tool | ✅ | ✅ | ✅ | scoring: trigger=5, intent=3, id/path=1, top-5 |
| `search_knowledge`: `sensitivity_max` filter | ✅ | ✅ | ✅ | public < internal < confidential < restricted |
| `search_knowledge`: `exclude_deprecated` filter | ✅ | ✅ | ✅ | default true |
| `search_knowledge`: `match_reason` field | ✅ | ✅ | ✅ | which scoring rules fired |
| `search_knowledge`: `token_estimate` field | ✅ | ✅ | ✅ | from hints.token_estimate |
| `search_knowledge`: `summary_unit` field | ✅ | ✅ | ✅ | from hints.summary_unit |
| `get_unit` tool | ✅ | ✅ | ❌ | **gap — Python needs this** |
| `list_manifests` tool | ✅ | ✅ | ✅ | lists declared sub-manifests (federation §3.6) |
| `get_command_syntax` tool | ✅ | ✅ | ❌ | **gap — Python needs this** (requires `--commands-dir`) |
| `sdd-review` prompt | ✅ | ✅ | ❌ | **gap — Python needs this** |
| `kcp-explore` prompt | ✅ | ✅ | ❌ | **gap — Python needs this** |

---

## Tier 2: CLI / Static Generation (TypeScript + Java only — Python deferred)

| Feature | TypeScript | Java | Python | Notes |
|---------|-----------|------|--------|-------|
| `--generate-instructions` → stdout | ✅ | ✅ | — | |
| `--audience <value>` | ✅ | ✅ | — | filter by audience field |
| `--output-format full\|compact\|agent` | ✅ | ✅ | — | default: full |
| `--output-dir <path>` | ✅ | ✅ | — | triggers split mode |
| `--split-by directory\|scope\|unit\|none` | ✅ | ✅ | — | generates `applyTo` .instructions.md files |
| `--generate-agent` | ✅ | ✅ | — | writes `.agent.md` frontmatter to stdout |
| `--max-chars <n>` | ✅ | ✅ | — | truncates agent file intelligently |
| `--generate-all` | ✅ | ✅ | — | writes all three tiers to `.github/` |
| `--commands-dir <path>` | ✅ | ✅ | — | loads kcp-commands manifests |

---

## Tier 2 (shared): Runtime flags (all three bridges)

| Feature | TypeScript | Java | Python | Notes |
|---------|-----------|------|--------|-------|
| `--sub-manifests <glob>` | ✅ | ✅ | ✅ | merges additional manifests |
| `--agent-only` | ✅ | ✅ | ✅ | expose only agent-audience units |
| `--no-warnings` | ✅ | ✅ | ✅ | suppress validation warnings |
| `--transport stdio\|http` | ✅ | ✅ | ✅ | |
| `--port <n>` | ✅ | ✅ | ✅ | HTTP transport port |

---

## Test counts (v0.15.0)

| Bridge | Tests |
|--------|------:|
| TypeScript (vitest) | 160 |
| Java (JUnit) | 145 |
| Python (pytest) | 132 |
| **Total** | **437** |

---

## Known Python gaps (Tier 1)

The following MCP tools/prompts exist in TS + Java but not yet in Python:

| Gap | Priority | Notes |
|-----|----------|-------|
| `get_unit` | High | Simple: lookup by unit ID, return full unit JSON |
| `get_command_syntax` | Medium | Requires `--commands-dir` CLI flag |
| `sdd-review` prompt | Medium | Static prompt text + manifest context |
| `kcp-explore` prompt | Medium | Requires `topic` argument |

Closing these gaps is the next Python bridge milestone (target: v0.15.0).

---

## Version history

| Version | Date | Changes |
|---------|------|---------|
| 0.5.0 | 2026-02 | MCP Resources only |
| 0.6.0 | 2026-03-06 | MCP tools, prompts, `--generate-instructions`, Java parity |
| 0.10.0 | 2026-03-06 | Three-tier static integration, `--generate-all`, full parity |
| 0.14.0 | 2026-03-15 | RFC-0007 query baseline: `sensitivity_max`, `exclude_deprecated`, `match_reason`, `token_estimate`, `summary_unit` — all three bridges. Python bridge added to parity tracking. |

> **Note:** v0.7.0--v0.9.0 were internal development milestones that shipped combined as v0.10.0.
> v0.11.0--v0.13.0 were bridge feature additions that culminated in v0.14.0.
