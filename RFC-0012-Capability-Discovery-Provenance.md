# RFC-0012: Capability Discovery Provenance

**Status:** Request for Comments
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-03-17
**Discussion:** [#50 KCP Treasure Map Service](https://github.com/Cantara/knowledge-context-protocol/discussions/50)
**Related:** [RFC-0010 Bi-Temporal Unit Validity](./RFC-0010-Bi-Temporal-Unit-Validity.md) · [RFC-0004 Trust and Compliance](./RFC-0004-Trust-and-Compliance.md) · [RFC-0011 Org Federation](./RFC-0011-Org-Federation.md)
**Spec:** [SPEC.md](./SPEC.md) (current: v0.11)

---

## What This RFC Proposes

A `discovery` block on KCP units that records how, when, and how confidently a capability
was identified — enabling automated tooling that generates KCP manifests from web traversal,
OpenAPI introspection, or LLM inference to express the epistemic state of each declared unit.

The `discovery` block answers: *"How do we know this unit is real, and how sure are we?"*

All fields are optional. All additions are backward compatible. A hand-authored manifest
requires no changes.

---

## The Problem

### Automated discovery produces manifests of uneven certainty

Tooling that generates KCP manifests from external observations — Playwright-based web
traversal, OpenAPI document parsing, LLM inference from marketing pages — produces
capabilities with very different confidence levels:

- An endpoint observed by crawling a live service and confirmed against its OpenAPI spec
  is a verified fact.
- An endpoint inferred from a Playwright screenshot but not yet confirmed against a live
  API call is a plausible observation.
- A capability mentioned in a sales page but not yet found in any technical surface is
  a rumor.

Today there is no way to express this distinction in a KCP manifest. All three look identical
to a consuming agent. The agent has no signal to decide whether to trust a unit, hold it
pending confirmation, or treat it as advisory-only.

### Discovery tools need a write path, not just a read path

The KCP ecosystem is acquiring tooling that generates manifests, not just reads them:
`synthesis export --format kcp`, Playwright-based service crawlers, OpenAPI-to-KCP
converters. These tools are the primary authors of manifests for external services —
services that have no KCP author in the traditional sense.

When a tool observes a capability rather than a human declaring it, the manifest should say
so. The confidence and verification status of the observation is itself useful knowledge.

### Contradictions emerge over time

A service crawled today may return different results next week. Two crawl passes may
produce conflicting capability descriptions. Without a mechanism to record contradictions
and their timestamps, the manifest cannot represent the ambiguity — it silently overwrites
the older observation.

---

## Design

### The `discovery` block

A `discovery` block MAY be added to any unit. It describes the provenance of that unit's
declaration — how the capability was found, when, and how confidently.

```yaml
units:
  - id: submit-expense-report
    path: capabilities/expense-submit.md
    intent: "How do I submit an expense report via the HR portal?"
    scope: project
    audience: [agent]

    discovery:
      verification_status: observed
      source: web_traversal
      observed_at: "2026-03-10T14:22:00Z"
      verified_at: null
      confidence: 0.72
      contradicted_by: null
```

### `discovery` field reference

| Field | Required | Type | Default | Description |
|-------|----------|------|---------|-------------|
| `discovery.verification_status` | OPTIONAL | enum | `verified` | The current verification state of this capability declaration. See vocabulary below. |
| `discovery.source` | OPTIONAL | enum | `manual` | How this unit was discovered. See vocabulary below. |
| `discovery.observed_at` | OPTIONAL | ISO 8601 datetime | null | When this capability was first observed or inferred. |
| `discovery.verified_at` | OPTIONAL | ISO 8601 datetime | null | When this capability was independently confirmed. MUST be null if `verification_status` is `rumored` or `observed`. |
| `discovery.confidence` | OPTIONAL | float 0.0–1.0 | 1.0 | Confidence in this capability declaration. |
| `discovery.contradicted_by` | OPTIONAL | string | null | The `id` of another unit in this manifest that provides a conflicting description of the same capability. |

---

### `verification_status` vocabulary

| Value | Meaning |
|-------|---------|
| `rumored` | Capability mentioned in an indirect source (marketing copy, third-party description, LLM inference from non-technical content). Not yet observed directly. |
| `observed` | Capability found via direct technical observation (web traversal, API call, screenshot analysis) but not yet cross-confirmed against a canonical source. |
| `verified` | Capability confirmed against a canonical source (OpenAPI spec, official documentation, successful live API call). |
| `deprecated` | Capability was previously `verified` or `observed` but is no longer present or functional. Retained for audit and historical reference. |

**Normative rules:**

- A unit with `verification_status: rumored` MUST declare `confidence < 0.5`.
- A unit with `verification_status: verified` SHOULD declare `confidence >= 0.8`.
- A unit with `verification_status: deprecated` SHOULD NOT be loaded by agents for live
  operation. It MAY be loaded by audit tooling.
- If `verification_status` is absent, agents MUST treat the unit as `verified` — this
  preserves the semantics of all existing hand-authored manifests.

---

### `source` vocabulary

| Value | Meaning |
|-------|---------|
| `manual` | Declared by a human author with direct knowledge of the capability. Default. |
| `web_traversal` | Discovered by automated web or UI traversal (e.g. Playwright, headless browser). |
| `openapi` | Derived from an OpenAPI, AsyncAPI, or equivalent machine-readable API description. |
| `llm_inference` | Inferred by an LLM from natural-language content (documentation, marketing pages, support text). |

`source` values are non-normative beyond the four named values. Implementations MAY use
additional source identifiers. Unknown values MUST be silently ignored.

---

### `contradicted_by`

When two observations of the same capability disagree, both units SHOULD be preserved with
`contradicted_by` pointing at each other. The consuming agent or serving layer can then
surface the conflict rather than silently preferring one version.

```yaml
units:
  - id: expense-api-v2
    intent: "Submit expense report via REST API at /api/v2/expenses"
    discovery:
      verification_status: observed
      source: web_traversal
      observed_at: "2026-03-10T14:22:00Z"
      confidence: 0.71
      contradicted_by: expense-api-v1

  - id: expense-api-v1
    intent: "Submit expense report via REST API at /api/v1/expenses"
    discovery:
      verification_status: observed
      source: openapi
      observed_at: "2026-03-08T09:00:00Z"
      confidence: 0.85
      contradicted_by: expense-api-v2
```

A serving layer encountering a `contradicted_by` reference SHOULD surface a
`discovery_conflict` warning alongside both units. It MUST NOT silently discard either unit.

---

## Relationship to RFC-0010 (Bi-Temporal Unit Validity)

RFC-0010 tracks *when knowledge was valid in the world* (valid time) and *when the manifest
recorded a version* (transaction time). RFC-0012 tracks *how the knowledge was acquired and
how confident we are in it*. The two blocks are complementary and may be declared together:

```yaml
units:
  - id: submit-expense-report
    intent: "How do I submit an expense report via the HR portal?"

    temporal:
      observed_at: "2026-03-10"       # RFC-0010: when this version was recorded
      valid_from: "2026-03-10"
      valid_until: null

    discovery:
      verification_status: observed   # RFC-0012: how we found it
      source: web_traversal
      observed_at: "2026-03-10T14:22:00Z"
      confidence: 0.72
```

| Field | Question answered |
|-------|-----------------|
| `temporal.recorded_at` (RFC-0010) | When did the manifest author write this version down? |
| `discovery.observed_at` (RFC-0012) | When was the capability first encountered by the discovery tool? |

For hand-authored manifests these are the same moment. For automated discovery they may
diverge: a crawler may observe a capability on Monday and write it to the manifest on
Thursday after a review step.

---

## Root-level `discovery` defaults

A root-level `discovery` block MAY declare manifest-wide defaults applied to all units
that do not declare their own `discovery` block:

```yaml
kcp_version: "0.12"
project: hr-portal-discovered

discovery:
  source: web_traversal
  verification_status: observed
  observed_at: "2026-03-10T14:00:00Z"

units:
  - id: submit-expense-report
    intent: "Submit expense report"
    discovery:
      confidence: 0.72          # inherits source, verification_status, observed_at from root

  - id: view-payslip
    intent: "View payslip"
    discovery:
      confidence: 0.91
      verification_status: verified   # overrides root default
      verified_at: "2026-03-11T08:30:00Z"
```

Unit-level `discovery` blocks override root defaults field-by-field.

---

## Complete Example: Automated Manifest from Web Traversal

```yaml
kcp_version: "0.12"
project: acme-hr-portal-discovered
version: 0.1.0
updated: "2026-03-10"

trust:
  provenance:
    publisher: "Automated Discovery Pipeline"
    contact: "platform-team@acme.no"

discovery:
  source: web_traversal
  verification_status: observed
  observed_at: "2026-03-10T14:00:00Z"

rate_limits:
  default:
    requests_per_minute: 10
    requests_per_day: 200

units:
  - id: login
    path: capabilities/login.md
    intent: "How do I authenticate to the ACME HR portal?"
    scope: project
    audience: [agent]
    compliance:
      sensitivity: internal
    discovery:
      confidence: 0.95
      verification_status: verified
      verified_at: "2026-03-10T14:05:00Z"

  - id: submit-expense-report
    path: capabilities/expense-submit.md
    intent: "How do I submit an expense report?"
    scope: project
    audience: [agent]
    compliance:
      sensitivity: internal
    discovery:
      confidence: 0.72

  - id: bulk-export-salaries
    path: capabilities/salary-export.md
    intent: "Export salary data in bulk"
    scope: project
    audience: [agent]
    compliance:
      sensitivity: confidential
    delegation:
      human_in_the_loop:
        required: true
        approval_mechanism: oauth_consent
    discovery:
      verification_status: rumored
      source: llm_inference
      confidence: 0.31

  - id: submit-expense-report-legacy
    intent: "Submit expense report via legacy /api/v1/expenses endpoint"
    discovery:
      verification_status: deprecated
      source: openapi
      observed_at: "2026-02-01T00:00:00Z"
      confidence: 0.0
      contradicted_by: submit-expense-report
```

---

## Conformance

| Feature | Level | Notes |
|---------|-------|-------|
| `discovery.verification_status` on unit | Level 2 | Agent filters or warns on `rumored` / `deprecated` |
| `discovery.source` on unit | Level 2 | Informational |
| `discovery.observed_at` / `verified_at` on unit | Level 2 | Informational |
| `discovery.confidence` on unit | Level 2 | Agent MAY use to rank units; MUST warn if rumored but confidence >= 0.5 |
| `discovery.contradicted_by` | Level 2 | Serving layer SHOULD surface conflict warning |
| Root-level `discovery` defaults | Level 2 | Field-level inheritance to units |

---

## Backward Compatibility

| Addition | v0.11 parser behaviour | Risk |
|----------|------------------------|------|
| `discovery` block on unit | Silently ignored per SPEC.md §2 | None |
| `discovery` block at root | Silently ignored per SPEC.md §2 | None |
| `kcp_version: "0.12"` | "Unknown version" warning per §6.1 | Warning only |

Existing manifests with no `discovery` block are treated as fully `verified` / `manual`.
No existing manifest requires changes.

---

## Open Questions

**1. Should `confidence` be required when `source != manual`?**

Making `confidence` mandatory for discovery-generated manifests would make the epistemic
state explicit. The cost is mandatory author burden for automated tooling that may not
always produce a score.

**2. Should `contradicted_by` support cross-manifest references?**

Currently `contradicted_by` references a unit `id` within the same manifest. A federated
discovery pipeline may find the same capability described in two separate sub-manifests.
Cross-manifest references would require URL-qualified IDs and create federation complexity
similar to RFC-0010 open question 3.

**3. Lifecycle promotion workflow**

Should the spec define a RECOMMENDED workflow for promoting a unit from `rumored` →
`observed` → `verified`? Or is this tooling-specific and outside the spec's scope?

---

## Relationship to Other RFCs

- **RFC-0004 (Trust and Compliance):** `trust.provenance` declares who published the manifest. `discovery.source` declares how each unit was found. Together: publisher identity + per-capability acquisition method.
- **RFC-0010 (Bi-Temporal Unit Validity):** `temporal` tracks when knowledge was valid and when recorded. `discovery` tracks how confidently it was known at recording time. Both may be declared on the same unit.
- **RFC-0011 (Org Federation):** Units discovered via federation traversal will typically start at `verification_status: observed` and be promoted to `verified` as teams confirm them.
- **RFC-0005 (Rate Limits):** `rate_limits` on units discovered via `web_traversal` SHOULD be populated from observed or configured backoff parameters.

---

*Knowledge Context Protocol — [eXOReaction AS](https://www.exoreaction.com), Oslo, Norway.*
