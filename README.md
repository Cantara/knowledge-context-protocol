# Knowledge Context Protocol (KCP)

> A structured metadata standard that makes knowledge navigable by AI agents.

**KCP is to knowledge what MCP is to tools.**

→ [**Website**](https://cantara.github.io/knowledge-context-protocol/) · [**Read the spec**](./SPEC.md) · [Read the proposal](./PROPOSAL.md)

The [Model Context Protocol](https://modelcontextprotocol.io) defines how agents connect to tools.
KCP defines how knowledge is structured so those tools can serve it effectively.
MCP solved the tool connectivity problem. KCP addresses the knowledge structure problem that remains.

---

## In 30 Seconds

Drop a `knowledge.yaml` at the root of any project. Agents stop guessing and start navigating.

```yaml
kcp_version: "0.7"
project: my-project
version: 1.0.0
units:
  - id: overview
    path: README.md
    intent: "What is this project and how do I get started?"
    scope: global
    audience: [human, agent]
    triggers: [overview, getting started, quickstart]
```

**Validated result:** 53–80% fewer agent tool calls vs unguided exploration
(tested across 5 frameworks: crewAI, AutoGen, smolagents, LangChain, OpenCode).

**Five minutes to Level 1.** See [adopting KCP in existing projects](./guides/adopting-kcp-in-existing-projects.md).

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
language: <BCP 47 tag>             # optional — default for all units
license: <SPDX identifier>        # optional — default for all units
indexing: open | read-only | no-train | none  # optional — default for all units
hints:                             # optional — manifest-level aggregate hints
  total_token_estimate: <integer>
  recommended_entry_point: <unit-id>
  has_summaries: true | false
trust:                             # optional — publisher provenance and audit
  provenance:
    publisher: <string>
    publisher_url: <string>
    contact: <string>
  audit:                           # optional — agent audit requirements
    agent_must_log: true | false
    require_trace_context: true | false
auth:                              # optional — authentication methods
  methods:
    - type: none | oauth2 | api_key
delegation:                        # optional — delegation constraints (v0.7)
  max_depth: 3                     # 0 = no delegation; absent = unlimited
  require_capability_attenuation: true
  audit_chain: true
  human_in_the_loop: recommended
compliance:                        # optional — data governance (v0.7)
  data_residency: [EU]
  sensitivity: confidential
  regulations: [GDPR, NIS2]
  restrictions: [no_ai_training]
payment:                           # optional — default monetisation tier
  default_tier: free | metered | subscription

units:
  - id: <unique-identifier>
    path: <relative path to content file>
    kind: knowledge | schema | service | policy | executable  # optional; default: knowledge
    intent: "<What question does this answer?>"
    format: markdown | pdf | openapi | ...  # optional
    content_type: <MIME type>              # optional
    language: <BCP 47 tag>                 # optional
    scope: global | project | module
    audience: [human, agent, developer, operator, architect]
    license: <SPDX identifier or object>  # optional
    validated: <ISO date>
    update_frequency: hourly | daily | weekly | monthly | rarely | never  # optional
    indexing: open | read-only | no-train | none  # optional
    depends_on: [<unit-id>, ...]       # optional
    supersedes: <unit-id>              # optional
    triggers: [<keyword>, ...]         # optional
    hints:                             # optional — context window hints
      token_estimate: <integer>        # approximate token count
      load_strategy: eager | lazy | never  # when to load; default: lazy
      priority: critical | supplementary | reference  # eviction order; default: supplementary
      density: dense | standard | verbose  # information density; default: standard
      summary_available: true | false  # shorter version exists in this manifest
      summary_unit: <unit-id>          # id of the summary unit
      summary_of: <unit-id>            # id of the full unit this summarises
    access: public | authenticated | restricted  # optional; default: public
    auth_scope: <string>               # optional — opaque scope token for restricted units
    sensitivity: public | internal | confidential | restricted  # optional
    deprecated: true | false          # optional; default: false
    payment:                           # optional — override root default
      default_tier: free | metered | subscription

relationships:
  - from: <unit-id>
    to: <unit-id>
    type: enables | context | supersedes | contradicts
```

### Knowledge Unit Fields

| Field | Required | Description |
|-------|----------|-------------|
| `id` | yes | Unique identifier within the project |
| `path` | yes | Relative path to the content file |
| `kind` | optional | Type of artifact: `knowledge` (default), `schema`, `service`, `policy`, `executable` |
| `intent` | yes | One sentence: what question does this unit answer? |
| `format` | optional | Content format: `markdown`, `pdf`, `openapi`, `json-schema`, `jupyter`, etc. |
| `content_type` | optional | Full MIME type for precise format identification |
| `language` | optional | BCP 47 language tag (e.g. `en`, `no`, `de`) |
| `scope` | yes | `global`, `project`, or `module` |
| `audience` | yes | Who this is for: `human`, `agent`, `developer`, `operator`, `architect` |
| `license` | optional | SPDX identifier or structured license object |
| `validated` | recommended | ISO date when content was last confirmed accurate |
| `update_frequency` | optional | How often content changes: `hourly`, `daily`, `weekly`, `monthly`, `rarely`, `never` |
| `indexing` | optional | AI crawling permissions: `open`, `read-only`, `no-train`, `none`, or structured object |
| `depends_on` | optional | Units that must be understood before this one |
| `supersedes` | optional | The unit-id this replaces |
| `triggers` | optional | Task contexts or keywords that make this unit relevant |
| `hints` | optional | Advisory context window hints: `token_estimate`, `load_strategy`, `priority`, `density`, `summary_available`, `summary_unit`, `summary_of` |
| `access` | optional | Who can fetch this unit: `public` (default), `authenticated`, `restricted` |
| `auth_scope` | optional | Opaque scope token indicating which credential scope is needed (meaningful when `access` is `restricted`) |
| `sensitivity` | optional | Information classification: `public`, `internal`, `confidential`, `restricted` |
| `deprecated` | optional | If `true`, this unit is present but should not be used for new development |
| `delegation` | optional | Per-unit delegation override: `max_depth`, `require_capability_attenuation`, `audit_chain`, `human_in_the_loop` |
| `compliance` | optional | Per-unit compliance override: `data_residency`, `sensitivity`, `regulations`, `restrictions` |
| `payment` | optional | Monetisation tier: `default_tier: free \| metered \| subscription` |

### Minimum Viable KCP

Five fields per unit are enough to start:

```yaml
kcp_version: "0.7"
project: my-project
version: 1.0.0
units:
  - id: overview
    path: README.md
    intent: "What is this project and how do I get started?"
    scope: global
    audience: [human, agent]
```

The standard allows complexity but does not demand it.

---

## Complete Example

```yaml
# knowledge.yaml
kcp_version: "0.7"
project: wiki.example.org
version: 1.0.0
updated: "2026-02-28"
language: en
license: "Apache-2.0"
indexing: open

units:
  - id: about
    path: about.md
    intent: "Who maintains this project? Background, current work, contact."
    scope: global
    audience: [human, agent]
    validated: "2026-02-24"
    update_frequency: monthly

  - id: methodology
    path: methodology/overview.md
    intent: "What development methodology is used? Principles, evidence, adoption."
    format: markdown
    scope: global
    audience: [developer, architect, agent]
    depends_on: [about]
    validated: "2026-02-13"
    triggers: ["methodology", "productivity", "workflow"]

  - id: api-spec
    kind: schema
    path: api/openapi.yaml
    intent: "What endpoints does the API expose?"
    format: openapi
    scope: module
    audience: [developer, agent]
    validated: "2026-02-25"
    update_frequency: weekly

  - id: knowledge-infrastructure
    path: tools/knowledge-infra.md
    intent: "How is knowledge infrastructure set up? Architecture, indexing, deployment."
    scope: global
    audience: [developer, devops, agent]
    depends_on: [methodology]
    validated: "2026-02-28"
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
Add `depends_on`, `validated`, and `hints` (at minimum `token_estimate`, `load_strategy`, and
the `summary_available` / `summary_unit` / `summary_of` trio). Agents can now load documentation
in dependency order, check freshness before acting on it, and prefer short TL;DR files over full
documents when answering common questions.

**Level 3 — Enterprise documentation**
Use the full field set including `triggers`, `audience`, `relationships`, and advanced hints
(`priority`, `density`, chunking). Build knowledge-graph-navigable documentation that supports
multiple agent roles querying the same corpus with different task contexts and constrained
context budgets.

**Level 4 — Multi-agent systems**
Add `auth`, `delegation`, and `compliance` blocks. Pair with an A2A Agent Card
(`/.well-known/agent.json`) and link to KCP via the `knowledgeManifest` convention.
Enforce delegation depth, capability attenuation, HITL gates, and data residency
constraints across agent chains.

---

## Relationship to HATEOAS

KCP shares a foundational insight with HATEOAS (Hypermedia As The Engine Of Application State):
typed, directional relationships between resources are necessary for navigation — a flat list is
not enough. KCP's `depends_on`, `supersedes`, and `relationships` fields are the same idea as
HATEOAS link relations.

The key difference is static vs dynamic. HATEOAS links are generated per-response based on live
resource state. KCP is a committed file: it declares topology at authoring time without a server.
Where KCP goes further: the `intent` field (what question does this unit answer?), `validated`
(human-confirmed freshness, not just file modification time), and `audience` targeting — concerns
that arise specifically when the consumer is a context-window-constrained AI agent rather than an
API client.

See [SPEC.md §11](./SPEC.md#11-relationship-to-hateoas) for a full treatment.

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

**A2A** (Agent-to-Agent, Google) defines how agents discover and invoke each other via
`/.well-known/agent.json` Agent Cards. A2A is the transport/invocation layer (per-agent
granularity). KCP is the knowledge-access layer (per-unit granularity). They are complementary:
an Agent Card describes what an agent can do; a KCP manifest describes what it knows.
See [SPEC.md §12](./SPEC.md#12-relationship-to-a2a) and [examples/a2a-agent-card/](./examples/a2a-agent-card/).

**[kcp-commands](https://github.com/Cantara/kcp-commands)** is a KCP-native Claude Code hook that
applies the KCP principle at the Bash tool boundary. Each manifest is a `knowledge.yaml`-compatible
description of a CLI command: syntax hints injected before execution (Phase A), noise-filtered
output after execution (Phase B). 283 manifests bundled; unknown commands auto-generate manifests
from `--help` output. Measured saving: **67,352 tokens per session — 33.7% of a 200K context
window recovered**.

**[opencode-kcp-plugin](https://www.npmjs.com/package/opencode-kcp-plugin)** is a KCP-native
plugin for [OpenCode](https://github.com/anomalyco/opencode). It injects the `knowledge.yaml`
knowledge map into every session's system prompt and annotates glob/grep results with KCP intent
strings — reducing explore-agent tool calls by 73–80%. Install:
`npm install opencode-kcp-plugin` and add `"plugin": ["opencode-kcp-plugin"]` to `opencode.json`.
Source: [`plugins/opencode/`](./plugins/opencode/)

**[kcp-memory](https://github.com/Cantara/kcp-memory)** is the episodic memory layer for Claude
Code. Indexes `~/.claude/projects/**/*.jsonl` session transcripts and `~/.kcp/events.jsonl`
tool-call events into a local SQLite+FTS5 database. Three-layer memory model: working (context
window) → episodic (kcp-memory) → semantic (Synthesis). Runs as a daemon (port 7735), CLI, and
MCP server (6 tools: `kcp_memory_search`, `kcp_memory_events_search`, `kcp_memory_list`,
`kcp_memory_stats`, `kcp_memory_session_detail`, `kcp_memory_project_context`). PostToolUse hook
for near-real-time indexing. v0.4.0 — proactive session-start context via `PWD` detection.
Install: `curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-memory/main/bin/install.sh | bash`

---

## Blog series

The full design rationale, benchmarks, and adoption walkthroughs are documented at
[wiki.totto.org](https://wiki.totto.org) in the **Knowledge Context Protocol** series:

| Post | Key content |
|------|-------------|
| [Beyond llms.txt: AI Agents Need Maps, Not Tables of Contents](https://wiki.totto.org/blog/2026/02/25/beyond-llms-txt-knowledge-context-protocol/) | Why llms.txt has six structural limits; KCP proposal |
| [Add knowledge.yaml to Your Project in Five Minutes](https://wiki.totto.org/blog/2026/02/28/kcp-adoption-guide/) | Adoption gradient Level 1–3 with field reference |
| [KCP on Two Repos, Two Days](https://wiki.totto.org/blog/2026/03/01/kcp-two-repos-two-days/) | 74% + 53% tool-call reduction, benchmark methodology |
| [KCP on Three Agent Frameworks](https://wiki.totto.org/blog/2026/03/01/kcp-three-frameworks/) | AutoGen 80%, CrewAI 76%, smolagents 73% |
| [kcp-commands: Save 33% of Context Window](https://wiki.totto.org/blog/2026/03/02/kcp-commands/) | Phase A/B/C design, 283 manifests, 67K tokens saved |
| [KCP Comes to OpenCode](https://wiki.totto.org/blog/2026/03/03/opencode-kcp-plugin/) | opencode-kcp-plugin: system prompt injection + glob annotation |
| [kcp-memory: Give Claude Code a Memory](https://wiki.totto.org/blog/2026/03/03/kcp-memory/) | Three-layer memory model, MCP server, 6 tools |
| [The Front Door and the Filing Cabinet: A2A Agent Cards Meet KCP](https://wiki.totto.org/blog/2026/03/08/the-front-door-and-the-filing-cabinet-a2a-agent-cards-meet-kcp/) | A2A + KCP composability; 4 simulators, 150 adversarial tests; 8 spec gaps → v0.7 |

---

## Governance

KCP has been submitted to the **[Agentic AI Foundation](https://www.linuxfoundation.org/press/linux-foundation-announces-the-formation-of-the-agentic-ai-foundation)** (Linux Foundation, launched December 2025) for consideration as a neutral-governance project alongside MCP and AGENTS.md. The AAIF brings together 146 member organizations — including AWS, Anthropic, Google, Microsoft, and OpenAI — under neutral governance for agentic infrastructure standards.

Until formal acceptance, KCP remains an Apache 2.0 open specification proposed by [eXOReaction AS](https://www.exoreaction.com).

---

## Status

**Current:** Draft specification — v0.7

This is an early proposal. The format is intentionally minimal. Feedback, use cases, and pull
requests are welcome.

- **[SPEC.md](./SPEC.md)** — Normative specification (field definitions, validation rules, conformance levels)
- **[PROPOSAL.md](./PROPOSAL.md)** — The case for a knowledge architecture standard
- **[RFC-0001](./RFC-0001-KCP-Extended.md)** — Extended capabilities (overview of all proposals; F/H/I/J/K/L/N promoted to v0.3–v0.4 core)
- **[RFC-0002](./RFC-0002-Auth-and-Delegation.md)** — Auth and delegation metadata (`access`, `auth_scope`, `auth` promoted to core in v0.5–v0.6; `delegation` promoted to core in v0.7)
- **[RFC-0003](./RFC-0003-Federation.md)** — Cross-manifest federation proposal (`manifests` block, `external_depends_on`, hub-and-spoke model)
- **[RFC-0004](./RFC-0004-Trust-and-Compliance.md)** — Trust, provenance, and compliance metadata (`trust.provenance`, `sensitivity` promoted in v0.5; `trust.audit` promoted in v0.6; `compliance` promoted to core in v0.7)
- **[RFC-0005](./RFC-0005-Payment-and-Rate-Limits.md)** — Payment and rate-limit metadata proposal (`payment`, `rate_limits` blocks)
- **[RFC-0006](./RFC-0006-Context-Window-Hints.md)** — Context window hints (accepted; promoted to SPEC.md §4.10 in v0.4)
- **parsers/** — Reference implementations (Python, Java, TypeScript) — 401 tests passing
- **bridge/** — MCP servers: expose any `knowledge.yaml` as MCP resources (TypeScript · Python · Java)
- **plugins/opencode/** — OpenCode plugin (`opencode-kcp-plugin` on npm)
- **examples/** — Reference manifests at four adoption levels plus 4 simulation scenarios (150 adversarial tests: A2A+KCP clinical research, energy metering HITL, legal delegation chains, financial AML)
- **[kcp-memory](https://github.com/Cantara/kcp-memory)** — Episodic memory daemon for Claude Code (separate repo)

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

Apache V2.

*Proposed by [eXOReaction AS](https://www.exoreaction.com) — builders of Synthesis, based in Oslo, Norway.*
