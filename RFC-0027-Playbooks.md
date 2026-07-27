# RFC-0027: Playbooks — Governed Composition of Units

**Status:** Draft
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-07-27
**Related:** RFC-0025 (Authority Level and Grant Ceiling) — this RFC gives its ceilings a
per-step attachment point. RFC-0026 (Escalation and Grant Requests) — likewise.
RFC-0009 (Visibility and Authority Declarations). SPEC.md §4.3a (`kind`), §3.4 (delegation).
**Spec:** [SPEC.md](./SPEC.md) (current: v0.28) — targets **v0.29**, which is the version
the examples below declare.

---

## What This RFC Proposes

A seventh `kind`: `playbook` — an ordered composition of units, governed **per step**.

```yaml
- id: promote-release
  kind: playbook
  intent: "How do we promote a verified build to production?"
  scope: project
  audience: [agent, operator]
  authority_level: commit          # playbook-level ceiling; see below
  steps:
    - id: verify
      uses: run-test-suite            # a kind: skill unit
      authority_level: observe
      success_condition: "zero failures reported by the suite"
      on_failure: abort
    - id: prepare-change
      uses: open-promotion-request
      depends_on: [verify]
      authority_level: prepare
      success_condition: "request URL returned"
      on_failure: abort
    - id: promote
      uses: complete-promotion
      depends_on: [prepare-change]
      authority_level: commit
      escalation: requires_approval   # RFC-0026
      success_condition: "target branch head equals the verified commit"
      on_failure: escalate
```

The unit of governance is the **step**, not the artifact.

## The Problem

### `kind: executable` is governed at invocation; orchestration needs governance per step

§4.3a already offers a kind for runnable things:

| `executable` | Runnable artifacts: scripts, notebooks, workflow definitions | **Invoke on demand** |

That is a black box. An agent either may invoke it or may not, and everything the artifact
does thereafter is opaque to the protocol. For a single script that is the correct model.

Real orchestrations are not uniform in risk. A promotion procedure typically reads state,
proposes a change, waits for a human, and only then commits. Those four steps warrant four
different authority levels. Declaring one level for the whole artifact forces a choice
between two bad options: set it at the **highest** step's requirement and the reading steps
carry commit authority they never needed, or set it at the **lowest** and the artifact
cannot complete.

### RFC-0025 and RFC-0026 have no step-granular attachment point

RFC-0025 established an ordinal `authority_level` scale — `observe`, `explain`, `suggest`,
`prepare`, `commit` — and `grant_ceiling` as a multi-source minimum. RFC-0026 added
escalation semantics (`requires_approval`, `insufficient_authority_level`,
`confidence_below_threshold`) and an audit trail.

Both attach to a unit or a task-type. Neither can express *"step 3 runs at `observe`, step 5
requires `commit` and escalates first."* The vocabulary exists; the place to put it does not.

### Compositions exist in practice and are invisible to the protocol

In deployments the authors have worked on, the orchestrations that matter are implemented as
shell scripts, CI definitions, or bespoke chain files. Two consequences follow:

1. **The composition duplicates the units.** A chain step says *"run the test suite and
   confirm all tests pass"* in prose while a `kind: skill` unit documenting exactly that
   already exists. They then drift independently, and nothing detects the divergence.
2. **The composition is better governed than the units it orchestrates.** The chain formats
   observed carried `depends_on`, `success_condition`, `on_failure` and a human-confirmation
   flag per step — while the procedural units they drew on frequently declared no
   verification step at all. The orchestration layer had reinvented, informally, precisely
   the governance the protocol offers formally.

A protocol that can describe the parts but not the assembly leaves the assembly to be
re-specified, badly, by every deployment.

### Provenance of the claims in this section

This RFC is motivated by private deployments that cannot be named, and the claims about them
are therefore **not independently checkable by a reader**. They are stated as motivation, not
as evidence, and no normative decision below rests on them alone. Where an earlier draft gave
counts ("~670 units", "~160 without a verification step") and attributed a specific formula to
an unnamed implementation, those figures have been removed rather than left as unfalsifiable
precision.

This is deliberate, and it is a correction. Two prior RFCs in this series cited mechanisms
that did not exist — filters that were never implemented, a spec section that was never
written — and both citations survived review because they were plausible. An unverifiable
claim dressed as a measurement is the same failure with better manners. The design argument
in this RFC stands on the structure of the problem; if it needs a private deployment's
statistics to be convincing, it is not yet convincing.

### Concrete scenario (anonymised)

A regulated-industry customer runs an *agentic operating model*: humans own goals, judgement
and decisions; agents perform analysis, coordination and follow-up **within declared
mandates**. Their governance model defines an authority ladder per task type, "lowest-of"
steering when multiple sources bound the same work, stop-triggers, and a lifecycle for
procedures (`draft → active → superseded`) where an agent may *propose* an update but only a
human may promote it.

Every one of those primitives now exists in KCP — RFC-0009, RFC-0025, RFC-0026, §4.3a
`kind: skill`, and the signing chain. What has no home is the thing the model is *about*:
the multi-step, multi-agent procedure that the mandates apply to. The customer's own design
names three artifact classes — knowledge, skill, and **playbook** — and only the first two
exist in the protocol.

## Design

### The `playbook` kind

```yaml
kind: playbook
```

Selection is gated exactly as `knowledge` and `skill` are — intent matching, `audience`,
`scope`, temporal validity, negative space. Enaction is governed **per step**, not per unit.

A playbook **MUST** declare `steps`. A playbook that declares no steps is a manifest error;
it is not a degenerate `executable`.

### Playbook-level fields

Beyond the fields every unit carries, a `kind: playbook` unit declares:

| Field | Required | Type | Description |
|---|---|---|---|
| `steps` | REQUIRED | list | Ordered list of step objects. MUST be non-empty. |
| `authority_level` | OPTIONAL | string | RFC-0025 scale. A ceiling over **every** step; source 2 of the lowest-of rule. |
| `action_scope` | OPTIONAL | object | §4.3a envelope. Declarative only — see *Relationship to `action_scope`*. |

### `steps`

| Field | Required | Type | Description |
|---|---|---|---|
| `id` | REQUIRED | string | Unique within the playbook. |
| `uses` | OPTIONAL | string | Unit id this step enacts. When present it SHOULD name a `kind: skill` unit. |
| `action` | OPTIONAL | string | Inline description, when no unit exists yet. |
| `depends_on` | OPTIONAL | list | Step ids that must complete successfully first. See default below. |
| `authority_level` | OPTIONAL | string | RFC-0025 scale. Ceiling semantics: at most this level. |
| `escalation` | OPTIONAL | string or list | RFC-0026 trigger(s), e.g. `requires_approval`. A bare string is shorthand for a single-element list. |
| `success_condition` | RECOMMENDED | string | Observable result that confirms the step succeeded. |
| `on_failure` | OPTIONAL | string | One of `abort`, `continue`, `escalate`. Default `abort`. |
| `timeout` | OPTIONAL | string | ISO-8601 duration. Elapsing constitutes failure. |

Either `uses` or `action` MUST be present. `uses` is strongly preferred: it is what stops the
composition from duplicating the unit, and it lets a conformance checker verify that the
referenced unit exists and is `kind: skill`.

`action` exists so a playbook can be authored before every step has a unit — but a checker
MUST report the count of inline steps, since an all-inline playbook has reproduced the
duplication this RFC exists to remove, and (see below) makes the playbook's declared
`action_scope` unverifiable.

#### `depends_on` and execution order

Absent `depends_on` means **the step depends on the step immediately preceding it in
declaration order** — the order in which steps appear in the manifest. Declaration order is
total, so this default is always well-defined, including in a manifest whose explicit
`depends_on` edges form a branching graph. The first step, if it declares no `depends_on`,
depends on nothing.

The resulting graph MUST be acyclic. A validator MUST error on a cycle; this is a check on
the flat `depends_on` graph within one playbook, and is separate from the nesting question in
Open Question 1.

Steps with no dependency path between them MAY be enacted concurrently. An implementation
that does not support concurrency MUST enact steps in declaration order. Either way the
lowest-of rule is applied **per step**, so concurrency cannot widen authority; but an
implementation that enacts concurrently SHOULD record it in the audit trail, because "these
two steps overlapped" changes how a reader interprets a failure.

#### The scope of `abort`

`on_failure: abort` halts **the whole run**, not just the failing step's dependency branch.
Under concurrency this means independent in-flight steps are affected even though no
`depends_on` edge connects them to the failure.

The narrower reading — abort only the dependent branch — was rejected because it makes the
blast radius of a failure depend on a graph the author drew for ordering reasons, not for
safety reasons. An author writing `on_failure: abort` on a verification step means "stop"; they
should not have to reason about which sibling branches happen to be independent.

An implementation MUST NOT begin enacting any step after an abort. For steps already in flight
it MUST do one of: cancel them, or allow them to complete and record the result as
**superseded by abort**. Which one is an implementation choice — cancellation is not always
possible — but the choice MUST be recorded, because "this step ran after the run aborted" is
otherwise indistinguishable from "this step ran normally" in the audit trail.

#### What "failure" means

`on_failure` is meaningless without saying what triggers it. A step has **failed** when any
of the following holds:

1. its enactment terminated abnormally — a non-zero exit, an uncaught exception, a transport
   error;
2. its `success_condition` was evaluated and **not** confirmed;
3. its declared `timeout` elapsed before it completed.

An implementation MUST record which of the three occurred. They are operationally different —
a timeout is not a failed assertion — and an audit trail that flattens them cannot answer why
a run stopped.

#### What `success_condition` is, and is not

`success_condition` is a **prose assertion in the manifest language, not an expression in an
evaluation language.** This RFC deliberately does not define an evaluation mechanism: doing so
would make the protocol responsible for a small programming language, and the assertions that
matter in practice ("the target branch head equals the verified commit") are checked by the
enacting agent against the world, not by the parser against a string.

The consequence is that a conformance checker lints the *presence* and *shape* of a
`success_condition`, never its truth. Implementations MUST record one of three outcomes per
step — `confirmed`, `not_confirmed`, `not_evaluated` — and MUST NOT treat `not_evaluated` as
`confirmed`. The third state is the honest one: it is what an implementation without an
evaluator actually knows, and collapsing it into success is how a run reports green having
verified nothing.

#### What "completed successfully" means for a dependency

`not_evaluated` creates a third state that the failure definition above does not cover: the
step did not fail (trigger 2 requires the condition to have been *evaluated* and not
confirmed), but nothing confirmed it succeeded either. A step is **completed successfully**,
and therefore satisfies a `depends_on` edge, only when it terminated normally, did not time
out, and its `success_condition` is either `confirmed` or absent.

**`not_evaluated` therefore does not satisfy a dependency.** A downstream step MUST NOT be
enacted on an unevaluated predecessor; the run suspends as if the predecessor had escalated.
Treating it as satisfied would let an implementation without an evaluator run an entire
playbook to `commit` while having verified nothing at any step — the same collapse the
three-state rule exists to prevent, arriving through the dependency graph instead.

### Effective authority is the minimum, not the maximum

A step's effective authority is the **lowest** of:

1. the step's own `authority_level`,
2. the playbook's `authority_level`, if declared,
3. the `grant_ceiling` in force for the task type (RFC-0025),
4. any tenant- or customer-scoped ceiling, where the deployment is multi-tenant,
5. the authority granted to the enacting agent.

Sources 3–5 are RFC-0025's existing multi-source minimum, restated here for completeness;
sources 1–2 are what this RFC adds. For source 3, the task type is the one declared by the
step's `uses` unit; if the unit declares none, the playbook's; if neither — including for an
inline step, which has no unit to declare one — no task-type ceiling applies and the minimum
is taken over the remaining sources.

Dropping an undeclared source rather than defaulting it to the most restrictive value is a
deliberate choice, and it is safe for one specific reason: **source 5, the enacting agent's own
granted authority, is never absent.** Every minimum therefore has at least one real bound, and
the cannot-raise property holds regardless of which optional sources are declared. The same
reasoning covers an omitted step-level `authority_level`. What the omission costs is precision,
not safety: a step with no task type is bounded by the agent rather than by the task, which is
looser than intended but never looser than the agent already is.

**Omitting `authority_level` on a step means "no step-level ceiling", not "the lowest level".**
The step remains bounded by sources 2–5, so the *cannot-raise* property below still holds.
Defaulting an omitted level to `observe` was considered and rejected: it would silently break
every step that omits the field, and the predictable author response — declaring `commit`
everywhere to make playbooks run — produces a worse security outcome than the honest absence.
Instead, a validator SHOULD warn when a step omits `authority_level` while its `uses` unit
declares an `action_scope` capable of mutation.

**A playbook can never raise authority** — composing units cannot grant what neither the units
nor the grants allow. That property is what makes a playbook safe to select automatically:
selecting one cannot escalate privilege.

#### `authority_level` and `action_scope` are independent bounds

A step's `authority_level` is a ceiling on *how far the step may go*; the `uses` unit's
`action_scope` bounds *what it may touch*. **Both apply, and neither substitutes for the
other.** A step declaring `observe` while referencing a skill whose `action_scope` permits
writes does not thereby become read-only in effect — it is capped at `observe` *and* still
bounded by that scope; an implementation MUST enforce both.

This matters because the declared level is a **cap, not a description**. Reading it as a
description — "this step only observes, so the referenced skill's write capability is
irrelevant" — is the mistake that turns a governance field into a comment. A validator SHOULD
warn where a step's declared level and its unit's `action_scope` are conspicuously mismatched,
but the enforcement obligation is on the enacting implementation, not the linter.

### Dependent steps and `on_failure: continue`

`on_failure: continue` means **the run continues past this step**, not that steps depending on
it may proceed. A step MUST NOT be enacted unless every step it depends on, transitively,
completed successfully.

Without that rule, `continue` on a verification step is an authority bypass: the abort gate is
removed while the downstream `commit` step still runs, which is exactly the "partially-failed
run reports success" failure the audit trail exists to prevent. With it, `continue` retains
its legitimate use — a best-effort notification step that nothing depends on — and cannot
neutralise a gate.

### What `escalate` does

The two paths into escalation differ in **when** they fire, and the difference is the whole
point of the `escalation` field:

- an **`escalation` trigger is evaluated before the step is enacted**. `requires_approval` on
  a `commit` step gates the promotion; it does not report it afterwards. A trigger that fired
  only post-enactment would be an audit record, not a control.
- **`on_failure: escalate`** fires after enactment, on failure.

Both suspend the step and raise a grant request per RFC-0026. Where a step declares several
`escalation` triggers, **any one firing suspends the step** — they are disjunctive, since each
names an independent reason the step should not proceed unreviewed.

Three outcomes follow:

- **granted** — the step is enacted at the granted level; the run proceeds normally;
- **denied** — the step is treated as having failed with `on_failure: abort`;
- **expired** — likewise treated as `abort`.

The run does not proceed past a suspended step, nor past any step depending on it. A suspended
step's `timeout` clock **pauses for the duration of the suspension** and resumes on a grant:
`timeout` bounds how long the work may take, and a human deliberating for an hour is not the
step running slowly. The request/response coordination mechanism itself is out of scope:
RFC-0026 accepted the semantics and the audit trail while explicitly deferring coordination to
a successor RFC, and this RFC does not pre-empt that decision.

An `escalation` trigger and an `on_failure: escalate` MUST be distinguishable in the audit
trail. "Stopped because the step failed" and "stopped before acting because approval was
required" are different events, and RFC-0026's `grant_request_events.trigger` column already
carries the distinction.

### The orchestrator steers; it does not execute

Where a playbook is enacted by an orchestrator coordinating other agents, the orchestrator
**MUST NOT** perform step work itself. It routes, applies the lowest-of rule, and enforces
stop conditions; each step is enacted by an agent bound by that step's scope.

This separation is what keeps `action_scope` meaningful in a composition. An orchestrator that
both steers and executes accumulates the union of every step's scope, and the per-step bounds
become advisory — the invariant fails silently, since nothing observable changes until the
day something is touched that no step authorised.

**The objection, and why the boundary holds anyway.** The separation has a real cost: the
orchestrator needs execution state to route, and a strict boundary means passing that state
across a process or agent edge rather than reading it directly. In a tightly coupled system
that is genuine overhead, and an implementer may reasonably find it inefficient.

The answer is not to relax the rule but to model the case correctly: an implementation that
must execute locally does so as an orchestrator **plus a co-located enacting agent with its
own declared scope**, in the same process if it likes. The requirement is that the executing
identity is a distinct bound subject, not that it runs on a different machine. That keeps the
union from forming while conceding the deployment reality.

### Stop conditions are not only failures

`on_failure` covers a step that failed. Deployments also stop on conditions that are not
failures: a proposal whose confidence falls below a threshold, a repeated pattern suggesting
weak input data, a cascade guard on correlated runs. RFC-0026 already names
`confidence_below_threshold` as an escalation trigger; a step MAY declare it via `escalation`
without having failed.

### Relationship to `action_scope`

A `kind: skill` unit declares what it may touch. A playbook step that `uses` such a unit
inherits that scope; it does not widen it. Where a playbook declares its own `action_scope`,
it is a **declaration for review, not a grant**, and it is expected to be the union of its
steps' scopes.

That union is computable **only when every step declares `uses` and every referenced unit
declares an `action_scope`.** Either gap breaks it: an inline (`action`) step references no
unit, and a referenced unit that declares no scope contributes nothing to distinguish from
contributing an empty one. In either case a validator MUST report the declared scope as
*unverified* rather than passing it silently — an unverifiable declaration that lints clean is
worse than none, because it reads as checked.

**An inline step is unbounded in scope, not merely widely scoped.** `action_scope` enforcement
attaches to the `uses` unit; with no unit there is nothing to enforce, and only the step's
`authority_level` ceiling applies. That ceiling constrains how far the step may go but not what
it may touch. This is the strongest argument for keeping `action` a transitional affordance:
a playbook of inline steps is governed on one axis out of two.

Where the union *is* computable, a validator SHOULD flag a playbook whose declared scope
exceeds it, because that is the shape of an over-broad request.

### Versioning and lifecycle

Playbooks are ordinary units: `validated`, `supersedes`, and signatures apply unchanged. This
matters for the self-improving case, where an agent proposes a revision. The existing
lifecycle already supports it: the proposal is a new unit that `supersedes` the active one, it
is inert until signed by a key the consumer trusts, and only a human holds that key. No new
mechanism is required — and implementations **MUST NOT** treat an unsigned successor as
selectable. This is a MUST rather than a SHOULD because the alternative makes the human
approval gate decorative: if an unsigned proposal can be selected, an agent revises its own
governing procedure without the signature that was the entire control.

## Alternatives Considered

### `executable` plus a `steps` metadata block

Nothing prevents attaching a `steps` array and per-step `authority_level` to a `kind:
executable` unit, and doing so would deliver per-step governance without a new kind. This is
the strongest argument against this RFC and it deserves a direct answer.

The substantive difference is **`uses`**. `executable` is defined as opaque and
invoke-on-demand; its contents are outside the protocol by construction. A playbook's steps
are *references to other units*, which is what lets a conformance checker resolve the
reference, confirm the target exists and is `kind: skill`, compare declared authority against
the target's `action_scope`, and detect the duplication described above. That checking is the
whole benefit, and it requires the composition to be first-class rather than metadata attached
to a black box.

The honest converse: **if `uses` were dropped and steps were purely inline prose, this RFC
would not justify a new kind** — `executable` with a metadata block would be sufficient, and
preferable for being additive to an existing kind. The new kind earns its place only insofar
as steps reference units.

### Widening `kind: skill` to permit composition

Rejected as a compatibility break: existing consumers select `skill` units expecting a single
governed action, and a `skill` that silently orchestrated others would change the meaning of a
field already in production use.

## Complete Example

```yaml
kcp_version: "0.29"
project: example-ops
units:
  - id: run-test-suite
    kind: skill
    path: skills/run-test-suite/SKILL.md
    intent: "How do I run the full test suite and read the result?"
    scope: project
    audience: [agent]
    action_scope:
      tools: [bash]
      paths: ["test/**"]

  - id: open-promotion-request
    kind: skill
    path: skills/open-promotion-request/SKILL.md
    intent: "How do I open a promotion request for a verified build?"
    scope: project
    audience: [agent, operator]
    action_scope:
      tools: [git, forge-cli]
      paths: [".forge/**"]

  - id: complete-promotion
    kind: skill
    path: skills/complete-promotion/SKILL.md
    intent: "How do I complete an approved promotion request?"
    scope: project
    audience: [agent, operator]
    action_scope:
      tools: [git, forge-cli]
      paths: [".forge/**"]
      capabilities: [release-promotion]

  - id: promote-release
    kind: playbook
    path: playbooks/promote-release.md
    intent: "How do we promote a verified build to production?"
    scope: project
    audience: [agent, operator]
    validated: "2026-07-27"
    authority_level: commit
    steps:
      - id: verify
        uses: run-test-suite
        authority_level: observe
        success_condition: "zero failures reported by the suite"
        on_failure: abort
      - id: prepare-change
        uses: open-promotion-request
        depends_on: [verify]
        authority_level: prepare
        success_condition: "request URL returned"
        on_failure: abort
      - id: promote
        uses: complete-promotion
        depends_on: [prepare-change]
        authority_level: commit
        escalation: requires_approval
        success_condition: "target head equals the verified commit"
        on_failure: escalate
```

Every `uses` in this example resolves to a unit declared above — which is the property a
conformance checker enforces, and which an earlier draft of this RFC violated by referencing
`complete-promotion` without declaring it.

## Open Questions

1. **Should `steps` permit nesting** — a step whose `uses` names another playbook? It is
   natural and it introduces cycles. A depth limit and a cross-playbook cycle check would be
   required, and the cascade-guard problem is the same one delegation chains already face
   (§3.4). **Until it is resolved, nesting is forbidden rather than merely unspecified**: a
   validator MUST error when `uses` names a `kind: playbook` unit (see Conformance). An earlier
   draft left this as a SHOULD-warning, which is not a guard — a lenient implementation could
   have nested playbooks whose combined `depends_on` graph the per-playbook cycle check never
   sees, defeating both the acyclicity rule and, through it, the lowest-of computation.
2. **Should `uses` pin a version?** A step inherits its unit's `action_scope` by id, not by
   signed hash. If the unit is later superseded with a broader scope, an already-approved
   playbook silently widens without re-review — a lifecycle path around the approval gate, and
   the more serious for being invisible. Candidate answers: resolve `uses` against the unit
   version current at signing time; or re-verify at enaction and escalate on widening. Both
   have costs and neither belongs in this RFC without an implementation behind it.
3. **Where does per-step evidence live?** RFC-0026 added `grant_request_events`. A signed
   decision per step is the natural companion, but whether it belongs in this RFC or a
   successor is unresolved.
4. **Should a playbook be selectable by an agent at all**, or only by an orchestrator? This RFC
   states that playbooks are gated exactly as `knowledge` and `skill` are, which permits
   intent-driven selection. The fail-closed alternative is `invocation: explicit` by default
   unless the manifest grants otherwise. The two readings are not reconciled here; the RFC
   currently takes the permissive one, and it is genuinely open whether it should.
5. **Where does a confidence threshold live?** RFC-0026 names `confidence_below_threshold` as a
   trigger, and this RFC lets a step declare it — but no field carries the threshold value, so
   each implementation would supply its own. Either a step-level field or a task-type binding
   is needed before the trigger is portable.
6. **Should budget be a ceiling alongside authority?** §4.3a.1 specifies `action_scope.spend`
   for a single unit; a playbook spanning many steps needs a run-level ceiling, and exhausting
   it mid-run is neither a failure nor an authority violation. The protocol has no vocabulary
   for that state.
7. **How should temporal drift across steps be handled?** A playbook spanning hours may reach
   a later step whose inputs changed after an earlier step read them. Re-verifying every
   precondition at every step is expensive; ignoring the problem is how a run produces a
   confidently wrong result from stale inputs.
8. **Should `authority_level` be re-evaluated mid-run?** This RFC computes effective authority
   per step at enactment time, which handles a ceiling that changes between steps. It does not
   handle a ceiling revoked *during* a long-running step.

## Relationship to Other RFCs

- **RFC-0025** supplies the authority scale and the lowest-of rule. This RFC gives them a step
  to attach to. Without RFC-0025 a playbook step could only say *that* it needs approval, not
  *how much* authority it exercises.
- **RFC-0026** supplies escalation triggers and the audit table. A step's `escalation` field is
  that vocabulary, positioned.
- **RFC-0014/0020/0022** compose *manifests*. This composes *units*. They are orthogonal: a
  playbook may draw on units from a composed manifest, and manifest-composition integrity
  applies to it unchanged.
- **RFC-0018 §2.1** draws the map/territory boundary: the render pipeline sanitises manifest
  metadata (the map) and cannot lint the content of referenced files (the territory). That
  boundary applies to playbooks with more force than to other kinds, because a step's `uses`
  reference is map-level and checkable while the referenced procedure's *content* is not. A
  conformance checker can confirm that `promote` uses a declared `kind: skill` unit; it cannot
  confirm the unit's prose does what its `intent` claims. An earlier draft of this RFC cited
  this invariant to the composition RFCs and paraphrased it as "signature covers the territory,
  not the map" — wrong source, and inverted.
- **A2A.** SPEC.md §related-work already positions KCP against agent-to-agent transport. This
  RFC deliberately does not specify how agents communicate — only what the governed artifact
  *is*. A playbook can be executed by one agent or by five over A2A; the protocol should not
  care.

## Conformance

Normative strength below matches the Design section; where the design states a requirement as
a MUST, conformance does not weaken it to a SHOULD.

**Parsing**

- A parser MUST treat an unknown `kind` as `knowledge` (§4.3a). Implementations predating this
  RFC therefore degrade a playbook to inert reference material — safe by default.

**Validation**

- A validator MUST error when a `kind: playbook` unit declares no `steps`, or declares an
  empty list.
- A validator MUST error when a step declares neither `uses` nor `action`.
- A validator MUST error when step ids are not unique within the playbook.
- A validator MUST error when the `depends_on` graph contains a cycle, or names a step id that
  does not exist.
- A validator MUST report the number of inline (`action`) steps.
- A validator MUST report a playbook's declared `action_scope` as *unverified* when any step is
  inline, or when any referenced unit declares no `action_scope`, rather than reporting it as
  checked.
- A validator MUST error when `uses` names a unit that does not resolve within the manifest or
  its composed manifests. This is an error rather than a warning because a resolvable `uses` is
  the entire justification for a distinct kind (see *Alternatives Considered*): a dangling
  reference that lints clean reduces the playbook to `executable` with worse ergonomics.
- A validator MUST error when `uses` names a `kind: playbook` unit, pending Open Question 1.
- A validator SHOULD warn when `uses` names a unit that resolves but is not `kind: skill`.
- A validator SHOULD warn when a step omits `authority_level` while its `uses` unit declares an
  `action_scope` capable of mutation.
- A validator SHOULD warn when a computable step-scope union is narrower than the playbook's
  declared `action_scope`.

**Enaction**

- An enacting implementation MUST apply the lowest-of rule per step and MUST NOT allow a
  playbook to raise effective authority above what the enacting agent already holds.
- An enacting implementation MUST enforce both the step's `authority_level` and the `uses`
  unit's `action_scope`; neither substitutes for the other.
- An enacting implementation MUST NOT enact a step unless every step it transitively depends on
  completed successfully, regardless of those steps' `on_failure`.
- An enacting implementation MUST record, per step, which failure condition occurred
  (abnormal termination, unconfirmed `success_condition`, or timeout) and MUST record
  `success_condition` outcome as one of `confirmed`, `not_confirmed`, `not_evaluated`.
- An enacting implementation MUST NOT treat `not_evaluated` as `confirmed`, and MUST NOT treat
  a `not_evaluated` predecessor as satisfying a `depends_on` edge.
- An enacting implementation MUST evaluate a step's `escalation` triggers before enacting it,
  and MUST suspend the step when any one of them fires.
- On abort, an enacting implementation MUST NOT begin any further step, and MUST record
  in-flight steps as either cancelled or superseded by the abort.
- Where a playbook is enacted by an orchestrator, the orchestrator MUST NOT enact step work
  itself; a co-located enacting agent with its own declared scope satisfies this.

  Unlike every other requirement here, this one binds an actor rather than a field: nothing in
  the manifest declares "orchestrator", so a validator cannot check it and only a deployment
  can honour it. It is stated as a MUST anyway because the property it protects — per-step
  `action_scope` remaining meaningful under composition — is not recoverable once violated, and
  because an implementation reading this RFC is the only party positioned to observe the
  violation. A future RFC that gives the enacting role a declared identity would make it
  checkable; that is a gap, and naming it is better than downgrading the rule to match the
  tooling.
- An implementation MUST NOT treat an unsigned successor playbook as selectable.

## Backward Compatibility

Additive. `kind` is OPTIONAL with default `knowledge`, and §4.3a instructs parsers to ignore
unknown values. A v0.28 consumer reading a v0.29 manifest containing playbooks sees inert
knowledge units — it will not enact them, which is the correct degradation.

No existing field changes meaning. `executable` is unaffected and remains correct for opaque
runnable artifacts.

## Changelog

- 2026-07-27 — initial draft.
- 2026-07-27 — revised after adversarial review (four-lens critique plus cross-model review).
  Defined step failure, `success_condition` semantics and the `not_evaluated` outcome, the
  `depends_on` default under branching, cycle rejection, and concurrency. Added the
  dependent-step rule closing the `on_failure: continue` bypass, `escalate` resolution
  outcomes, `escalation` cardinality, the task-type source for `grant_ceiling`, the
  playbook-level field table, and the `authority_level`/`action_scope` independence rule.
  Upgraded the orchestrator-non-execution and unsigned-successor rules to MUST and answered
  the practicality objection to the former. Added *Alternatives Considered*, including the
  case where this RFC would not be justified. Declared `complete-promotion` in the Complete
  Example, which the draft referenced but never defined. Removed unverifiable statistics and
  the unnamed-implementation citation, replacing them with an explicit provenance note.
- 2026-07-27 — second review round against the revised text (26 findings; none of round one's
  recurred). Defined the scope of `abort` and in-flight step handling under concurrency; ruled
  that `not_evaluated` does not satisfy a `depends_on` edge, closing a hole the three-state
  outcome rule had opened; specified `escalation` triggers as pre-enactment and disjunctive;
  paused the `timeout` clock during suspension. Upgraded unresolvable `uses` to a MUST error
  and forbade playbook-nesting outright rather than leaving it unspecified. Stated why an
  undeclared ceiling source is dropped rather than defaulted (source 5 is never absent) and why
  an inline step is scope-*unbounded*, not merely wide. Added open questions on version-pinning
  `uses` and on where a confidence threshold lives. Corrected the map/territory citation: the
  invariant is RFC-0018 §2.1, not the composition RFCs, and the draft had it inverted.
