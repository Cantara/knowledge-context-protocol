# RFC-0026: Escalation and Grant Requests

**Status:** Draft
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-07-24
**Related:** RFC-0002 (Auth and Delegation), RFC-0009 (Visibility and Authority Declarations),
RFC-0025 (Authority Level and Multi-Source Grant Ceiling) — this RFC builds on all three and
replaces none of them.
**Motivating case:** Mynder Orchestration Canvas v1 (Cantara customer deployment) — same
deployment as RFC-0025, completing the mechanism RFC-0025 deliberately left open (its Open
Question 6).

---

## What This RFC Proposes

Three prior mechanisms declare *when* a human must be involved, but none defines *what happens
next* as a concrete, auditable object:

- RFC-0002's `delegation.human_in_the_loop.required: true` — a human must approve before a
  unit may be loaded at all.
- RFC-0009's `authority.<action>: requires_approval` — a human must approve before a specific
  action on a specific unit.
- RFC-0025's `grant_ceiling` — computes an effective `authority_level` ceiling; a task that
  needs to exceed it currently has no defined path to ask for more.

All three currently stop at "a human should be asked" without specifying: asked *how*, asked
*who* (by name or role, not just "a human"), what the answer looks like, how long an answer is
valid for, and how a granted answer feeds back into evaluation. This RFC defines that object —
a **`GrantRequest`** — and its lifecycle, plus a fourth trigger type that RFC-0025's Open
Question 6 anticipated but did not define: escalation on low model-reported confidence in a
task's own output, evaluated *after* synthesis rather than before.

This RFC is scoped narrowly: it defines the request/response object and lifecycle. It does not
define a specific approval-mechanism transport (RFC-0002's `oauth_consent` / `uma` / `custom`
taxonomy already covers that, and this RFC reuses it rather than inventing a second one).

---

## The Problem

### "A human should approve" is not a protocol

A `GrantRequest`-shaped gap already exists in production. Mynder's own Policy Agent module
(GDPR supplier-document-retention review) independently built exactly this: a persisted state
machine (`scanning → pending_review → executed | dismissed`), a named approver
(`reviewedByUserId`), and full timestamping — because RFC-0002/0009's declarative "approval
required" flags gave it no runtime object to use. Every deployment that takes
`requires_approval` seriously ends up building its own version of the same lifecycle object.
That is exactly the kind of convergent, independently-invented mechanism a protocol should
capture once rather than leave to N reinventions.

### RFC-0025 computes a ceiling; nothing defines what happens when a task needs more

RFC-0025's `grant_ceiling` produces an effective `authority_level` and names the binding
source. It stops there deliberately (its Open Question 6). A task whose effective level is
`explain` but whose actual work requires `suggest` has no defined next step: today, an
implementation must invent its own escalation mechanism, and — this is the part RFC-0025 could
not specify on its own — that mechanism needs to know it can raise *only the specific binding
source*, not the whole computed minimum, or a naive implementation collapses `grant_ceiling`'s
entire multi-source discipline the first time someone approves anything.

### Confidence is a different kind of trigger — it does not exist before synthesis

Every mechanism in RFC-0009 and RFC-0025 is evaluated from **declared, static metadata**,
before an agent generates anything — `authority`, `authority_level`, and `grant_ceiling` are
all computable by a parser with no model in the loop. A model's own confidence in what it just
produced is categorically different: it does not exist until *after* synthesis, and no static
manifest field can express it in advance. Treating "low confidence" as if it were just another
static ceiling source (as an earlier informal review of this design space initially assumed)
is a category error. It needs its own trigger type, evaluated at a different point in the
pipeline, funneling into the *same* `GrantRequest` object rather than a separate one — the
shape of "agent hit a wall, needs a named human to lift it" is identical whether the wall is
an authority ceiling or the agent's own uncertainty.

### Concrete scenario

**Scenario A — Mynder, continued from RFC-0025:** A task-type's effective `authority_level`
(per RFC-0025) is `explain`, bound by the task-type's own manifest ceiling. The agent
determines the actual work would require `suggest`. Today, nothing in the manifest says who to
ask, what form the ask takes, how long a "yes" is good for, or how the "yes" is supposed to
interact with the five *other* `grant_ceiling` sources that were not the binding constraint.
Separately, the same deployment wants: if an agent's own confidence in a generated compliance
determination falls below a declared threshold, that must trigger the identical
human-in-the-loop pause — evaluated per-task after generation, not derivable from any
pre-declared manifest field.

---

## Design

### `GrantRequest` — the object

```yaml
grant_request:
  id: "gr-2026-07-24-0031"                  # unique per request, implementation-assigned
  trigger: insufficient_authority_level      # see Trigger vocabulary, below
  task_type_ref: change-formal-status
  agent_ref: lara-compliance

  # populated for trigger: insufficient_authority_level
  binding_source_ref: task-type-ceiling      # the grant_ceiling source (§3.13, RFC-0025) that capped the task
  current_effective_level: explain
  requested_level: suggest

  grantor:
    role: "kundeansvarlig"                  # named role or person, not just "a human"
    approval_mechanism: oauth_consent        # reuses RFC-0002's §3.4 vocabulary — oauth_consent | uma | custom

  grant_scope: single_use                   # single_use | time_bound | standing
  status: pending                           # pending | granted | denied | expired

  requested_at: "2026-07-24T09:15:00Z"
  resolved_at: null
  resolved_by: null
  justification: "Customer-supplied evidence contradicts the auto-classified status; need to propose a correction, not just explain the discrepancy."
```

**`grant_request` fields:**

| Field | Required | Type | Description |
|-------|----------|------|--------------|
| `id` | REQUIRED | string | Unique per request. Implementation-assigned; format is implementation-defined but MUST be stable for the request's lifetime. |
| `trigger` | REQUIRED | enum | One of the Trigger vocabulary values, below. |
| `task_type_ref` | REQUIRED | string | The `task_types[].id` (RFC-0025 §3.13) this request concerns. |
| `agent_ref` | OPTIONAL | string | The `agents[].id` (RFC-0025 §3.13) making the request, if applicable. |
| `binding_source_ref` | REQUIRED for `insufficient_authority_level` | string | The `grant_ceiling.sources[].id` (§3.13) identified as the binding constraint in the original ceiling computation. |
| `current_effective_level` | REQUIRED for `insufficient_authority_level` | string | The `authority_level` computed before this request. |
| `requested_level` | REQUIRED for `insufficient_authority_level` | string | The `authority_level` the task needs. MUST exceed `current_effective_level` (a request that doesn't ask for more than the current ceiling is not an escalation). |
| `requested_action` | REQUIRED for `requires_approval` | string | The RFC-0009 §4.17 action (`modify`, `execute`, etc.) awaiting approval. |
| `confidence_observed` | REQUIRED for `confidence_below_threshold` | float 0.0–1.0 | The agent-reported confidence that triggered escalation. |
| `confidence_threshold_ref` | REQUIRED for `confidence_below_threshold` | string | Reference to the `task_types[].confidence_threshold` (below) that was breached. |
| `grantor.role` | REQUIRED | string | Named role or person expected to resolve this request. MAY reference a role vocabulary the deployment maintains outside KCP (KCP does not define an identity/role directory). |
| `grantor.approval_mechanism` | REQUIRED | enum | `oauth_consent` \| `uma` \| `custom` — reuses RFC-0002 §3.4's vocabulary exactly; this RFC does not define a second transport taxonomy. |
| `grant_scope` | REQUIRED | enum | `single_use` (this task instance only — default and safest) \| `time_bound` (valid until an expiry, below) \| `standing` (persistent until explicitly revoked). |
| `expires_at` | REQUIRED if `grant_scope: time_bound` | ISO 8601 datetime | When a `time_bound` grant stops being valid. |
| `status` | REQUIRED | enum | `pending` \| `granted` \| `denied` \| `expired`. |
| `requested_at` / `resolved_at` | REQUIRED / OPTIONAL | ISO 8601 datetime | Request and resolution timestamps. `resolved_at` is null while `status: pending`. |
| `resolved_by` | REQUIRED once resolved | string | Identity of whoever actually resolved the request. MUST be recorded even if it matches `grantor.role` — this is the audit fact, not the request's expectation. |
| `justification` | OPTIONAL | string | Free-text reason for the request, surfaced to the grantor. |

### Trigger vocabulary

| Trigger | Evaluated | Source |
|---------|-----------|--------|
| `insufficient_authority_level` | Before synthesis (static) | RFC-0025 `grant_ceiling` computes an effective level lower than the task requires. |
| `requires_approval` | Before the specific action | RFC-0009 `authority.<action>: requires_approval` is hit. |
| `confidence_below_threshold` | **After synthesis** (runtime, model-reported) | This RFC's `task_types[].confidence_threshold` (below) is breached by the agent's own reported confidence in its output. |

The third trigger is categorically different from the first two and MUST NOT be treated as
just another `grant_ceiling` source (RFC-0025 §3.13): it cannot be computed by a manifest
parser with no model in the loop, because the value being compared does not exist until after
the agent has generated its output. Implementations MUST evaluate it as a distinct,
post-synthesis check.

### `confidence_threshold` — declared per task-type

```yaml
task_types:
  - id: change-formal-status
    authority_level: explain
    confidence_threshold: 0.70   # OPTIONAL — agent-reported confidence below this triggers a GrantRequest
```

| Field | Required | Type | Description |
|-------|----------|------|--------------|
| `task_types[].confidence_threshold` | OPTIONAL | float 0.0–1.0 | If the acting agent reports confidence in its output below this value for this task-type, a `GrantRequest` with `trigger: confidence_below_threshold` MUST be raised before the output is surfaced as a decision. Absence means no confidence-based escalation is required for this task-type — this is a declared opt-in, not a default-on check, since not every task-type has a well-defined notion of "confidence" to report. |

KCP does not define how an agent computes its own confidence value — that is the runtime's
responsibility, the same division of labor RFC-0009 draws between declaring `execute: denied`
and an alignment-dependent model actually respecting it (see RFC-0009 Appendix D). This field
only defines the threshold and the resulting obligation to escalate.

### Resolution semantics — how a grant feeds back into `grant_ceiling`

This is the part that must be precise, or the whole discipline RFC-0025 built collapses the
first time someone clicks "approve":

- A **granted** `insufficient_authority_level` request raises *only* the specific
  `grant_ceiling.sources[]` entry named in `binding_source_ref`, to (at most)
  `requested_level`, for the declared `grant_scope`. It does not touch, override, or bypass any
  other source.
- The effective `authority_level` MUST be **recomputed as the minimum across all sources**,
  with the named source's value now raised. If a *different* source is now the binding
  constraint (because it was already lower than the original binding source, or became lower
  independently), the task remains capped by that other source, and a fresh `GrantRequest`
  against *that* source is required — an escalation grant is never a blanket override of the
  whole ceiling.
- A **granted** `requires_approval` request permits exactly the one `requested_action` on the
  one unit/task combination the request named, per `grant_scope`. It does not grant other
  RFC-0009 actions, and does not touch `grant_ceiling` at all — the two mechanisms compose the
  same way they do without this RFC (RFC-0025's normative capping table still applies to
  whatever `authority_level` is in effect).
- A **granted** `confidence_below_threshold` request permits the specific output to be
  surfaced as a decision. It is inherently `single_use` — see Conformance; `time_bound` and
  `standing` grant scopes MUST NOT be used for this trigger, since a confidence observation is
  about one generated output, not a standing property of the task-type or agent.
- A **denied** or **expired** request leaves the effective level, action permission, or output
  suppression exactly as it was before the request — denial is not itself a demotion, it is a
  refusal to raise.
- `grant_scope: standing` grants SHOULD be paired with a periodic-review obligation
  (implementation-defined; this RFC does not mandate a specific review cadence) — a standing
  grant that is never revisited reintroduces the exact silent-ceiling-erosion risk RFC-0025's
  `mandatory_sources` was designed to prevent.

---

## Complete Example

Continuing RFC-0025's worked example (§3.13): `change-formal-status`'s effective
`authority_level` was `explain`, bound by `task-type-ceiling`. The agent's actual task needs
`suggest`:

```yaml
grant_request:
  id: "gr-2026-07-24-0031"
  trigger: insufficient_authority_level
  task_type_ref: change-formal-status
  agent_ref: lara-compliance
  binding_source_ref: task-type-ceiling
  current_effective_level: explain
  requested_level: suggest
  grantor:
    role: "kundeansvarlig"
    approval_mechanism: oauth_consent
  grant_scope: single_use
  status: granted
  requested_at: "2026-07-24T09:15:00Z"
  resolved_at: "2026-07-24T09:22:00Z"
  resolved_by: "user:kari.nordmann@example.com"
  justification: "Customer-supplied evidence contradicts the auto-classified status; need to propose a correction, not just explain the discrepancy."
```

**Recomputation:** `task-type-ceiling` is raised to `suggest` for this single task instance
only. Recomputing the minimum across RFC-0025's six sources (`org-risk-policy: prepare`,
`org-data-policy: suggest`, `regulatory-constraint: suggest`, `task-type-ceiling: suggest` [was
`explain`, now raised], `agent-capability-ceiling: prepare`, `customer-setting: prepare`) — new
effective level: `suggest`, now bound jointly by `org-data-policy` and `regulatory-constraint`
(both at `suggest`, the new minimum). If the agent's task later needed `prepare`, a fresh
`GrantRequest` naming one of *those* two sources as `binding_source_ref` would be required —
this grant does not reach that far.

---

## Open Questions

**1. Where does `grant_request` live — in the manifest, or as a separate runtime artifact?**

Everything else in RFC-0025/RFC-0009 is manifest-declared, static configuration. A
`GrantRequest` is inherently a runtime, per-instance artifact (it has a lifecycle, timestamps,
a specific resolution). This RFC shows it in YAML for illustration, but it is likely wrong for
`grant_request` objects to live inside `knowledge.yaml` itself, growing unboundedly with every
request ever made. Should this RFC instead define an API/event shape (request created, request
resolved) rather than a manifest field, with only the *reference* (`task_type_ref`,
`binding_source_ref`) living in the manifest? Leaning yes; this draft did not fully resolve the
manifest-vs-API boundary.

**2. Does `resolved_by` need to be verifiably bound to `grantor.role`, or is recording it enough?**

The design requires recording `resolved_by` regardless of whether it matches the expected
`grantor.role`, for audit honesty. It does not currently require *verifying* that whoever
resolved the request was actually authorized to hold that role — that verification is left to
the `approval_mechanism` (OAuth/UMA token claims, the same trust boundary RFC-0009 Appendix C
already draws for `agent_role`). Is that division of responsibility sufficient, or does this
RFC need its own authorization check independent of the approval mechanism?

**3. Should `confidence_below_threshold` support a threshold that varies by
`authority_level`, not just by task-type?**

The current design ties `confidence_threshold` to a task-type only. A deployment might
reasonably want a stricter confidence bar at `suggest` than at `observe` for the same
task-type. Deferred as unnecessary complexity for v1; worth revisiting once real deployments
report whether a single per-task-type threshold is too coarse.

**4. Expiry semantics for `time_bound` grants that outlive the manifest version they were granted against**

If a `time_bound` grant is still active when the manifest's `grant_ceiling` sources change
(a new manifest version ships with a different `task-type-ceiling` value), does the grant
still apply to the old ceiling value, the new one, or does a manifest version change implicitly
expire all outstanding `time_bound` grants? This RFC does not currently address version/grant
interaction and probably should before this leaves Draft.

---

## Relationship to Other RFCs

- **RFC-0002 (Auth and Delegation):** `grantor.approval_mechanism` reuses
  `delegation.human_in_the_loop.approval_mechanism`'s vocabulary verbatim rather than defining
  a parallel one. This RFC gives `human_in_the_loop.required: true` the runtime object it
  previously lacked.
- **RFC-0009 (Visibility and Authority Declarations):** `requires_approval` actions resolve
  through a `GrantRequest` with `trigger: requires_approval`. This RFC does not change RFC-0009's
  capping semantics.
- **RFC-0025 (Authority Level and Multi-Source Grant Ceiling):** answers RFC-0025's own Open
  Question 6. `binding_source_ref` and the recomputation rule depend directly on RFC-0025's
  named-binding-source requirement (§3.13) — a `GrantRequest` cannot state what it is raising
  without RFC-0025 having named what was binding in the first place.

---

## Conformance

| Feature | Level | Notes |
|---------|-------|-------|
| `grant_request` with `trigger: requires_approval` | Level 2 | Reuses existing RFC-0009/RFC-0002 mechanisms |
| `grant_request` with `trigger: insufficient_authority_level` | Level 3 | Requires RFC-0025 `grant_ceiling` (Level 3) |
| `grant_request` with `trigger: confidence_below_threshold` | Level 3 | Requires runtime (post-synthesis) evaluation, not just manifest parsing |
| `grant_scope: single_use` | Level 2 | Default, simplest to reason about |
| `grant_scope: time_bound` / `standing` | Level 3 | Requires expiry/revocation handling |

`confidence_below_threshold` grants MUST use `grant_scope: single_use`; a `time_bound` or
`standing` grant on this trigger type is a conformance violation (see Resolution semantics).

---

## Backward Compatibility

| Addition | Pre-RFC-0026 behaviour | Risk |
|----------|------------------------|------|
| `grant_request` object, `task_types[].confidence_threshold` | Silently ignored per SPEC.md §2 | None |
| Manifests using only RFC-0002/0009/0025 | Fully valid, unaffected | None |

This RFC adds no new required fields to any existing block and deprecates nothing.

---

*Knowledge Context Protocol — [eXOReaction AS](https://www.exoreaction.com), Oslo, Norway.*
