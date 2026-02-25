# Knowledge Context Protocol (KCP)

> A structured metadata standard that makes knowledge navigable by AI agents.

**KCP is to knowledge what MCP is to tools.**

The [Model Context Protocol](https://modelcontextprotocol.io) defines how agents connect to tools.
KCP defines how knowledge is structured so those tools can serve it effectively.
MCP solved the tool connectivity problem. KCP addresses the knowledge structure problem that remains.

---

## The Problem with llms.txt

Jeremy Howard's [llms.txt](https://llmstxt.org) solved a real problem: it gives AI agents a
canonical, machine-readable index of a website. For personal sites and small documentation sets,
it works well.

But llms.txt has six structural limitations that matter at scale:

| Limitation | What it means |
|-----------|---------------|
| **Flat topology** | Lists what exists. Cannot express that A depends on B, or that C supersedes D. |
| **No selective loading** | All or nothing: the index (small) or the full dump (huge). No middle ground. |
| **No intent metadata** | A URL and a description. No way to say what task this knowledge is relevant to. |
| **No freshness signal** | Cannot distinguish a document written yesterday from one written three years ago. |
| **No tooling connection** | A static file. No query interface, no dependency graph, no retrieval integration. |
| **Scale collapse** | Works for 27 blog posts. Fails for 8,934 files across an enterprise. |

These are structural limitations, not incidental ones. A bigger text file does not solve them.

---

## What KCP Provides

KCP is a file format specification — a `knowledge.yaml` manifest you drop at the root of a
project or documentation site. It adds the metadata layer that llms.txt cannot express:

- **Topology**: what depends on what, what supersedes what
- **Intent**: what task or question each knowledge unit answers
- **Freshness**: when each unit was last validated, and against what
- **Selective loading**: agents query by task context, not by URL guessing
- **Audience targeting**: which units are for humans, which for agents, which for both

---

## The Spec

### Root Manifest: `knowledge.yaml`

```yaml
project: <name>
version: 1.0.0
updated: <ISO date>

units:
  - id: <unique-identifier>
    path: <relative path to markdown file>
    intent: "<What question does this answer?>"
    scope: global | project | module
    audience: [human, agent, developer, operator, architect]
    validated: <ISO date>
    depends_on: [<unit-id>, ...]       # optional
    supersedes: <unit-id>              # optional
    triggers: [<keyword>, ...]         # optional

relationships:
  - from: <unit-id>
    to: <unit-id>
    type: enables | context | supersedes | contradicts
```

### Knowledge Unit Fields

| Field | Required | Description |
|-------|----------|-------------|
| `id` | yes | Unique identifier within the project |
| `path` | yes | Relative path to the Markdown content file |
| `intent` | yes | One sentence: what question does this unit answer? |
| `scope` | yes | `global`, `project`, or `module` |
| `audience` | yes | Who this is for: `human`, `agent`, `developer`, `operator`, `architect` |
| `validated` | recommended | ISO date when content was last confirmed accurate |
| `depends_on` | optional | Units that must be understood before this one |
| `supersedes` | optional | The unit-id this replaces |
| `triggers` | optional | Task contexts or keywords that make this unit relevant |

### Minimum Viable KCP

Three fields are enough to start:

```yaml
project: my-project
version: 1.0.0
units:
  - id: overview
    path: README.md
    intent: "What is this project and how do I get started?"
```

The standard allows complexity but does not demand it.

---

## Complete Example

```yaml
# knowledge.yaml
project: wiki.example.org
version: 1.0.0
updated: 2026-02-25

units:
  - id: about
    path: about.md
    intent: "Who maintains this project? Background, current work, contact."
    scope: global
    audience: [human, agent]
    validated: 2026-02-24

  - id: methodology
    path: methodology/overview.md
    intent: "What development methodology is used? Principles, evidence, adoption."
    scope: global
    audience: [developer, architect, agent]
    depends_on: [about]
    validated: 2026-02-13
    triggers: ["methodology", "productivity", "workflow"]

  - id: knowledge-infrastructure
    path: tools/knowledge-infra.md
    intent: "How is knowledge infrastructure set up? Architecture, indexing, deployment."
    scope: global
    audience: [developer, devops, agent]
    depends_on: [methodology]
    validated: 2026-02-25
    supersedes: knowledge-infra-v1
    triggers: ["knowledge infrastructure", "MCP", "code search", "indexing"]

relationships:
  - from: methodology
    to: knowledge-infrastructure
    type: enables
  - from: about
    to: methodology
    type: context
```

---

## Adoption Gradient

KCP is designed to be adopted incrementally.

**Level 1 — Personal sites and small projects**
Drop a `knowledge.yaml` alongside your `llms.txt`. Add `id`, `path`, and `intent` for your key
pages. Five minutes. Immediately navigable by agents.

**Level 2 — Open source projects**
Add `depends_on` and `validated` fields. Agents can now load documentation in dependency order
and check freshness before acting on it.

**Level 3 — Enterprise documentation**
Use the full field set including `triggers`, `audience`, and `relationships`. Build
knowledge-graph-navigable documentation that supports multiple agent roles querying the same
corpus with different task contexts.

---

## Relationship to MCP and Synthesis

**MCP** (Model Context Protocol) defines how agents connect to tools. KCP defines how knowledge
is structured for those tools to serve.

**Synthesis** is a knowledge infrastructure tool and reference implementation of a KCP-native
knowledge server. It indexes workspaces — code, documentation, configuration, PDFs — and serves
them via MCP with sub-second retrieval. KCP is the format specification; Synthesis is one engine
that implements it.

`synthesis export --format kcp` will generate a `knowledge.yaml` from an existing
Synthesis index automatically.

---

## Status

**Current:** Draft specification — v0.1.0

This is an early proposal. The format is intentionally minimal. Feedback, use cases, and pull
requests are welcome.

Reference parser: (Python/Java) in parsers

---

## Contributing

Open an issue to:
- Propose additions to the field set
- Share a use case that the current spec does not cover
- Report a gap or ambiguity in the format

The goal is a standard that solves the real problem without demanding complexity from those who
do not need it.

---

## License

MIT.

*Proposed by [eXOReaction AS](https://www.exoreaction.com) — builders of Synthesis, based in Oslo, Norway.*
