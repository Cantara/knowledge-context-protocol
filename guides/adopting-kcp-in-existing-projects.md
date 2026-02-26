# Adopting KCP in an Existing Project

Most projects that adopt KCP are not starting from scratch. They have documentation, a
README, maybe an API spec, maybe structured skill files or agent definitions. They are
already doing KCP-level thinking — they just have not formalised it.

This guide is about formalising what you already have, not replacing it.

---

## What NOT to change

Your documents stay where they are. KCP wraps them with metadata — it does not move them,
restructure them, or replace your existing documentation workflow.

- Your `README.md` stays at the root. It becomes a KCP unit with `path: README.md`.
- Your `docs/` directory stays as-is. Each document becomes a unit.
- Your `llms.txt` (if you have one) stays. KCP coexists with it — see SPEC.md §9.
- Your skill files, agent definitions, hooks — all stay in place.

The only new file is `knowledge.yaml`. Everything else is declared, not created.

---

## Mature project? Start with classification

If your project already has 10+ artifacts — skill files, agent definitions, hooks, API specs,
documentation — **classify them by `kind` before doing anything else.**

The most disorienting part of retrofitting KCP onto a mature project is not knowing which
artifacts belong in the manifest and in what form. The `kind` taxonomy answers this:

| What you have | `kind` | How to think about it |
|---------------|--------|----------------------|
| README, guides, wiki pages | `knowledge` (default) | "What question does this answer?" |
| OpenAPI / AsyncAPI / gRPC proto | `schema` | "What structure does this define?" |
| Running API, MCP server, webhook | `service` | "What capability does this expose?" |
| Skill files, behavioral instructions | `knowledge` | "What does this teach the agent to do?" |
| Agent definitions, runnable workers | `executable` | "What does this do when invoked?" |
| Pre-commit hooks, policy rules | `policy` | "What rule does this enforce?" |

**Why classification first:** The `kind` determines how you write the `intent`. A policy's
intent ("What checks run before every commit?") is fundamentally different from a knowledge
unit's intent ("How do I authenticate API requests?"). If you try to write intents before
classifying, you will write documentation-style intents for everything — and the dispatch
signal that agents need will be missing.

**The mature-project path through this guide:**

1. Classify all artifacts by `kind` (this section — you just did it)
2. Step 1: Audit with classification in hand (the questions now have context)
3. Step 2: Write Level 1 entries — include `kind` from the start
4. Steps 3-5: Add structure, triggers, and relationships as normal

If you are starting a new project with mostly documentation, skip this section and go
straight to Step 1.

---

## Step 1: Audit what you already have

Walk your project and list the knowledge artifacts that exist:

```
README.md                    — project overview
docs/architecture.md         — architecture decisions
docs/api/authentication.md   — API guide
openapi/payments.yaml        — API definition
.claude/skills/tdd.yaml      — development workflow skill
.claude/agents/health-check.md — agent definition
.husky/pre-commit            — commit gate hook
```

For each one, ask:
1. What kind of artifact is it — documentation, schema, executable, policy? (→ `kind`)
2. What question does this answer, or what does it do? (→ `intent`)
3. Who is it for? (→ `audience`)
4. Does anything need to be read before this? (→ `depends_on`)

---

## Step 2: Map to KCP units (Level 1 — minimum viable)

Start with just `id`, `path`, and `intent`. Nothing else is required at Level 1.

```yaml
kcp_version: "0.1"
project: my-project
version: 1.0.0

units:
  - id: overview
    path: README.md
    intent: "What is this project and how do I get started?"

  - id: architecture
    path: docs/architecture.md
    intent: "What are the architectural decisions and why were they made?"

  - id: auth-guide
    path: docs/api/authentication.md
    intent: "How do I authenticate API requests?"
```

This is a valid, complete KCP manifest. Stop here if this is enough.

---

## Step 3: Add structure (Level 2)

Add `scope`, `audience`, `validated`, and `depends_on` where they add value.
Do not add fields for the sake of completeness — only where the metadata is meaningful.

```yaml
  - id: auth-guide
    path: docs/api/authentication.md
    intent: "How do I authenticate API requests?"
    scope: module
    audience: [developer, agent]
    validated: 2026-02-26
    depends_on: [architecture]
```

`validated` is the most valuable field to add first — it makes stale documentation
visible. An agent seeing a `validated` date from before a major release can flag the unit
as potentially stale.

---

## Step 4: Verify `kind` for non-documentation artifacts

If you classified artifacts by `kind` earlier (see "Mature project? Start with
classification"), verify your classifications against the table below. If you skipped
classification, now is the time to add `kind` to any unit that is not narrative documentation.

| What you have | `kind` | Default behavior |
|---------------|--------|-----------------|
| README, guides, wiki pages | `knowledge` (default) | Agent loads and embeds |
| OpenAPI / AsyncAPI / gRPC proto | `schema` | Agent parses as structured definition |
| Running API, MCP server, webhook | `service` | Agent invokes via protocol |
| Skill files, behavioral instructions | `knowledge` | Agent loads and embeds |
| Agent definitions, runnable workers | `executable` | Agent invokes on demand |
| Pre-commit hooks, policy rules | `policy` | Agent evaluates as gate |

```yaml
  - id: payments-api-spec
    kind: schema
    path: openapi/payments.yaml
    intent: "What endpoints does the Payments API expose?"
    scope: module
    audience: [developer, agent]

  - id: health-check-agent
    kind: executable
    path: .claude/agents/health-check.md
    intent: "How do I run a health check on this project's AI context setup?"
    scope: project
    audience: [agent]

  - id: pre-commit-gate
    kind: policy
    path: .husky/pre-commit
    intent: "What checks run automatically before every commit?"
    scope: project
    audience: [developer, agent]
```

---

## Step 5: Add `triggers` and `relationships` where useful (Level 3)

`triggers` enable task-based retrieval — an agent working on authentication finds the
auth guide without scanning every unit.

`relationships` make the dependency graph explicit and navigable.

```yaml
  - id: auth-guide
    path: docs/api/authentication.md
    intent: "How do I authenticate API requests?"
    scope: module
    audience: [developer, agent]
    validated: 2026-02-26
    depends_on: [architecture]
    triggers: [authentication, oauth2, bearer-token, jwt, api-security]

relationships:
  - from: architecture
    to: auth-guide
    type: enables
```

---

## Common friction points

### "Where does `knowledge.yaml` go in a monorepo?"

Place it at the root of each subproject that has its own documentation. Each manifest
is independent (SPEC.md §1.3). Cross-manifest references are not supported in v0.1 —
see issue #12 for the federation proposal.

Alternatively, place a single manifest at the monorepo root covering all subprojects,
using relative paths: `path: services/payments/docs/auth.md`.

### "My docs are in Confluence, not files."

KCP requires a `path` to a content file. For external documentation, either:
- Export key documents to markdown files and declare those
- Create stub markdown files that summarise and link to the external source
- Wait for federation support (issue #12) which may support URL-based paths in v0.2

### "How do I keep the manifest in sync with actual docs?"

The `validated` field is your sync signal. Set it when you confirm content is accurate.
When content changes, update the `validated` date. A unit with a `validated` date more
than 90 days old is a candidate for review.

Tooling can automate this: a pre-commit hook that warns when a committed doc change does
not update the corresponding unit's `validated` date in `knowledge.yaml`.

### "What if several units cover the same topic?"

Use `supersedes` to mark the older unit as replaced:

```yaml
  - id: deployment-guide
    path: ops/deployment-v3.md
    intent: "How do I deploy version 3.x to production?"
    supersedes: deployment-guide-v2
```

The superseded unit can remain in the manifest (for historical reference) or be removed.

### "Should the manifest describe team-shared or personal/local knowledge?"

Team-shared. The `knowledge.yaml` manifest lives in the repository and describes knowledge
that is shared across the team and its agents. Personal or local knowledge — your own
scratch notes, local agent memory, per-developer configuration — does not belong in the
manifest.

If you need to distinguish team knowledge from personal knowledge, the signals are:
- **In the manifest:** committed to the repository, audience includes `developer` or `agent`
- **Not in the manifest:** local-only files (`.claude/memory/`, personal notes, local config)
- **Grey area:** team-level agent memory or shared skill files that are committed but
  developer-specific — include these with `audience: [agent]` so the intent is clear

### "I have 80+ documents. Do I need to declare all of them?"

No. KCP is not exhaustive — it describes the knowledge that matters for navigation.
Start with the 10-20 documents that agents and developers consult most. Add more as
the value of having them in the manifest becomes clear.

---

## What a "~80% organically compliant" project looks like

If your project already has:
- A `README.md` with a clear project description
- Structured documentation in a `docs/` directory
- Skill files or agent definitions in `.claude/`
- A `llms.txt` or similar machine-readable index

...then you are already doing KCP-level thinking. The retrofit is mostly adding
`knowledge.yaml` and writing one sentence (`intent`) per document. A project with
20 key documents can be at Level 2 KCP compliance in under two hours.

The biggest value-add for projects already at this level is `validated` dates —
making documentation staleness visible — and `kind` for non-documentation artifacts,
which gives agents the dispatch signal they need to interact correctly with API specs,
agent definitions, and hooks.

---

## Validation

Check your manifest is valid:

```bash
# Python
cd parsers/python && python -m kcp validate /path/to/knowledge.yaml

# Java
cd parsers/java && mvn exec:java -Dexec.mainClass="no.cantara.kcp.KcpCli" \
  -Dexec.args="validate /path/to/knowledge.yaml"
```

Both parsers follow the validation rules in SPEC.md §7: required fields produce errors,
optional gaps produce warnings, unknown fields are silently ignored.

---

*See also: [SPEC.md §8 Conformance Levels](../SPEC.md) · [RFC-0001 Extended Capabilities](../RFC-0001-KCP-Extended.md) · [Issue #12 Federation](https://github.com/Cantara/knowledge-context-protocol/issues/12)*
