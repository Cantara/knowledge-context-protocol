# RFC-0027: Playbooks — Governed Composition of Units

**Status:** Draft
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-07-27
**Related:** RFC-0025 (Authority Level and Grant Ceiling) — this RFC gives its ceilings a
per-step attachment point. RFC-0026 (Escalation and Grant Requests) — likewise.
RFC-0009 (Visibility and Authority Declarations). SPEC.md §4.3a (`kind`), §3.4 (delegation).
**Spec:** [SPEC.md](./SPEC.md) (current: v0.28)

---

## What This RFC Proposes

A seventh `kind`: `playbook` — an ordered composition of units, governed **per step**.

```yaml
- id: promote-release
  kind: playbook
  intent: "How do we promote a verified build to production?"
  scope: project
  audience: [agent, operator]
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

In deployments we have observed, the orchestrations that matter are implemented as shell
scripts, CI definitions, or bespoke chain files. Two consequences follow, and both were
measured in a production library of ~670 units:

1. **The composition duplicates the units.** A chain step says *"run the test suite and
   confirm all tests pass"* in prose while a `kind: skill` unit documenting exactly that
   already exists. They then drift independently, and nothing detects the divergence.
2. **The composition is better governed than the units it orchestrates.** The chain format
   in that deployment carried `depends_on`, `success_condition`, `on_failure` and a
   human-confirmation flag per step — while none of the ~160 procedural units it drew on
   declared a verification step at all. The orchestration layer had reinvented, informally,
   precisely the governance the protocol offers formally.

A protocol that can describe the parts but not the assembly leaves the assembly to be
re-specified, badly, by every deployment.

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

### `steps`

| Field | Required | Type | Description |
|---|---|---|---|
| `id` | REQUIRED | string | Unique within the playbook. |
| `uses` | OPTIONAL | string | Unit id this step enacts. When present it SHOULD name a `kind: skill` unit. |
| `action` | OPTIONAL | string | Inline description, when no unit exists yet. |
| `depends_on` | OPTIONAL | list | Step ids that must complete first. Absent means "after the previous step". |
| `authority_level` | OPTIONAL | string | RFC-0025 scale. Ceiling semantics: at most this level. |
| `escalation` | OPTIONAL | string | RFC-0026 trigger, e.g. `requires_approval`. |
| `success_condition` | RECOMMENDED | string | Observable result that confirms the step succeeded. |
| `on_failure` | OPTIONAL | string | One of `abort`, `continue`, `escalate`. Default `abort`. |

Either `uses` or `action` MUST be present. `uses` is strongly preferred: it is what stops the
composition from duplicating the unit, and it lets a conformance checker verify that the
referenced unit exists, is `kind: skill`, and declares an `action_scope` compatible with the
step's `authority_level`.

`action` exists so a playbook can be authored before every step has a unit — but a checker
SHOULD report the count of inline steps, since an all-inline playbook has reproduced the
duplication this RFC exists to remove.

### Effective authority is the minimum, not the maximum

A step's effective authority is the **lowest** of:

1. the step's own `authority_level`,
2. the playbook's `authority_level`, if declared,
3. the `grant_ceiling` in force for the task type (RFC-0025),
4. any tenant- or customer-scoped ceiling, where the deployment is multi-tenant,
5. the authority granted to the enacting agent.

This is RFC-0025's "lowest-of" rule applied within a composition. **A playbook can never
raise authority** — composing units cannot grant what neither the units nor the grants
allow. That property is what makes a playbook safe to select automatically: selecting one
cannot escalate privilege.

An existing implementation of this model computes precisely
`min(policy_ceiling, tenant_ceiling, step_cap, playbook_max)` over a five-level ladder
(`observe → explain → suggest → prepare → commit`) — independently arriving at both the
ordinal scale RFC-0025 specifies and the playbook-level ceiling this RFC proposes. The
tenant ceiling in that implementation is the reason source 4 is listed here: a governance
model that binds per customer cannot express itself through task-type grants alone.

### The orchestrator steers; it does not execute

Where a playbook is enacted by an orchestrator coordinating other agents, the orchestrator
**SHOULD NOT** perform step work itself. It routes, applies the lowest-of rule, and enforces
stop conditions; each step is enacted by an agent bound by that step's scope.

This separation is what keeps `action_scope` meaningful in a composition. An orchestrator
that both steers and executes accumulates the union of every step's scope, and the
per-step bounds become advisory. The same deployment referenced above states the invariant
directly: the orchestrator owns the steering — routing, lowest-of, stop-triggers — and
never the execution.

### Stop conditions are not only failures

`on_failure` covers a step that failed. Deployments also stop on conditions that are not
failures: a proposal whose confidence falls below a threshold, a repeated pattern
suggesting weak input data, a cascade guard on correlated runs. RFC-0026 already names
`confidence_below_threshold` as an escalation trigger; a step MAY declare it via
`escalation` without having failed.

Implementations SHOULD distinguish the two in the audit trail. "Stopped because the step
failed" and "stopped because the evidence was too thin to proceed at this authority" are
different events, and conflating them makes the second invisible — which is precisely the
case a governance model exists to surface.

### Relationship to `action_scope`

A `kind: skill` unit declares what it may touch. A playbook step that `uses` such a unit
inherits that scope; it does not widen it. Where a playbook declares its own `action_scope`,
it is the **union** of its steps' scopes and serves as a declaration for review — not as a
grant. A conformance checker SHOULD flag a playbook whose declared scope exceeds the union
of the scopes it actually draws on, because that is the shape of an over-broad request.

### Versioning and lifecycle

Playbooks are ordinary units: `validated`, `supersedes`, and signatures apply unchanged.
This matters for the self-improving case, where an agent proposes a revision. The existing
lifecycle already supports it: the proposal is a new unit that `supersedes` the active one,
it is inert until signed by a key the consumer trusts, and only a human holds that key. No
new mechanism is required — but implementations SHOULD NOT treat an unsigned successor as
selectable, or the human gate is decorative.

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

  - id: promote-release
    kind: playbook
    path: playbooks/promote-release.md
    intent: "How do we promote a verified build to production?"
    scope: project
    audience: [agent, operator]
    validated: "2026-07-27"
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

## Open Questions

1. **Should `steps` permit nesting** — a step whose `uses` names another playbook? It is
   natural and it introduces cycles. A depth limit and a cycle check would be required, and
   the cascade-guard problem is the same one delegation chains already face (§3.4).
2. **Is `on_failure: continue` safe to allow?** It is genuinely useful (a best-effort
   notification step) and it is also how a partially-failed run reports success — the exact
   failure this protocol's audit trail exists to prevent. A checker warning may be enough.
3. **Where does per-step evidence live?** RFC-0026 added `grant_request_events`. A signed
   decision per step is the natural companion, but whether it belongs in this RFC or a
   successor is unresolved.
4. **Should a playbook be selectable by an agent at all**, or only by an orchestrator? The
   fail-closed reading is that a playbook renders as a pointer with `invocation: explicit`
   unless the manifest grants otherwise — matching `kind: skill` in §4.3a.
5. **Should budget be a ceiling alongside authority?** One observed implementation meters
   operations in units against a session ceiling. A playbook that exhausts budget mid-run
   is neither a failure nor an authority violation, and the protocol currently has no
   vocabulary for it.
6. **How should temporal drift across steps be handled?** The same implementation pins
   inputs to a data timestamp and checks for drift. A playbook spanning hours may reach a
   later step whose inputs changed after an earlier step read them. Re-verifying every
   precondition at every step is expensive; ignoring the problem is how a run produces a
   confidently wrong result from stale inputs.

## Relationship to Other RFCs

- **RFC-0025** supplies the authority scale and the lowest-of rule. This RFC gives them a
  step to attach to. Without RFC-0025 a playbook step could only say *that* it needs
  approval, not *how much* authority it exercises.
- **RFC-0026** supplies escalation triggers and the audit table. A step's `escalation` field
  is that vocabulary, positioned.
- **RFC-0014/0020/0022** compose *manifests*. This composes *units*. They are orthogonal:
  a playbook may draw on units from a composed manifest, and the composition-integrity
  invariant (signature covers the territory, not the map) applies unchanged.
- **A2A.** SPEC.md §related-work already positions KCP against agent-to-agent transport.
  This RFC deliberately does not specify how agents communicate — only what the governed
  artifact *is*. A playbook can be executed by one agent or by five over A2A; the protocol
  should not care.

## Conformance

- A parser MUST treat an unknown `kind` as `knowledge` (§4.3a). Implementations predating
  this RFC therefore degrade a playbook to inert reference material — safe by default.
- A validator SHOULD error when a `kind: playbook` unit declares no `steps`.
- A validator SHOULD error when a step declares neither `uses` nor `action`.
- A validator SHOULD warn when `uses` names a unit that does not exist, is not
  `kind: skill`, or whose `action_scope` cannot satisfy the step's `authority_level`.
- A validator SHOULD report the number of inline (`action`) steps, since these reintroduce
  the duplication the kind exists to remove.
- An enacting implementation MUST apply the lowest-of rule and MUST NOT allow a playbook to
  raise effective authority above what the enacting agent already holds.

## Backward Compatibility

Additive. `kind` is OPTIONAL with default `knowledge`, and §4.3a instructs parsers to ignore
unknown values. A v0.28 consumer reading a v0.29 manifest containing playbooks sees inert
knowledge units — it will not enact them, which is the correct degradation.

No existing field changes meaning. `executable` is unaffected and remains correct for
opaque runnable artifacts.

## Changelog

- 2026-07-27 — initial draft.
