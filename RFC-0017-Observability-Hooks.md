# RFC-0017: Observability Hooks

**Status:** Accepted — promoted to [SPEC.md](./SPEC.md) §17 (v0.16). v0.16 also adds the `render_events` and `quarantine_events` tables (RFC-0018 §8) alongside `usage_events`.
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-03-27
**Discussion:** [GitHub Discussions](https://github.com/Cantara/knowledge-context-protocol/discussions)
**Related:** [RFC-0006 Context Window Hints](./RFC-0006-Context-Window-Hints.md) · [RFC-0007 Query Vocabulary](./RFC-0007-Query-Vocabulary.md) · [RFC-0016 Content Structure Declaration](./RFC-0016-Content-Structure-Declaration.md)
**Spec:** [SPEC.md](./SPEC.md) (current: v0.14)

---

## What This RFC Proposes

A local-first observability convention for KCP bridges. Bridges that serve
knowledge units MAY record usage events to a well-known SQLite database at
`~/.kcp/usage.db`. A companion CLI command (`kcp stats`) reads this database
and reports aggregate usage: queries served, units fetched, tokens saved,
and top-accessed units.

---

## The Problem

### KCP users cannot prove KCP is working

A developer installs kcp-mcp, configures a `knowledge.yaml`, and starts using
it with an agent. After a week they cannot answer: "How many queries did KCP
serve? Which units are actually used? How many tokens did I save versus
injecting everything into context?"

### Adoption requires proof of value

To justify continued investment — in enterprise settings especially — teams
need data: query volume, token savings, and usage distribution. Without it,
KCP is a black box that is trusted to work but cannot demonstrate that it
does.

### Current state: zero observability

Today, every `search_knowledge` and `get_unit` call is fire-and-forget. The
bridge processes the request, returns the result, and leaves no trace.

---

## Design

### Event schema

Each usage event is a row in the `usage_events` table:

| Column | Type | Required | Description |
|--------|------|----------|-------------|
| `id` | INTEGER | yes | Auto-increment row id |
| `timestamp` | TEXT | yes | ISO 8601 UTC (e.g. `2026-03-27T14:30:00Z`) |
| `event_type` | TEXT | yes | One of: `search`, `get_unit`, `inject`, `skill_selected`, `plan_formed`, `conclusion`, `action_trace`, `verdict_emitted` |
| `project` | TEXT | yes | Manifest `project` field value (or basename of working directory) |
| `query` | TEXT | no | The search query string (`search` events only) |
| `unit_id` | TEXT | no | The unit id fetched (`get_unit`), manifest key injected (`inject`), or `kind: skill` unit selected/enacted (lifecycle events) |
| `result_count` | INTEGER | no | Number of results returned (`search` events only) |
| `token_estimate` | INTEGER | no | Token estimate of the fetched/injected content (chars / 4) |
| `manifest_token_total` | INTEGER | no | Sum of `hints.token_estimate` across all units in the manifest |
| `correlation_id` | TEXT | no | W3C Trace Context `traceparent` (v0.26) stitching the events of one procedural run into a single trace. Reuses `trust.audit.require_trace_context` (SPEC §3.2). |
| `session_id` | TEXT | no | Opaque session identifier, if available |

### DDL

```sql
CREATE TABLE IF NOT EXISTS usage_events (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp            TEXT    NOT NULL,
    event_type           TEXT    NOT NULL,
    project              TEXT    NOT NULL,
    query                TEXT,
    unit_id              TEXT,
    result_count         INTEGER,
    token_estimate       INTEGER,
    manifest_token_total INTEGER,
    correlation_id       TEXT,
    session_id           TEXT
);

CREATE INDEX IF NOT EXISTS idx_usage_timestamp      ON usage_events(timestamp);
CREATE INDEX IF NOT EXISTS idx_usage_project        ON usage_events(project);
CREATE INDEX IF NOT EXISTS idx_usage_unit_id        ON usage_events(unit_id);
CREATE INDEX IF NOT EXISTS idx_usage_correlation_id ON usage_events(correlation_id);
```

### Storage convention

- **Default path:** `~/.kcp/usage.db`
- **Override:** Via `KCP_USAGE_DB` environment variable or bridge flag
- **Format:** SQLite 3, WAL journal mode (allows concurrent readers)
- **Writers:** KCP bridge implementations (kcp-mcp: `search`/`get_unit`; kcp-commands: `inject`)
- **Readers:** `kcp stats` CLI, `kcp_memory_stats` MCP tool, `kcp-dashboard`, user tooling

### Event type semantics

| Event type | Emitted by | Trigger |
|------------|-----------|---------|
| `search` | kcp-mcp bridge | Agent calls `search_knowledge` tool |
| `get_unit` | kcp-mcp bridge | Agent calls `get_unit` tool |
| `inject` | kcp-commands daemon | Manifest hit served to Claude Code PreToolUse hook |
| `skill_selected` | procedural runtime | A `kind: skill` unit (SPEC §4.3a) is chosen to enact a task |
| `plan_formed` | procedural runtime | The agent commits to a plan of steps for the selected skill |
| `conclusion` | procedural runtime | An intermediate finding or decision reached while enacting the plan |
| `action_trace` | procedural runtime | A single governed action taken inside the skill's `action_scope` |
| `verdict_emitted` | procedural runtime | The terminal outcome of the run (success/failure/abstain) |

For `inject` events: `unit_id` is the resolved manifest key (e.g. `git`, `git-log`, `docker-run`),
`token_estimate` is `max(1, contextLength / 4)` where `contextLength` is the character length of the
injected `additionalContext` string, and `project` is the basename of the working directory (`cwd`).

### Runtime-depth lifecycle events (v0.26)

The five lifecycle events — `skill_selected`, `plan_formed`, `conclusion`, `action_trace`,
`verdict_emitted` — record the *procedural plane*: what an agent did after a `kind: skill` unit
(SPEC §4.3a) was selected, not merely which knowledge it read. For these events `unit_id` is the
skill's unit id, and all events of one procedural run share a single `correlation_id`.

The `correlation_id` carries a [W3C Trace Context](https://www.w3.org/TR/trace-context/)
`traceparent` value, so a run's events can be reconstructed in order and correlated with the
HTTP access chain that `trust.audit.require_trace_context` (SPEC §3.2) governs. When a skill is
enacted over local file access rather than HTTP, the runtime SHOULD still generate a
conforming `traceparent` and record it here — mirroring the local-access guidance in §3.2 — so
every run is traceable regardless of transport. `correlation_id` is OPTIONAL on the pre-existing
`search`/`get_unit`/`inject` events and is not required for KCP conformance.

These events are subject to the same **local-only** posture as all rows in `usage_events`:
implementations MUST NOT transmit them to external services without explicit user consent
(see *Privacy note* below).

### `tokens_saved` calculation

For a `get_unit` event where both `manifest_token_total` and `token_estimate`
are present:

```
tokens_saved = manifest_token_total - token_estimate
```

This represents the tokens the agent did NOT load because KCP routed it to
one unit instead of injecting the entire knowledge base into context.

When `hints.token_estimate` is absent on the fetched unit, `token_estimate`
is null and the event does not contribute to savings calculations. Adding
`hints.token_estimate` to all units in `knowledge.yaml` unlocks this metric.

### Privacy note

All data is local-only. No telemetry leaves the machine. Query strings and
unit ids are the same strings already visible in the user's MCP tool-call
logs. Implementations MUST NOT transmit usage events to external services
without explicit user consent.

---

## Bridge behavior

Bridges implementing usage logging:

- MUST use the schema defined above
- MUST NOT block tool responses on logging I/O — logging SHOULD be async
- SHOULD enable WAL journal mode: `PRAGMA journal_mode=WAL`
- SHOULD compute `manifest_token_total` once at startup, not per-call
- SHOULD set `PRAGMA user_version = 1` to support future schema migrations

---

## `kcp stats` CLI command

The reference consumer of the usage database. Part of the `kcp` npm CLI
package (`npx kcp stats`).

### Output

```
KCP Usage Statistics (last 30 days)

  Queries served:  142
  Units fetched:   387
  Tokens saved:    2.3M

Top Units
  architecture-overview          67 fetches (412k tokens saved)
  api-reference                  52 fetches (318k tokens saved)
  deployment-guide               41 fetches (251k tokens saved)

Top Queries
   23x "how do I deploy?"
   18x "authentication setup"
   12x "error handling patterns"
```

### Flags

- `--days N` — reporting window (default: 30)
- `--json` — machine-readable JSON output
- `--project <name>` — filter to a specific project

---

## Conformance

- Bridges MAY implement usage logging — it is not required for KCP conformance
- Bridges that implement usage logging MUST use the schema above
- The `~/.kcp/usage.db` path is the RECOMMENDED default; bridges MAY offer override
- `kcp stats` MUST handle a missing or empty database gracefully (no crash, helpful message)

---

## Backward Compatibility

This RFC is purely additive. Bridges that do not implement usage logging
continue to work unchanged. The `kcp stats` command reports "no data" when
the database does not exist.

---

## Relationship to Existing Fields

| Field | What it enables |
|-------|----------------|
| `hints.token_estimate` | Per-unit token savings calculation |
| `project` (root) | Multi-project dashboard filtering |
| `id` (unit) | Per-unit access frequency |
| `discovery.source` | (future) correlate high-traffic units with their discovery origin |

---

## Open Questions

1. **Schema migrations.** `PRAGMA user_version = 1` is set in v1. If columns
   are added in a future RFC, a migration path will be needed. Defer to RFC-0018
   or an amendment.

2. **Team aggregation.** Shipping events to a shared store for per-team
   dashboards is explicitly out of scope for this RFC. A future RFC could
   define an opt-in remote sync protocol.

3. **Cost model.** Token price varies by model and tier. This RFC does not
   define how to calculate cost from tokens saved — that is left to the
   consuming tool (the CLI can accept a `--cost-per-mtok` flag).

4. **`search` suppression events.** Units that matched a query but were
   excluded by priority/filtering are not currently tracked. A future event
   type `suppressed` could capture this, enabling a "units never shown"
   report.
