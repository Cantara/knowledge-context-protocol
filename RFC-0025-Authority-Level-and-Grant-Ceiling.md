# RFC-0025: Authority Level and Multi-Source Grant Ceiling

**Status:** Accepted — promoted to SPEC.md v0.27
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-07-24
**Related:** RFC-0009 (Visibility and Authority Declarations) — this RFC extends it, does not replace it.
**Motivating case:** Mynder Orchestration Canvas v1 (Cantara customer deployment) — see Scenario A.

---

## What This RFC Proposes

RFC-0009 answers *"may an agent take this specific action (read / modify / execute / …) on
this specific unit?"* — a three-value permission (`initiative` / `requires_approval` / `denied`)
per action, per unit.

This RFC answers a related but different question: *"how much discretion does an agent hold
for this task, as a single ordinal ceiling — and when five or six independent policies each
try to set that ceiling, which one wins?"* Two additions:

1. **`authority_level`** — an ordinal scale (`observe < explain < suggest < prepare < commit`)
   declared per task-type/unit/agent rather than per unit-action. It is coarser than RFC-0009's
   `authority` block and sits above it: `authority_level` caps the *role* an agent plays in a
   task; RFC-0009's `authority` block still governs individual actions on individual units
   within whatever role is granted. (The top level is named `commit`, not `execute`, to avoid
   colliding with RFC-0009's `authority.execute` action — the two are unrelated concepts that
   happen to share a word in earlier drafts of this RFC; see Interaction table below.)

2. **`grant_ceiling`** — a named-source minimum computation. Where RFC-0009 resolves a single
   authority value through an override chain (unit → root → default), several real deployments
   need to combine **multiple independently-owned ceilings that all apply at once** — an org-wide
   risk policy, a data-sensitivity policy, a regulatory constraint, a per-task-type manifest
   declaration, an agent's own capability ceiling, and an end-customer's own dial — and take
   the *strictest*, not the *last-declared*. This directly answers RFC-0009's own **Open
   Question 2** ("hub operators may want to enforce a global floor"), generalized from
   hub-vs-sub-manifest to N named, independently-versioned sources.

Both additions are backward compatible and optional, following RFC-0009's own pattern. A
manifest with neither block behaves exactly as it does today — with one caveat now made
explicit (see "Absence is not a grant" below): declaring `authority_level_scale` at the
manifest root without declaring `authority_level`/`grant_ceiling` on a task-type is a real gap,
not a neutral default, and this RFC requires implementations to warn on it.

---

## The Problem

### RFC-0009's model is per-action; several real deployments need a per-task-type ceiling

RFC-0009's `authority` block is deliberately scoped to *content actions* (read, summarize,
modify, share_externally, execute) on a *unit*. This is the right scope for "may an agent
read/modify/share this document" — but it does not express "what role does this agent hold in
this task, on an ordinal scale from passive observation to full execution," which several
governance frameworks built on top of agentic systems need as a first-class, coarser concept
independent of any single unit's content actions.

A concrete instance: a task-type such as "propose which compliance requirements apply to this
customer" is not well captured as `authority.summarize` or `authority.modify` on any one
unit — it is a question about the *task itself*: is the agent only allowed to observe and
explain (levels 1–2), suggest without committing (level 3), prepare a change for a human to
commit (level 4), or commit the change itself (level 5)? The same task-type, run against
different units, should carry one consistent authority level — today there is no single field
that expresses this.

### RFC-0009 left the multi-source floor question open

RFC-0009's Open Question 2 asks: *"If a hub manifest declares `authority.modify:
requires_approval` globally, does this propagate to federated sub-manifests? ... hub operators
may want to enforce a global floor."* The RFC shipped without resolving this because the
motivating case at the time was two-level (hub / sub-manifest). Real deployments now have
more than two independently-owned ceiling sources that must combine, not override each other:

- An organization-wide policy ceiling by risk category (e.g., "no task touching customer-facing
  legal status may exceed `suggest`").
- A separate organization-wide policy ceiling by data category (e.g., "no task touching
  personal data may exceed `prepare`").
- A regulatory constraint carried by the matched knowledge unit itself (some requirements
  mandate human sign-off regardless of internal policy).
- A per-task-type ceiling declared by the workflow/procedure definition itself (this task-type,
  specifically, never exceeds `prepare`).
- The acting agent's own declared capability ceiling (this agent has never been granted more
  than `suggest` for any task).
- An end-customer's own chosen setting.

None of these six is "more authoritative" than the others in the RFC-0009 sense of an override
chain (unit beats root beats default) — they are independently owned, independently versioned,
and **all apply simultaneously**. The effective authority for a given task is the *minimum*
across all of them, and — critically for audit purposes — the *specific source that set the
binding ceiling* must be named, not just the resulting number. No source, including the
customer's own dial, needs a special flag to enforce "can only lower" — that property is
inherent to taking a minimum across sources (see Design, below); an earlier draft of this RFC
proposed a `may_only_lower` flag before recognizing it was a no-op given the minimum semantics,
and removed it (see Changelog).

### Concrete scenario

**Scenario A — Mynder (Cantara customer, compliance SaaS for MSPs):** Mynder's internal
operating model ("Orchestration Canvas") requires that every agent-executed task declare an
authority level on the same five-value scale this RFC proposes, and that the *effective*
level for any task be the lowest of: the org's risk-category policy, the org's data-category
policy, applicable regulatory constraints, the task-type's own declared ceiling, the acting
agent's capability ceiling, and the customer's own setting. Prior to this RFC, no field in a
KCP manifest could express either the ordinal scale or the multi-source minimum — RFC-0009's
per-unit, per-action, override-chain model does not fit a requirement that is per-task-type
and multi-source-intersected by design.

---

## Design

### `authority_level` — the ordinal scale

```yaml
authority_level_scale:
  - observe    # agent may read/observe only; no output surfaced as a decision
  - explain    # agent may explain/analyze; no proposal offered
  - suggest    # agent may propose; a human decides
  - prepare    # agent may prepare a change; a human commits it
  - commit     # agent may commit the change itself
```

The scale is fixed and total: each level strictly exceeds the one before it, and is not
per-manifest extensible (see Open Question 1 — this is a deliberate, load-bearing choice: a
`grant_ceiling` minimum across sources declared in different manifests only means something if
every source shares the same total order).

Declaring an `authority_level` anywhere in this RFC's blocks means "at most this level" — the
same ceiling semantics RFC-0009 uses for its `authority` values, just on an ordinal scale
instead of a three-value one.

A task-type declares its own ceiling:

```yaml
task_types:
  - id: propose-applicable-requirements
    intent: "Propose which compliance requirements apply to this customer"
    authority_level: suggest        # this task-type never exceeds "suggest"

  - id: change-formal-status
    intent: "Change the formal status of a customer's compliance record"
    authority_level: explain        # tightly capped: agent may explain, not suggest or act
```

**`task_types[]` fields:**

| Field | Required | Type | Description |
|-------|----------|------|--------------|
| `task_types[].id` | REQUIRED | string | Stable identifier, unique within the manifest. A duplicate `id` is a manifest error, matching the `units[].id` uniqueness rule. |
| `task_types[].intent` | OPTIONAL | string | One-sentence description of the task-type, in the style of unit `intent` (§3.7). |
| `task_types[].authority_level` | OPTIONAL | string | One of `authority_level_scale`. This task-type's own declared ceiling. Absence means this task-type declares no ceiling of its own (see "Absence is not a grant," below — this does not mean unconstrained if referenced from a `grant_ceiling` that includes other sources). |

**`agents[]` fields:**

| Field | Required | Type | Description |
|-------|----------|------|--------------|
| `agents[].id` | REQUIRED | string | Stable identifier, unique within the manifest. A duplicate `id` is a manifest error. |
| `agents[].name` | OPTIONAL | string | Display name. |
| `agents[].authority_level` | OPTIONAL | string | One of `authority_level_scale`. This agent's own declared capability ceiling, across all tasks it is assigned. |

### `grant_ceiling` — multi-source minimum with named binding source

```yaml
grant_ceiling:
  sources:
    - id: org-risk-policy
      authority_level: prepare
    - id: org-data-policy
      authority_level: suggest
    - id: regulatory-constraint
      unit_ref: gdpr-art-30-processing-log   # ceiling comes from the matched unit's own declaration
    - id: task-type-ceiling
      task_type_ref: change-formal-status
    - id: agent-capability-ceiling
      agent_ref: lara-compliance
    - id: customer-setting
      authority_level: prepare
  mandatory_sources: [org-risk-policy, org-data-policy]
```

**Evaluation semantics:**

- Each source resolves to one `authority_level` value (either declared inline, or looked up
  via `unit_ref` / `task_type_ref` / `agent_ref` against that referenced entity's own
  `authority_level` declaration).
- The **effective authority level is the minimum** across all resolved sources. This is why no
  source needs a "may only lower" flag: a minimum computation cannot be raised by any single
  input by construction — adding a source can only hold the result steady or lower it further.
- The evaluator MUST record, alongside the effective level, **which source(s) produced the
  binding value** — i.e., which source(s) tied for the minimum. This mirrors the existing
  requirement (§4.22 / RFC-0020, RFC-0021) that a `superseded_by` relationship name the specific
  successor unit, and the `not_for` filter's `caution` annotation (§15.11) naming why a unit was
  demoted rather than silently dropped: a capped result without a named cause is not sufficient
  for an audit trail.
- If a referenced entity (`unit_ref`/`task_type_ref`/`agent_ref`) has no `authority_level`
  declared, that source MUST be treated as non-binding (excluded from the minimum), not as an
  implicit `commit` (most-permissive). Absence of a declared ceiling on the *referenced entity*
  is not itself a grant.
- **Cycle detection is REQUIRED.** Resolving `unit_ref` / `task_type_ref` / `agent_ref` MUST
  maintain a visited-set across the resolution chain. If resolving a source's reference would
  revisit an entity already in the chain (directly, or transitively through that entity's own
  `grant_ceiling`), this MUST be detected and reported as a manifest error — not silently
  broken or left to loop. This matches the cycle-detection requirement already normative for
  `composition.includes` (§14.3) and for `superseded_by` relationships (§4.22, §21.2).

**Absence is not a grant — mandatory sources and warnings:**

`grant_ceiling` and `authority_level` are both optional per RFC-0009's backward-compatibility
pattern, but optionality creates a real gap this RFC must not paper over: a task-type that
declares neither is entirely unconstrained by this mechanism, silently. This defeats the
Scenario A requirement ("all six sources apply simultaneously") the moment any one task-type's
author forgets to cite one.

Two requirements close this gap:

- `grant_ceiling.mandatory_sources` (OPTIONAL, list of source `id`s) MAY be declared at the
  manifest root. If present, every `grant_ceiling` block within the manifest MUST include a
  source for each listed `id`. A `grant_ceiling` missing a mandatory source MUST be reported as
  a manifest error. This lets an org pin "the risk-policy and data-policy sources are never
  optional" so a leaf task-type cannot silently drop them.
- Independent of `mandatory_sources`, if a manifest declares `authority_level_scale` at the
  root but a `task_types[]` entry declares neither `authority_level` nor `grant_ceiling`,
  implementations SHOULD emit a §7 warning (`authority_ceiling_undeclared`) — the manifest
  author opted into the scale but left this task-type unconstrained by it, which is far more
  likely to be an oversight than an intentional choice.

**`grant_ceiling` fields:**

| Field | Required | Type | Description |
|-------|----------|------|--------------|
| `grant_ceiling.sources` | REQUIRED | list | List of ceiling sources. Order does not affect the computed minimum (order-independent) but MAY affect which source is reported first when multiple tie (see Open Question 3). |
| `grant_ceiling.sources[].id` | REQUIRED | string | Stable identifier for this source, used in the effective-level trace and in `mandatory_sources`. |
| `grant_ceiling.sources[].authority_level` | OPTIONAL | string | Inline ceiling value from `authority_level_scale`. Mutually exclusive with the `*_ref` fields. |
| `grant_ceiling.sources[].unit_ref` | OPTIONAL | string | Resolve ceiling from a referenced unit's own `authority_level`. |
| `grant_ceiling.sources[].task_type_ref` | OPTIONAL | string | Resolve ceiling from a referenced task-type's own `authority_level`. |
| `grant_ceiling.sources[].agent_ref` | OPTIONAL | string | Resolve ceiling from a referenced agent's own declared capability ceiling. |
| `grant_ceiling.mandatory_sources` | OPTIONAL | list of strings | Source `id`s that MUST appear in every `grant_ceiling` block in this manifest. |

### Interaction with RFC-0009 — normative capping table

`authority_level` and RFC-0009's `authority` block answer different questions at different
granularities and are both evaluated, not merged. An agent's effective `authority_level` for a
task caps what RFC-0009 permission values are honored for units touched by that task, per the
table below — **the effective RFC-0009 permission is the minimum of (a) the unit's own declared
value and (b) this table's cap for the effective `authority_level`**, using RFC-0009's own
ordering `denied < requires_approval < initiative`:

| Effective `authority_level` | `read` cap | `summarize` cap | `modify` cap | `share_externally` cap | `execute` cap |
|---|---|---|---|---|---|
| `observe` | initiative | requires_approval | denied | denied | denied |
| `explain` | initiative | initiative | denied | denied | denied |
| `suggest` | initiative | initiative | requires_approval | denied | denied |
| `prepare` | initiative | initiative | requires_approval | requires_approval | requires_approval |
| `commit` | initiative | initiative | initiative | initiative | initiative |

This is normative, not illustrative — no discretion is left to the implementer for how a given
`authority_level` caps a given RFC-0009 action. (This table is itself open for comment; see
Open Question 2b if the specific cap values above should differ — but the requirement that
*some* complete, normative table exists is not optional, given two independently-conformant
bridges must agree on this behavior for the Scenario A audit use case to hold.)

Worked example: a unit declares `authority.modify: initiative`, but the task's effective
`authority_level` is `suggest`. Effective `modify` permission = min(`initiative`,
`requires_approval`) = `requires_approval`. RFC-0009's per-unit declaration remains the
finer-grained control *within* whatever room the task-level ceiling leaves; it cannot widen it.

---

## Complete Example

```yaml
kcp_version: "0.21"
project: mynder-core

authority_level_scale:
  - observe
  - explain
  - suggest
  - prepare
  - commit

task_types:
  - id: change-formal-status
    intent: "Change the formal status of a customer's compliance record"
    authority_level: explain   # this task-type's own manifest ceiling

agents:
  - id: lara-compliance
    authority_level: prepare  # this agent's own capability ceiling, across all tasks

units:
  - id: gdpr-art-30-processing-log
    path: compliance/gdpr-art30.md
    intent: "What personal data processing activities are recorded for GDPR Article 30?"
    authority_level: suggest   # a regulatory ceiling carried by the matched unit

    authority:                 # RFC-0009 — finer-grained, evaluated within the ceiling below
      read: initiative
      summarize: initiative
      modify: requires_approval
      share_externally: denied

grant_ceiling:
  sources:
    - id: org-risk-policy
      authority_level: prepare
    - id: org-data-policy
      authority_level: suggest
    - id: regulatory-constraint
      unit_ref: gdpr-art-30-processing-log
    - id: task-type-ceiling
      task_type_ref: change-formal-status
    - id: agent-capability-ceiling
      agent_ref: lara-compliance
    - id: customer-setting
      authority_level: prepare
  mandatory_sources: [org-risk-policy, org-data-policy]
```

**Resolved effective authority level:** `explain` — bound by `task-type-ceiling`
(`change-formal-status` declares `explain`), which is stricter than every other source
(`org-data-policy` and `regulatory-constraint` both resolve to `suggest`; `org-risk-policy`,
`agent-capability-ceiling`, and `customer-setting` all resolve to `prepare`). The evaluator's
trace reports `explain` with `task-type-ceiling` named as the sole binding source. Both
`mandatory_sources` entries (`org-risk-policy`, `org-data-policy`) are present, so this
`grant_ceiling` is valid; removing either from `sources` would be a manifest error.

Applying the capping table above to `gdpr-art-30-processing-log`'s own `authority.modify:
requires_approval` at effective level `explain`: cap for `modify` at `explain` is `denied`,
so effective `modify` permission = min(`requires_approval`, `denied`) = `denied` — stricter
than the unit's own declaration, because the task-type ceiling is the binding constraint.

---

## Open Questions

**1. Is five levels the right scale, or should it stay fixed vs. become extensible?**

RFC-0009 deliberately made its action vocabulary extensible (custom actions beyond the five
standard ones). This RFC keeps `authority_level_scale` fixed because `grant_ceiling`'s minimum
computation requires a shared total order across independently-authored manifests — an
extensible scale breaks comparability the moment two manifests extend it differently. A hybrid
worth considering before closing this out: numeric ordinal values (e.g. 10/20/30/40/50) with a
recommended-but-overridable alias layer, giving implementations room to insert intermediate
levels later without breaking existing comparisons. Flagged during adversarial review as a
real alternative, not fully evaluated in this draft.

**2a. Should `grant_ceiling` be root-scoped (as in this draft), nested under `task_types[]`,
or both?**

The example above scopes `grant_ceiling` at the manifest root, resolved per task-type via
`task_type_ref`. Some deployments may want a `grant_ceiling` block nested directly under a
`task_types[]` entry instead. Both are equivalent in expressive power; root-level with
explicit refs was chosen for this draft because it lets ceiling sources be declared once and
reused across many task-types without duplication. **This must be resolved together with cycle
detection, not independently** — if `grant_ceiling` moves under `task_types[]`, task-type ↔
task-type cycles (A's ceiling references B via `task_type_ref`, B's references A) become the
most natural failure shape, and the cycle-detection requirement above must cover that shape
before this question is settled either way.

**2b. Are the specific values in the RFC-0009 capping table correct?**

The table proposed above (e.g., `observe` capping `summarize` to `requires_approval` rather
than `denied`) reflects one plausible reading of what each ordinal level should permit. This is
the first attempt at making the interaction fully normative rather than the specific values
being load-bearing; reviewers with more field experience across the five levels should treat
the exact cap values as negotiable, the *requirement that a complete table exists* as not.

**3. Tie-breaking when multiple sources bind at the same minimum**

The design says to report all sources that tie for the minimum. Is that sufficient, or should
the spec define a deterministic tie-break order (e.g., regulatory constraints always reported
first) for implementations that want to surface a single "primary reason" string rather than a
set?

**4. Is `mandatory_sources` enforcement strong enough, or does a hub need to pin it non-overridably?**

`mandatory_sources` is declared in the same manifest it constrains — a leaf manifest author
could simply not declare it, or a federated sub-manifest could omit inherited `mandatory_sources`
entries entirely (see RFC-0011 federation). Should a hub manifest be able to pin
`mandatory_sources` in a way that propagates to and cannot be removed by federated
sub-manifests, the way `not_for` scope boundaries work at the manifest level (§3.10)?

**5. Relationship to `delegation.human_in_the_loop` (RFC-0002)**

RFC-0002's HITL block is manifest-level (is a human in the loop for this source at all).
`authority_level` resolving to `explain` or below for a task is a strong signal that HITL
should be required for that task — should the spec state this as a normative MUST, or leave
the connection between the two RFCs as guidance only (as RFC-0009 does today for
`requires_approval` and RFC-0002's approval mechanism)?

**6. Does the effective `authority_level` need its own approval/escalation mechanism, distinct
from RFC-0009's `requires_approval`?**

This RFC defines how the ceiling is *computed*. It does not define what happens when an agent's
task requires exceeding its currently effective ceiling (an escalation request). That is
deliberately out of scope here — see the companion discussion on a human-in-the-loop
approval mechanism, which may be its own RFC building on both this one and RFC-0002.

---

## Relationship to Other RFCs

- **RFC-0009 (Visibility and Authority Declarations):** This RFC extends RFC-0009 rather than
  replacing it. RFC-0009's Open Question 2 (federation-wide floor) is the two-source special
  case of this RFC's `grant_ceiling` (N named sources, minimum, named binding source).
  RFC-0009's per-unit `authority` block remains the fine-grained action control *within*
  whatever ceiling this RFC's `grant_ceiling` computes, per the normative capping table above.
- **RFC-0002 (Auth and Delegation):** `delegation.human_in_the_loop` and this RFC's
  `authority_level` are complementary — see Open Question 5.
- **RFC-0008 (Agent Readiness):** `requires_capabilities` says whether an agent *can* act;
  this RFC's `agent_ref` ceiling source says how much discretion it *may* hold once it can.
- **§14.3 (Composition) / §4.22, §21.2 (Temporal, `superseded_by`):** source of the cycle-detection
  requirement this RFC's `unit_ref`/`task_type_ref`/`agent_ref` chain reuses verbatim.

---

## Conformance

| Feature | Level | Notes |
|---------|-------|-------|
| `authority_level` on a unit, task-type, or agent | Level 2 | Single declared ceiling |
| `grant_ceiling` with inline `authority_level` sources only | Level 2 | No cross-references, no cycle risk |
| `grant_ceiling` with `unit_ref`/`task_type_ref`/`agent_ref` sources | Level 3 | Cross-entity resolution; cycle detection REQUIRED |
| `grant_ceiling.mandatory_sources` | Level 3 | Enforcement of required source citation |
| Named binding-source trace output | Level 3 | Required for audit/evidence use cases |
| RFC-0009 capping table applied | Level 3 | Both blocks evaluated together, not just declared |

A conformance test for `grant_ceiling` MUST include at least one cycle case (expect: manifest
error) and one `mandatory_sources` omission case (expect: manifest error), given both are now
normatively required rather than advisory.

---

## Backward Compatibility

| Addition | Pre-RFC-0025 parser behaviour | Risk |
|----------|-------------------------------|------|
| `authority_level_scale`, `authority_level`, `grant_ceiling`, `task_types`, `agents` | Silently ignored per SPEC.md §2 | None — all are new top-level or nested keys, not modifications to existing ones |
| Manifests with only RFC-0009's `authority` block | Fully valid, unaffected | None |

This RFC does not deprecate or modify any RFC-0009 field. A manifest may adopt `authority_level`
without adopting `grant_ceiling`, or vice versa, though the two are designed to be used together
in deployments with more than one governing policy source.

---

## Changelog

- **v2 (2026-07-24):** Revised after adversarial review. Added: cycle-detection requirement for
  `unit_ref`/`task_type_ref`/`agent_ref` (matching `composition.includes`/`superseded_by`
  precedent); field tables for the new `task_types[]`/`agents[]` collections with id-uniqueness
  rules; a normative RFC-0009 capping table (replacing a single hand-wavy example); renamed the
  top ordinal level `execute` → `commit` to avoid colliding with RFC-0009's `authority.execute`
  action; added `grant_ceiling.mandatory_sources` and a required §7 warning for undeclared
  ceilings, closing a silent-bypass gap; removed the `may_only_lower` field as a semantic no-op
  given minimum-based evaluation, replacing it with an explicit prose statement of why no such
  flag is needed; corrected a citation that referenced `money_budget`/`max_units` filters which
  do not exist in SPEC.md, replacing it with the real `superseded_by`/`not_for` precedent.
- **v1 (2026-07-24):** Initial draft.

---

*Knowledge Context Protocol — [eXOReaction AS](https://www.exoreaction.com), Oslo, Norway.*
