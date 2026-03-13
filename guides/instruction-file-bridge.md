# Instruction File Bridge

KCP manifests can serve as the single source of truth from which vendor-specific instruction
files are generated. This guide documents the conventions for bridging KCP metadata into
the instruction file formats used by popular AI coding tools.

---

## Why a single source?

AI coding tools use different instruction files — `CLAUDE.md`, `.github/copilot-instructions.md`,
`.github/agents.json`, `AGENTS.md`, `llms.txt` — each with its own format and conventions. When
these files are maintained independently, they drift apart. A project ends up with contradictory
instructions across tools.

KCP solves this by declaring knowledge units with structured metadata (`audience`, `kind`,
`load_strategy`, `scope`). Tooling can then **generate** vendor-specific files from the manifest,
ensuring consistency.

---

## Mapping rules

### `CLAUDE.md` preamble

Units with `audience: [agent]` and `hints.load_strategy: eager` are candidates for the
`CLAUDE.md` preamble — the instructions that Claude Code loads at the start of every session.

```yaml
# In knowledge.yaml
units:
  - id: coding-standards
    path: docs/coding-standards.md
    intent: "What coding standards must all code follow?"
    scope: project
    audience: [agent]
    kind: policy
    hints:
      load_strategy: eager
```

**Generation rule:** Concatenate the content of all eager agent units into `CLAUDE.md`,
ordered by declaration order in the manifest.

### `.github/copilot-instructions.md`

Units with `audience: [agent]` — regardless of `load_strategy` — are candidates for
Copilot instructions. This file is typically a flat markdown document.

```yaml
  - id: api-conventions
    path: docs/api-conventions.md
    intent: "What conventions apply to API endpoint design?"
    scope: project
    audience: [agent, developer]
```

**Generation rule:** Include all `audience: [agent]` units. Use the `intent` field as the
section heading for each unit's content.

### `.github/agents.json`

Units with `kind: policy` map to agent definition entries in `.github/agents.json`:

```yaml
  - id: security-policy
    path: policies/security.md
    intent: "What security requirements apply to all code changes?"
    scope: project
    audience: [agent]
    kind: policy
```

**Generation rule:** Each `kind: policy` unit with `audience: [agent]` becomes an entry in
the agents configuration, with `intent` as the description and `path` as the source.

---

## Comment header convention

Generated files SHOULD include a comment header indicating their KCP origin. This enables
tooling to detect generated files and avoid manual edits that would be overwritten:

```markdown
<!-- kcp-source: knowledge.yaml -->
<!-- kcp-generated: 2026-03-13 -->
```

For files that do not support HTML comments (e.g., JSON), use a top-level metadata field:

```json
{
  "_kcp_source": "knowledge.yaml",
  "_kcp_generated": "2026-03-13",
  ...
}
```

When a tool encounters these headers, it knows the file was generated from KCP and can:
1. Re-generate it from the manifest instead of editing it directly.
2. Warn the user if manual edits are detected.
3. Diff the generated output against the current file to detect drift.

---

## Bidirectional path

### Generate FROM knowledge.yaml (forward)

The primary direction: a `kcp generate` or equivalent command reads `knowledge.yaml` and
produces vendor-specific instruction files.

```
knowledge.yaml  -->  CLAUDE.md
                -->  .github/copilot-instructions.md
                -->  .github/agents.json
```

### Discover knowledge.yaml from existing files (reverse)

When adopting KCP in a project that already has instruction files, tooling can discover
existing knowledge by scanning for:

| File | What it tells us |
|------|------------------|
| `CLAUDE.md` | Agent-facing instructions exist; likely candidates for `audience: [agent]`, `load_strategy: eager` |
| `.github/copilot-instructions.md` | Agent-facing instructions; `audience: [agent]` |
| `.github/agents.json` | Policy definitions; `kind: policy`, `audience: [agent]` |
| `AGENTS.md` | Agent definitions; `kind: executable` or `kind: policy` |
| `llms.txt` | Existing machine-readable index; may contain paths to document |
| `README.md` | Project overview; `scope: global`, `audience: [human, agent]` |
| `docs/` | Documentation directory; multiple `kind: knowledge` units |
| `.claude/skills/` | Skill files; `audience: [agent]`, `kind: knowledge` |

This reverse discovery is what `kcp init --scan` performs (see the
[adopting guide](./adopting-kcp-in-existing-projects.md)).

---

## Generation scope by tool

| Target file | Which units | Key filters |
|-------------|-------------|-------------|
| `CLAUDE.md` | `audience: [agent]` + `hints.load_strategy: eager` | Concatenate content, declaration order |
| `.github/copilot-instructions.md` | `audience: [agent]` | All agent units, `intent` as heading |
| `.github/agents.json` | `kind: policy` + `audience: [agent]` | One entry per policy unit |
| `llms.txt` | All units | One line per unit: `path: intent` |

---

## Example workflow

1. **Author** writes `knowledge.yaml` with proper `audience`, `kind`, and `hints` metadata.
2. **CI/pre-commit hook** runs `kcp generate` to produce/update instruction files.
3. **Generated files** include the `<!-- kcp-source -->` header.
4. **Developers** edit `knowledge.yaml` (the source), not the generated files.
5. **Drift detection** warns if a generated file has been manually modified.

---

## Limitations

- **Content transformation:** This guide covers mapping metadata, not content transformation.
  A `CLAUDE.md` preamble may need content to be reformatted (e.g., from structured markdown
  to a single instruction block). Content transformation is tool-specific.
- **Vendor-specific features:** Some instruction files support features with no KCP equivalent
  (e.g., Copilot's `@workspace` references). These should be added manually after generation,
  or maintained in a separate non-generated section of the file.

---

*See also: [Adopting KCP in Existing Projects](./adopting-kcp-in-existing-projects.md) · [SPEC.md §4.10 Hints](../SPEC.md)*
