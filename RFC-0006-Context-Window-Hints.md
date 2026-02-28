# RFC-0006: Context Window Hints

**Status:** Request for Comments
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-02-28
**Supersedes:** Proposal J in [RFC-0001](./RFC-0001-KCP-Extended.md)
**Issues:** [#9 (context window hints)](https://github.com/Cantara/knowledge-context-protocol/issues/9)
**Discussion:** [GitHub Issues](https://github.com/Cantara/knowledge-context-protocol/issues)
**Spec:** [SPEC.md](./SPEC.md) (current: v0.3)

---

## What This RFC Proposes

Two additions to the KCP manifest format:

1. **Unit-level `hints` block** — advisory metadata about the size, cost, and loading characteristics of a knowledge unit, so agents can make informed loading decisions before fetching content.
2. **Root-level `hints` block** — manifest-level aggregate hints that allow an agent to assess total context cost before loading any unit.

These fields are **advisory declarations**, not enforcement mechanisms. An agent that ignores them entirely still receives valid KCP responses. The value is in enabling agents with constrained context budgets to plan before they load.

---

## The Problem

### Context windows are finite and agents cannot see ahead

Every LLM-backed agent has a finite context window. Loading a 42,000-token specification when the remaining budget is 8,000 tokens will either fail, truncate, or force an expensive context-clearing operation. Today, agents discover this problem only after fetching the content — by which point the cost (latency, tokens, money) has already been incurred.

### No standard expresses unit size

KCP manifests currently describe *what* a unit is and *what it answers*, but not *how large it is*. Agents must choose between:

- Loading everything and hoping it fits (fails for large corpora)
- Loading nothing and asking the user what to load (defeats the purpose of a manifest)
- Making blind guesses based on path names (unreliable)

### Large documents have natural sub-units that are never exposed

A 40,000-token specification has chapters. A 200-page PDF has sections. A large codebase has modules. Agents that want only "the authentication chapter" must load the entire document to find it — unless the manifest exposes chunks explicitly.

### Summaries exist but are invisible to agents

Many knowledge bases maintain both a full document and an executive summary or TL;DR. Without manifest-level linking, an agent has no way to discover that a 600-token summary of a 42,000-token specification exists and answers the same question adequately for most contexts.

---

## Design

### Unit-level `hints` block

The `hints` block is optional on any unit. All sub-fields are optional.

```yaml
units:
  - id: full-specification
    path: SPEC.md
    intent: "What are the normative rules for a knowledge.yaml manifest?"
    scope: global
    audience: [human, agent, developer, architect]
    hints:
      token_estimate: 42000         # approximate tokens for a typical LLM tokenizer
      token_estimate_method: measured   # measured | estimated
      size_bytes: 168000            # raw file size (model-agnostic)
      load_strategy: lazy           # eager | lazy | never
      priority: supplementary       # critical | supplementary | reference
      density: dense                # dense | standard | verbose
      summary_available: true       # a shorter version of this unit exists in the manifest
      summary_unit: spec-summary    # id of the summary unit
      partial_load_supported: false # whether the source supports range/partial requests
```

#### Field definitions

| Field | Type | Description |
|-------|------|-------------|
| `token_estimate` | integer | Approximate token count for a typical LLM tokenizer. Advisory — actual count varies by model. |
| `token_estimate_method` | enum | How the estimate was produced: `measured` (actual tokenizer run) or `estimated` (rough word count / heuristic). Default: `estimated`. |
| `size_bytes` | integer | Raw file size in bytes. Model-agnostic; useful for non-text assets (PDFs, images, audio). |
| `load_strategy` | enum | Agent loading hint. See [Load strategy](#load-strategy) below. |
| `priority` | enum | Importance when context budget forces choices. See [Priority](#priority) below. |
| `density` | enum | Information density of the content. See [Density](#density) below. |
| `summary_available` | boolean | A shorter summary of this unit exists in this manifest. |
| `summary_unit` | string | Unit id of the summary. Required when `summary_available: true`. |
| `partial_load_supported` | boolean | The content source supports partial/range requests (e.g. HTTP Range, PDF page ranges). Default: `false`. |

---

#### Load strategy

`load_strategy` advises the agent on when to load a unit relative to manifest initialisation.

| Value | Meaning |
|-------|---------|
| `eager` | Load immediately when the manifest is processed. This unit is nearly always needed. |
| `lazy` | Load on demand, when the agent determines the unit is relevant to the current task. Default. |
| `never` | Do not load proactively. Only load if the user or orchestrator explicitly requests this unit by id. Typical for large archives, raw data dumps, or units the agent should summarise rather than load in full. |

`eager` is appropriate for short, high-signal units (an `about.md`, a schema index). `never` is appropriate for raw data files, large historical archives, or full PDF reports where the agent should access a summary instead.

---

#### Priority

`priority` advises the agent on which units to retain when context must be cleared or truncated.

| Value | Meaning |
|-------|---------|
| `critical` | Evict last. This unit contains essential facts the agent must retain to function correctly. |
| `supplementary` | Standard priority. Load and use normally; may be evicted if budget is tight. Default. |
| `reference` | Load on demand, evict first. Reference material (API specs, full changelogs, raw data) used for spot lookups rather than sustained reasoning. |

An agent managing its own context window SHOULD evict `reference` units before `supplementary` units, and `supplementary` before `critical`.

---

#### Density

`density` describes the information-to-token ratio of the content, helping agents decide whether to compress or summarise before placing in context.

| Value | Meaning |
|-------|---------|
| `dense` | Nearly every sentence is load-bearing. Compression risks information loss. Full text is preferred. |
| `standard` | Normal prose. Some compression acceptable. |
| `verbose` | High token count relative to information content (tutorials, narrative explanations, marketing copy). Summarisation before loading is likely worthwhile. |

---

### Summary and proxy relationships

When a short summary of a large unit exists in the same manifest, both sides of the relationship should be declared:

```yaml
units:
  - id: full-specification
    path: SPEC.md
    intent: "What are the normative rules for a knowledge.yaml manifest?"
    hints:
      token_estimate: 42000
      load_strategy: lazy
      priority: supplementary
      density: dense
      summary_available: true
      summary_unit: spec-summary      # → points to the summary

  - id: spec-summary
    path: SPEC-tldr.md
    intent: "What are the key points of the spec in 500 words?"
    hints:
      token_estimate: 600
      load_strategy: eager
      priority: critical
      summary_of: full-specification  # ← points back to the full unit
```

`summary_of` on the summary unit and `summary_unit` on the full unit are complementary. Both should be declared for consistency. A validator SHOULD warn when `summary_available: true` has no matching `summary_unit`, or when `summary_of` references a unit that does not declare `summary_available: true`.

---

### Chunked units

Large documents may be split into sequential chunks that can be loaded independently. The chunk relationship allows an agent to load only the relevant section of a large document.

```yaml
units:
  - id: spec
    path: SPEC.md
    intent: "What are the normative rules for a knowledge.yaml manifest?"
    hints:
      token_estimate: 42000
      load_strategy: lazy
      chunked: true
      chunk_count: 6              # total number of chunks available

  - id: spec-ch1-fields
    path: SPEC-ch1-fields.md
    intent: "What are the field definitions and types for knowledge.yaml units?"
    hints:
      token_estimate: 7200
      load_strategy: lazy
      priority: critical
      chunk_of: spec              # parent unit id
      chunk_index: 1              # 1-based position within the parent
      total_chunks: 6
      chunk_topic: "Field definitions and types"

  - id: spec-ch2-validation
    path: SPEC-ch2-validation.md
    intent: "What validation rules apply to a knowledge.yaml manifest?"
    hints:
      token_estimate: 5800
      load_strategy: lazy
      chunk_of: spec
      chunk_index: 2
      total_chunks: 6
      chunk_topic: "Validation rules and conformance"
```

#### Chunk fields

| Field | Type | Description |
|-------|------|-------------|
| `chunked` | boolean | This unit has been split into chunks. `chunk_count` should be declared alongside. |
| `chunk_count` | integer | Total number of chunks the parent is split into. |
| `chunk_of` | string | Unit id of the parent this chunk belongs to. |
| `chunk_index` | integer | 1-based position of this chunk within the parent sequence. |
| `total_chunks` | integer | Total chunks in the parent (mirrors `chunk_count` on the parent for local access). |
| `chunk_topic` | string | One short phrase describing what this chunk covers. Helps agents select the right chunk without loading all of them. |

`chunk_of` implies that the parent unit exists in the same manifest and has `chunked: true`. A validator SHOULD warn when `chunk_of` references a non-existent unit, or when `chunk_index` exceeds `total_chunks`.

---

### Root-level `hints` block

The root `hints` block provides manifest-level aggregate information that allows an agent to assess total context cost before loading any unit.

```yaml
kcp_version: "0.3"
project: large-enterprise-wiki
version: 2.1.0
updated: "2026-02-28"

hints:
  total_token_estimate: 840000    # sum of all unit token_estimates; advisory
  unit_count: 94                  # total units in this manifest
  recommended_entry_point: overview   # unit id to load first for new agents
  has_summaries: true             # at least one unit has summary_available: true
  has_chunks: true                # at least one unit has chunked: true

units:
  ...
```

#### Root hint fields

| Field | Type | Description |
|-------|------|-------------|
| `total_token_estimate` | integer | Advisory sum of all unit `token_estimate` values. Allows an agent to decide whether to engage with this manifest given its remaining budget. |
| `unit_count` | integer | Total number of units declared. Useful for manifests where the agent cannot receive the full unit list in one context load. |
| `recommended_entry_point` | string | Unit id the publisher recommends loading first. Typically the overview, index, or getting-started unit. |
| `has_summaries` | boolean | At least one unit in this manifest has `summary_available: true`. Signals to agents that lighter-weight alternatives exist. |
| `has_chunks` | boolean | At least one unit in this manifest has `chunked: true`. Signals that large documents have been split. |

`total_token_estimate` should be recomputed by tooling when unit estimates change. Publishers SHOULD NOT maintain it by hand.

---

## Complete Example

A large documentation corpus with a mix of sizes, strategies, and relationships:

```yaml
kcp_version: "0.3"
project: platform-docs
version: 3.0.0
updated: "2026-02-28"
language: en

hints:
  total_token_estimate: 128400
  unit_count: 8
  recommended_entry_point: overview
  has_summaries: true
  has_chunks: true

units:
  - id: overview
    path: overview.md
    intent: "What is the platform and how do I get started?"
    scope: global
    audience: [human, agent, developer]
    hints:
      token_estimate: 800
      load_strategy: eager
      priority: critical
      density: standard

  - id: architecture
    path: architecture.md
    intent: "What is the system architecture and how do the components relate?"
    scope: global
    audience: [architect, developer, agent]
    hints:
      token_estimate: 18000
      load_strategy: lazy
      priority: supplementary
      density: dense
      summary_available: true
      summary_unit: architecture-summary

  - id: architecture-summary
    path: architecture-tldr.md
    intent: "What are the key architectural decisions in 400 words?"
    scope: global
    audience: [architect, developer, agent]
    hints:
      token_estimate: 500
      load_strategy: eager
      priority: critical
      density: standard
      summary_of: architecture

  - id: api-reference
    path: api/reference.md
    intent: "What endpoints, parameters, and response schemas does the API expose?"
    scope: module
    audience: [developer, agent]
    hints:
      token_estimate: 62000
      load_strategy: never
      priority: reference
      density: dense
      chunked: true
      chunk_count: 5

  - id: api-ref-auth
    path: api/reference-auth.md
    intent: "What are the authentication endpoints and token schemas?"
    scope: module
    audience: [developer, agent]
    hints:
      token_estimate: 9400
      load_strategy: lazy
      priority: supplementary
      chunk_of: api-reference
      chunk_index: 1
      total_chunks: 5
      chunk_topic: "Authentication and token management"

  - id: api-ref-resources
    path: api/reference-resources.md
    intent: "What are the resource CRUD endpoints and their schemas?"
    scope: module
    audience: [developer, agent]
    hints:
      token_estimate: 18600
      load_strategy: lazy
      priority: supplementary
      chunk_of: api-reference
      chunk_index: 2
      total_chunks: 5
      chunk_topic: "Resource management endpoints"

  - id: changelog
    path: CHANGELOG.md
    intent: "What changed in recent releases?"
    scope: global
    audience: [developer, agent]
    hints:
      token_estimate: 22000
      load_strategy: never
      priority: reference
      density: verbose

  - id: migration-guide
    path: migration/v2-to-v3.md
    intent: "How do I migrate from platform v2 to v3?"
    scope: global
    audience: [developer, agent]
    hints:
      token_estimate: 14100
      load_strategy: lazy
      priority: supplementary
      density: standard
```

---

## Relationship to RFC-0005 (Rate Limits)

RFC-0005 `rate_limits.tokens` and RFC-0006 `hints.token_estimate` are complementary but describe different things:

| Field | Who it constrains | What it expresses |
|-------|------------------|-------------------|
| `rate_limits.tokens.default.tokens_per_minute` | Server-side | How many tokens the *publisher* will serve per minute |
| `hints.token_estimate` | Client-side | How many tokens a unit will *consume* in the *agent's* context window |

A publisher serving a 42,000-token document may have a `tokens_per_minute: 200000` rate limit (capacity is fine) while an agent with 16,000 tokens of remaining budget decides not to load it (client constraint). Both pieces of information are needed; neither replaces the other.

---

## Relationship to `depends_on` and `relationships`

Context hints describe *size and cost*. The `depends_on` and `relationships` fields describe *meaning and structure*. They are orthogonal:

- `depends_on: [overview]` says "understand the overview before reading this."
- `hints.chunk_of: api-reference` says "this is a section of the full API reference."
- `hints.summary_of: architecture` says "this is a shorter version of the architecture doc."

The structural relationships (`chunk_of`, `summary_of`) are hints-internal because they are primarily cost-planning metadata. If a consumer also wants to express them as formal graph relationships, they may mirror them in the `relationships` block with type `context` (summary) or `enables` (chunk introduces dependency ordering) — but this is not required.

---

## Conformance

| Feature | Level | Notes |
|---------|-------|-------|
| `hints.token_estimate` | Level 2 | Basic size signal on any unit |
| `hints.load_strategy` | Level 2 | `eager` / `lazy` / `never` on any unit |
| `hints.summary_available` + `summary_unit` | Level 2 | Summary relationship declared on full unit |
| `hints.summary_of` | Level 2 | Summary relationship declared on summary unit |
| `hints.priority` | Level 3 | Eviction hints for context management |
| `hints.density` | Level 3 | Compression advisory |
| `hints.chunk_of` / `chunk_index` | Level 3 | Chunked documents |
| `hints.chunked` / `chunk_count` | Level 3 | Chunked document declaration on parent |
| Root-level `hints` block | Level 3 | Manifest aggregate hints |

A unit that adds only `token_estimate` and `load_strategy` meets Level 2. Full chunking, priority management, and manifest-level aggregates are Level 3.

---

## Open Design Questions

**1. Token estimate staleness**

`token_estimate` is a snapshot that will drift as content changes. Should KCP define a `token_estimate_updated` date field, or is the unit's `validated` date sufficient as a freshness proxy for all `hints` fields?

**2. Tokenizer variance**

Token counts vary significantly across models: a 42,000-token document by GPT-4 tokenizer may be 38,000 tokens by Claude's tokenizer and 46,000 by Llama's. Should `token_estimate` be declared as model-agnostic (current proposal) or support a model-keyed map (`token_estimates: {cl100k_base: 42000, claude: 38500}`)? The map approach is precise but adds maintenance burden.

**3. `never` and agentic search**

A unit with `load_strategy: never` should not be loaded proactively, but should it appear in a manifest query result at all? If an agent searches the manifest for units matching a task context, should `never`-strategy units be excluded from results, or returned with a flag? The distinction matters for large archives where the agent should know the unit exists but not load it.

**4. Chunk ordering and navigation**

Chunks are defined by `chunk_index` (sequential position). Should KCP also support topic-based chunk selection — where an agent declares a topic and the publisher returns the matching chunk id — or is that a server-side query concern beyond the manifest format?

**5. Cross-manifest chunk references**

RFC-0003 federation allows `external_depends_on` across manifests. Should `chunk_of` be extended to support cross-manifest references (`chunk_of: {manifest: remote-docs, unit: api-reference}`) for federated large document sets? Or is chunking strictly intra-manifest?

**6. Manifest-level `total_token_estimate` maintenance**

`hints.total_token_estimate` at the root level is a derived aggregate that publishers must keep in sync manually (or via tooling). Should KCP mandate that `synthesis export --format kcp` and similar tools compute this automatically, or should it be left entirely advisory with no maintenance guidance? If a validator detects the declared total is more than 20% off from the sum of unit estimates, should it warn?
