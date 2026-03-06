# Bridge Parity

Both bridges (TypeScript and Java) are required to stay at feature parity.
This file is the truth. Update it when adding or removing features.

**Current version:** 0.10.0 (both bridges)

---

## Rule

> Never ship a version where one bridge has features the other lacks.

When adding any capability:
1. Implement in TypeScript first (`bridge/typescript/src/`)
2. Implement in Java immediately after (`bridge/java/src/main/java/no/cantara/kcp/mcp/`)
3. Add tests in both (vitest for TS, JUnit for Java)
4. Bump both to the same version in `package.json` and `pom.xml`
5. Publish both and update this file

---

## Feature matrix (v0.10.0)

| Feature | TypeScript | Java | Notes |
|---------|-----------|------|-------|
| MCP Resources (list + read) | ✅ | ✅ | |
| `search_knowledge` tool | ✅ | ✅ | scoring: trigger=5, intent=3, id/path=1, top-5 |
| `get_unit` tool | ✅ | ✅ | |
| `get_command_syntax` tool | ✅ | ✅ | requires `--commands-dir` |
| `sdd-review` prompt | ✅ | ✅ | focus: architecture/quality/security/performance |
| `kcp-explore` prompt | ✅ | ✅ | requires `topic` argument |
| `--generate-instructions` → stdout | ✅ | ✅ | |
| `--audience <value>` | ✅ | ✅ | filter by audience field |
| `--output-format full\|compact\|agent` | ✅ | ✅ | default: full |
| `--output-dir <path>` | ✅ | ✅ | triggers split mode |
| `--split-by directory\|scope\|unit\|none` | ✅ | ✅ | generates `applyTo` .instructions.md files |
| `--generate-agent` | ✅ | ✅ | writes `.agent.md` frontmatter to stdout |
| `--max-chars <n>` | ✅ | ✅ | truncates agent file intelligently |
| `--generate-all` | ✅ | ✅ | writes all three tiers to `.github/` |
| `--commands-dir <path>` | ✅ | ✅ | loads kcp-commands manifests |
| `--sub-manifests <glob>` | ✅ | ✅ | merges additional manifests |
| `--agent-only` | ✅ | ✅ | expose only agent-audience units |
| `--no-warnings` | ✅ | ✅ | suppress validation warnings |
| `--transport stdio\|http` | ✅ | ✅ | |
| `--port <n>` | ✅ | ✅ | HTTP transport port |

## Test counts (v0.10.0)

| Bridge | Tests |
|--------|------:|
| TypeScript (vitest) | 131 |
| Java (JUnit) | 137 |
| **Total** | **268** |

---

## Version history

| Version | Date | Changes |
|---------|------|---------|
| 0.5.0 | 2026-02 | MCP Resources only |
| 0.6.0 | 2026-03-06 | MCP tools, prompts, `--generate-instructions`, Java parity |
| 0.10.0 | 2026-03-06 | Three-tier static integration, `--generate-all`, full parity |

> **Note:** v0.7.0--v0.9.0 were internal development milestones that shipped combined as v0.10.0.
