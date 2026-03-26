# RFC-0016: Content Structure Declaration

**Status:** Request for Comments
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-03-26
**Discussion:** [GitHub Discussions](https://github.com/Cantara/knowledge-context-protocol/discussions)
**Related:** [RFC-0001 KCP Extended](./RFC-0001-KCP-Extended.md) · [RFC-0006 Context Window Hints](./RFC-0006-Context-Window-Hints.md) · [RFC-0012 Capability Discovery Provenance](./RFC-0012-Capability-Discovery-Provenance.md)
**Spec:** [SPEC.md](./SPEC.md) (current: v0.14)

---

## What This RFC Proposes

A `content_structure` block on knowledge units that declares the internal structure of
a document — what content modalities it contains and in what proportion. This lets
retrieval agents and RAG pipelines route queries to the right units before reading them,
and choose the right extraction strategy before loading a file.

---

## The Problem

### Retrieval agents guess at document structure

A knowledge unit declares `path: docs/compliance-matrix.md` and
`intent: "What are our NIS2 compliance control mappings?"`. The agent fetches it and
discovers it is 80% a dense table with short cells — not prose. A semantic embedding
model trained on prose will produce a low-quality vector for this content.

The agent had no way to know before fetching. It chose the wrong retrieval strategy
because the manifest gave no structural signal.

### `format` and `content_type` describe encoding, not structure

`format: markdown` tells a reader the file is Markdown. It does not say whether the
Markdown is prose, a table, a code listing, a diagram, or a mix. A PDF may contain
prose, scanned images, data tables, or all three. `content_type: text/markdown` is
equally silent on structure.

### RAG routing is done heuristically or not at all

Teams building RAG pipelines over KCP-indexed knowledge currently have two choices:
1. Fetch every unit and inspect it before deciding which extractor to use (expensive)
2. Apply one extraction strategy to everything and accept degraded precision (wasteful)

Neither option uses the manifest as the routing layer it is designed to be.

---

## Design

### Unit-level `content_structure` block

```yaml
units:
  - id: compliance-matrix
    path: docs/compliance-matrix.md
    intent: "What are our NIS2 compliance control mappings?"
    content_structure:
      primary: table
      contains: [table, prose]
      density: dense
```

### Fields

#### `primary` (string, OPTIONAL)
The dominant content modality. One of:
- `prose` — running text, paragraphs, narrative
- `table` — structured rows/columns (markdown tables, HTML tables, CSV-like)
- `code` — source code, shell commands, configuration snippets
- `list` — enumerated or bulleted items without dense prose
- `diagram` — visual structure (ASCII art, Mermaid, PlantUML, embedded images)
- `mixed` — no single modality dominates (default when `contains` has 2+ equal modalities)
- `reference` — lookup content: glossaries, API parameter lists, field definitions

#### `contains` (list of strings, OPTIONAL)
All modalities present in the unit, including minor ones. Uses the same vocabulary
as `primary`. Agents MAY use this to select an extractor that handles multi-modal content.

#### `density` (string, OPTIONAL)
How information-dense the content is relative to its length:
- `sparse` — long document, low information per token (e.g. narrative tutorial)
- `normal` — typical documentation density
- `dense` — high information per token (e.g. API reference, data table, config file)

This maps directly to retrieval cost: a `dense` unit yields more signal per fetched
token than a `sparse` one.

---

## Bridge behavior

When `content_structure` is declared, the `search_knowledge` tool SHOULD:

1. Include `content_structure.primary` and `content_structure.density` in the result
   payload so the calling agent can choose its extraction strategy
2. Use `density` as a tiebreaker when two units have equal score — prefer `dense` for
   factual queries, `prose` for conceptual queries

When the query contains terms like "show me the table", "list of", "code example",
"diagram", the bridge SHOULD boost units whose `content_structure.primary` matches
the implied modality.

---

## Conformance

`content_structure` block:
- OPTIONAL at the unit level
- Parsers MUST read and expose all subfields
- Bridges SHOULD use `primary` and `density` in result payloads
- Bridges MAY use `primary` to boost modality-matched queries

`primary` and `contains` values:
- MUST be one of the defined vocabulary values
- Parsers SHOULD warn on unknown values (forward compatibility: unknown values MUST be
  passed through, not rejected)

---

## Backward Compatibility

`content_structure` is additive. Existing manifests without it continue to work
unchanged. The block has no effect on parsers that do not support it.

---

## Relationship to Existing Fields

| Field | What it declares |
|-------|-----------------|
| `format` | File encoding (`markdown`, `pdf`, `openapi`, ...) |
| `content_type` | MIME type |
| `kind` | Semantic role (`knowledge`, `schema`, `policy`, `executable`, ...) |
| `hints.token_estimate` | Approximate size |
| **`content_structure`** | **Internal modality composition and density** |

`content_structure` complements `kind`: a unit may have `kind: reference` and
`content_structure.primary: table` — both signals are useful and non-redundant.

---

## Examples

### Example 1: Dense API reference table

```yaml
- id: api-error-codes
  path: docs/api/error-codes.md
  intent: "What does each API error code mean?"
  kind: reference
  content_structure:
    primary: table
    contains: [table, prose]
    density: dense
```

### Example 2: Narrative tutorial

```yaml
- id: getting-started
  path: docs/guides/getting-started.md
  intent: "How do I set up the project from scratch?"
  content_structure:
    primary: prose
    contains: [prose, code, list]
    density: sparse
```

### Example 3: Architecture diagram with explanation

```yaml
- id: system-overview
  path: docs/architecture/overview.md
  intent: "What is the high-level system architecture?"
  content_structure:
    primary: mixed
    contains: [diagram, prose, table]
    density: normal
```

### Example 4: Pure code reference

```yaml
- id: config-reference
  path: docs/configuration.yaml
  intent: "What configuration options are available?"
  kind: schema
  content_structure:
    primary: code
    density: dense
```

---

## Open Questions

1. **Automated inference:** Could a preprocessing tool scan unit content and populate
   `content_structure` automatically? Yes — this is a natural target for `kcp init`
   in a future version. The field is intentionally simple enough for heuristic detection
   (table row count vs word count ratio, code fence detection, etc.).

2. **Image-heavy documents:** PDFs with significant image content have no good primary
   value today. A future addition could be `image` as a modality, but this requires
   vision-capable extractors. Defer to RFC-0017 or an amendment.

3. **Per-section declaration:** A very long document may have structurally distinct
   sections. This RFC declares structure at the unit level only. Section-level
   declaration would require a more complex model — defer.
