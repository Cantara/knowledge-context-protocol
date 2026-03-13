# RFC-0007: Query Vocabulary

**Status:** Request for Comments
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-03-13
**Issues:** [#7 (query vocabulary)](https://github.com/Cantara/knowledge-context-protocol/issues)
**Discussion:** [GitHub Issues](https://github.com/Cantara/knowledge-context-protocol/issues)
**Spec:** [SPEC.md](./SPEC.md) (current: v0.10)

---

## What This RFC Proposes

A normative query vocabulary for **pre-invocation capability discovery** against KCP manifests.

KCP manifests are designed for agents to navigate knowledge without loading everything. But
navigation today requires the agent to parse the full manifest and apply its own filtering logic.
This RFC defines a standard query request/response shape so that orchestrators, tools, and agents
can ask: "which units match my needs?" — and get scored, budget-aware results.

This is the answer to **"why manifests over runtime guardrails?"** — because manifests enable
pre-invocation queries that guardrails cannot. An agent can ask before committing tokens.

---

## The Problem

### Agents load first, filter later

Today an agent receiving a KCP manifest must:

1. Parse all units.
2. Apply its own heuristics to decide which units are relevant.
3. Load units, discover they exceed the context budget, and backtrack.

This works for small manifests (10-20 units) but breaks down as manifests grow (100+ units,
federated graphs with 1,000+ units). The agent needs a way to **query** the manifest
efficiently before committing context window budget.

### No standard for discovery queries

Each tool that consumes KCP manifests implements its own filtering logic. There is no shared
vocabulary for expressing "I want units about authentication, for an agent audience, that fit
in 8,000 tokens." Without a standard, every consumer reinvents filtering.

### Budget-constrained selection is underdefined

The `hints.token_estimate` and `hints.summary_unit` fields exist (§4.10) but there is no
normative guidance on how to use them for budget-constrained selection. An agent with 8,000
tokens of context budget has no standard way to say "give me the best units that fit."

---

## Design

### Query request shape

A query is a structured object with the following fields. All fields are OPTIONAL — an empty
query matches all units.

```yaml
terms: ["authentication", "oauth2"]    # free-text search terms
audience: agent                         # filter: unit audience must include this value
scope: module                           # filter: unit scope must equal this value
sensitivity_max: internal               # ceiling on sensitivity (see ordering below)
max_token_budget: 8000                  # total token budget for results
include_summaries: true                 # prefer summary_unit when budget constrained
exclude_deprecated: true                # skip units with deprecated: true
```

#### Field definitions

| Field | Type | Description |
|-------|------|-------------|
| `terms` | `list[string]` | Free-text search terms. Matched against `triggers`, `intent`, `id`, and `path`. |
| `audience` | `string` | If present, only return units whose `audience` list includes this value. |
| `scope` | `string` | If present, only return units whose `scope` equals this value. |
| `sensitivity_max` | `string` | Maximum sensitivity level to include. Uses the ordering: `public` < `internal` < `confidential` < `restricted`. Units with sensitivity above this ceiling are excluded. Default: no ceiling. |
| `max_token_budget` | `integer` | Maximum total `hints.token_estimate` across all returned results. When set, the response SHOULD prefer `summary_unit` alternatives when available and the summary fits within the remaining budget. |
| `include_summaries` | `boolean` | When `true` and `max_token_budget` is set, the scorer SHOULD substitute `summary_unit` for units that exceed the remaining budget. Default: `true`. |
| `exclude_deprecated` | `boolean` | When `true`, units with `deprecated: true` are excluded from results. Default: `true`. |

### Query response shape

```yaml
results:
  - unit_id: auth-guide
    score: 13
    path: docs/api/authentication.md
    token_estimate: 4200
    summary_unit: auth-guide-tldr
    match_reason: [trigger, intent]
  - unit_id: api-overview
    score: 4
    path: docs/api/overview.md
    token_estimate: 2100
    summary_unit: null
    match_reason: [intent, id]
```

#### Result fields

| Field | Type | Description |
|-------|------|-------------|
| `unit_id` | `string` | The `id` of the matching unit. |
| `score` | `integer` | Relevance score. Higher is more relevant. |
| `path` | `string` | The unit's `path` field (convenience — avoids a second lookup). |
| `token_estimate` | `integer` or `null` | The unit's `hints.token_estimate`, if declared. |
| `summary_unit` | `string` or `null` | The unit's `hints.summary_unit`, if declared. |
| `match_reason` | `list[string]` | Which scoring rules contributed. Values: `trigger`, `intent`, `id`, `path`. |

### Scoring algorithm (normative SHOULD)

Implementations SHOULD use the following scoring rules. Alternative scoring algorithms are
permitted, but the default SHOULD produce equivalent ordering for identical inputs.

| Rule | Points | Condition |
|------|--------|-----------|
| Trigger match | 5 | Per search term that matches any entry in `triggers` (case-insensitive substring). |
| Intent substring match | 3 | Per search term that appears as a substring in `intent` (case-insensitive). |
| ID/path substring match | 1 | Per search term that appears as a substring in `id` or `path` (case-insensitive). |

**Sorting:** Results are sorted descending by `score`. Ties are broken by declaration order
in the manifest (earlier units first).

**Top-N:** Implementations SHOULD return the top 5 results by default. The limit MAY be
configurable.

**Budget-constrained selection:**

When `max_token_budget` is set:

1. Sort candidates by score (descending).
2. Walk the sorted list. For each unit:
   a. If the unit's `token_estimate` fits in the remaining budget, include it.
   b. If the unit has a `summary_unit` and `include_summaries` is true, check if the
      summary unit's `token_estimate` fits. If so, include the summary instead.
   c. Otherwise, skip the unit.
3. Stop when the budget is exhausted or all candidates have been considered.

Units without `hints.token_estimate` are treated as having an estimate of 0 (they are
always included, since their cost is unknown and presumed small).

---

## Interaction with Federation

When querying a federated manifest graph, the query is evaluated against the local manifest
only. Cross-manifest queries (querying units from `manifests[]` entries) require fetching and
parsing the remote manifest first. This RFC does not define cross-manifest query semantics.

Implementations MAY extend the query with a `manifest` field to scope the query to a specific
federated sub-manifest, but this is not part of the normative vocabulary.

---

## Interaction with Existing Fields

This RFC builds on existing KCP fields without modifying them:

- `triggers` (§4.9) — primary scoring signal
- `intent` (§4.5) — secondary scoring signal
- `audience` (§4.5) — filter
- `scope` (§4.5) — filter
- `sensitivity` (§4.12) — ceiling filter
- `deprecated` (§4.13) — exclusion filter
- `hints.token_estimate` (§4.10) — budget constraint
- `hints.summary_unit` (§4.10) — budget-aware substitution

No new manifest fields are introduced. The query vocabulary is a consumer-side convention,
not a manifest-side addition.

---

## Examples

### Example 1: Simple term search

```yaml
# Query
terms: ["authentication"]

# Response
results:
  - unit_id: auth-guide
    score: 5
    path: docs/api/authentication.md
    token_estimate: 4200
    summary_unit: auth-guide-tldr
    match_reason: [trigger]
```

### Example 2: Budget-constrained agent query

```yaml
# Query
terms: ["authentication", "oauth2"]
audience: agent
max_token_budget: 8000

# Response (auth-guide summary used because full unit is 4200 tokens)
results:
  - unit_id: auth-guide-tldr
    score: 13
    path: docs/api/auth-summary.md
    token_estimate: 800
    summary_unit: null
    match_reason: [trigger, intent]
  - unit_id: oauth2-config
    score: 8
    path: docs/api/oauth2.md
    token_estimate: 3200
    summary_unit: null
    match_reason: [trigger, intent]
```

### Example 3: Sensitivity-constrained query

```yaml
# Query
terms: ["deployment"]
sensitivity_max: internal

# Units with sensitivity: confidential or restricted are excluded
results:
  - unit_id: deploy-guide
    score: 5
    path: ops/deployment.md
    token_estimate: 2800
    summary_unit: null
    match_reason: [trigger]
```

---

## Security Considerations

- **Sensitivity ceiling:** The `sensitivity_max` filter is advisory. It relies on units
  having accurate `sensitivity` values. An agent MUST NOT treat query results as an
  access control decision — the query vocabulary is for navigation, not authorization.

- **Information disclosure:** Query responses include `path` and `token_estimate` fields
  for units the agent has not yet loaded. These fields are already present in the manifest
  and do not constitute additional information disclosure.

---

## Rationale

### Why not runtime guardrails?

Runtime guardrails (e.g., MCP tool-level filtering) operate after invocation. They cannot
help an agent decide *whether* to invoke. KCP manifests + query vocabulary enable
pre-invocation decisions: "I have 8,000 tokens of budget. Which units should I load?"

### Why a scoring algorithm?

Without normative scoring, every consumer would produce different rankings for the same query
on the same manifest. The scoring algorithm (trigger match > intent match > ID/path match)
reflects the information density of each field and produces intuitive results.

### Why advisory, not mandatory?

Consistent with KCP's philosophy (SPEC.md §12.1): all metadata is advisory. The query
vocabulary is a SHOULD, not a MUST. Implementations that use alternative scoring algorithms
are permitted as long as they process the same query fields.

---

## Relationship to Other RFCs

- **RFC-0006 (Context Window Hints):** The query vocabulary's budget-constrained selection
  depends on `hints.token_estimate` and `hints.summary_unit`, both promoted from RFC-0006.
- **RFC-0003 (Federation):** The query vocabulary operates on local manifests only.
  Cross-manifest query semantics are deferred.

---

*See also: [SPEC.md §4.9 Triggers](./SPEC.md) · [SPEC.md §4.10 Hints](./SPEC.md) · [RFC-0006 Context Window Hints](./RFC-0006-Context-Window-Hints.md)*
