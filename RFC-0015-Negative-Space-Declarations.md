# RFC-0015: Negative Space Declarations

**Status:** Request for Comments
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-03-26
**Discussion:** [GitHub Discussions](https://github.com/Cantara/knowledge-context-protocol/discussions)
**Related:** [RFC-0001 KCP Extended](./RFC-0001-KCP-Extended.md) · [RFC-0007 Query Vocabulary](./RFC-0007-Query-Vocabulary.md)
**Spec:** [SPEC.md](./SPEC.md) (current: v0.14)

---

## What This RFC Proposes

A `not_for` field on knowledge units that explicitly declares what questions or contexts
the unit does NOT address — a negative-space declaration that lets agents distinguish
"this unit cannot help you" from "this unit hasn't been searched yet."

---

## The Problem

### Agents can only optimize locally

A knowledge unit's `intent` and `triggers` describe what it answers. They cannot describe
what it does not answer. When an agent searches a manifest, it finds the best available
match — but "best available" is not the same as "correct."

Without a declared boundary, an agent cannot tell the difference between:
- "No unit in this manifest covers this question"
- "This unit is the closest match, but not actually applicable"

The result is confident hallucination dressed as retrieval.

### The completeness gap

A formal system cannot prove its own incompleteness from within. A knowledge unit
cannot signal its own inapplicability from within its own content — because its content
is exactly what the agent would retrieve if it matched. The signal must come from
*outside* the unit, in the manifest structure that wraps it.

`not_for` is that outside signal.

### Current workarounds are inadequate

Authors currently work around this by writing defensive prose inside documents
("This guide does not cover X") — but agents don't parse prose boundaries reliably.
Triggers are additive (what to match on) with no subtractive equivalent.

---

## Design

### Unit-level `not_for` field

```yaml
units:
  - id: api-security
    path: docs/security/api-auth.md
    intent: "How do we authenticate API clients?"
    not_for:
      - "Frontend authentication flows"
      - "End-user login and session management"
      - "Database access control"
      - "Service-to-service mTLS configuration"
```

`not_for` is a list of natural-language strings describing contexts or questions
this unit explicitly does NOT address.

### Strict exclusion opt-in

By default, `not_for` matches produce a **soft demotion** with annotation — the unit
remains in results but is flagged. When the author wants a hard guarantee, they can
opt into strict exclusion:

```yaml
units:
  - id: v1-migration-guide
    path: migration/v1-to-v2.md
    intent: "How do I migrate from KCP v1 to v2?"
    not_for:
      - "Initial KCP adoption (no prior version)"
      - "Migration from v2 to v3"
    not_for_strict: true   # hard-exclude this unit when a not_for phrase matches
```

When `not_for_strict: true`, a bridge MUST exclude the unit from results if any
`not_for` entry matches the query. Use with precise, unambiguous `not_for` phrases.

### Bridge behavior (search_knowledge)

When an agent queries `search_knowledge`, the bridge:

1. Scores units as today (trigger, intent, id/path matching)
2. For each scored unit, evaluates `not_for` entries against the query
3. Default (`not_for_strict` absent or false): if a `not_for` entry matches the query,
   the unit's score is **demoted** and the result is annotated:
   `{ unit: "api-security", caution: "not_for match: 'end-user login'" }`
4. Strict (`not_for_strict: true`): the unit is **excluded** from results entirely

### Manifest-level `not_for` (optional extension)

A manifest MAY declare `not_for` at the root level to signal the entire manifest's
scope boundary:

```yaml
kcp_version: "0.15"
project: "API Security Docs"
not_for:
  - "UI component documentation"
  - "Data pipeline architecture"
  - "Business process workflows"
```

This helps agents decide whether to federate to another manifest rather than searching
this one at all. Manifest-level `not_for` does not support `not_for_strict` (manifest
federation decisions are always advisory).

---

## Conformance

### Unit-level `not_for`
- Type: list of strings (natural language)
- OPTIONAL
- Parsers MUST read and expose the field
- Bridges SHOULD use `not_for` to demote or annotate results
- Bridges MUST NOT silently ignore `not_for` when scoring

### Unit-level `not_for_strict`
- Type: boolean
- OPTIONAL (default: false)
- Only meaningful when `not_for` is also present
- When true, bridges MUST exclude the unit from results on a `not_for` match
- Parsers MUST read and expose the field

### Manifest-level `not_for`
- Type: list of strings
- OPTIONAL
- Same parse/expose requirements; advisory only (no strict variant)

---

## Backward Compatibility

`not_for` and `not_for_strict` are additive. Existing manifests without them continue
to work unchanged. Parsers that do not support these fields MUST silently ignore them
(forward compatibility rule §13).

---

## Relationship to Existing Fields

| Field | Role | Additive / Subtractive |
|-------|------|------------------------|
| `intent` | What question the unit answers | Additive |
| `triggers` | Phrases that should route here | Additive |
| `audience` | Who the unit is for | Additive |
| `deprecated` | Unit is superseded | Neutral |
| **`not_for`** | **What should NOT route here** | **Subtractive** |

`not_for` is the first explicitly *subtractive* field in the spec. All prior fields
are additive (opt-in matching). This RFC introduces opt-out matching.

---

## Examples

### Example 1: Technical scoping

```yaml
- id: gdpr-data-retention
  path: legal/gdpr-retention.md
  intent: "What are our data retention obligations under GDPR?"
  not_for:
    - "Cookie consent management"
    - "Data subject access requests"
    - "Cross-border transfer rules"
```

### Example 2: Audience scoping

```yaml
- id: architecture-decision-record
  path: adr/adr-003-event-sourcing.md
  intent: "Why did we choose event sourcing for order processing?"
  not_for:
    - "How to implement event sourcing (see implementation guide)"
    - "Operational runbook for event store"
    - "Frontend state management patterns"
```

### Example 3: Strict temporal scoping

```yaml
- id: v1-migration-guide
  path: migration/v1-to-v2.md
  intent: "How do I migrate from KCP v1 to v2?"
  not_for:
    - "Initial KCP adoption (no prior version)"
    - "Migration from v2 to v3"
    - "Rollback procedures"
  not_for_strict: true
```

### Example 4: Manifest-level scope boundary

```yaml
kcp_version: "0.15"
project: "Payments Service Docs"
not_for:
  - "Identity and authentication"
  - "Notification delivery"
  - "Reporting and analytics"

units:
  - id: payment-flows
    ...
```

---

## Open Questions

1. **Scoring weight for soft demotion:** What numeric demotion factor should bridges
   apply? A `not_for` match that is weaker than the `intent` match should demote less
   than one that is stronger. Recommend: demotion factor is implementation-defined in
   v0.15; a reference weighting may be added in a later RFC once implementations report
   back.

2. **`not_for` on relationships:** Could a `relationship` entry declare that a
   `governs` link does NOT apply in certain contexts? Out of scope for this RFC.

3. **Typed vs natural language:** Should `not_for` eventually accept structured values
   (domain tags, audience types)? Free text is easier to author. Defer to implementation
   feedback — this RFC intentionally keeps `not_for` as natural language to stay
   consistent with `intent` and `triggers`.
