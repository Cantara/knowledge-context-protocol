# RFC-0030: Playbook-Level Prohibitions

**Status:** Draft
**Version target:** 0.32 (§4.3b addition)
**Related:** RFC-0029 (Skill-Level Authority and Prohibitions) — this RFC extends `action_scope.deny` from `kind: skill` to `kind: playbook`, composing by union. RFC-0027 (Playbooks) — the playbook-level `action_scope` was introduced there as declarative-only; this RFC makes its `deny` sub-object the first *enforced* part of it. RFC-0025 (Authority Level and Grant Ceiling) — the union-of-denies is the scope-axis mirror of the lowest-of rule on the authority axis. SPEC.md §4.3a (`action_scope`, `deny`), §4.3b (`steps`, scope verifiability), §3.13 (`grant_ceiling`).

## What This RFC Proposes

One addition and one clarification, both fail-closed and backward-compatible (absent = today's behaviour):

1. **`action_scope.deny` on a `kind: playbook` unit** — a blanket prohibition over **every step** of the playbook. The effective denylist for a step is the **union** of the playbook's `deny` and the `deny` of the skill the step `uses`. A match in either denies, overriding any allow, exactly as in §4.3a (v0.31).

2. **A deny is never grantable** (amends the §4.3a v0.31 escalation sentence, for skill-level and playbook-level `deny` alike) — a deny-hit raises a notify-only *prohibited-attempt* event; no response to it enacts the refused action. The only way past a `deny` is a new, reviewed, signed manifest version that no longer declares it.

```yaml
- id: pb-002-gdpr-sletting
  kind: playbook
  authority_level: commit
  action_scope:
    deny:                            # enforced against every step, whichever skill runs
      paths: ["legal/hold/**"]       # a deletion playbook that can never touch legal hold
      tools: [transfer_ownership]
  steps:
    - id: identifiser
      uses: dokument-og-bevisagent
      authority_level: observe
    - id: slett
      uses: sletteagent
      authority_level: commit
      escalation: requires_approval
```

## The Problem

### Prohibitions exist at the process level, and the process has nowhere to say them

RFC-0029 gave a `kind: skill` its own `deny` — *this agent* never publishes, never grades. But real governance also constrains the **process**: "*this entire deletion playbook may never touch material under legal hold — whichever skill runs, in whatever order, including skills added to it later.*" Today that invariant can only be expressed by repeating the prohibition on every skill the playbook uses — which fails exactly when it matters: a step is re-pointed at a different skill, or a new step is added, and the blanket prohibition silently has a hole. A process-level constraint should be declared once, on the playbook, and hold for every step structurally.

### The playbook's `action_scope` is declarative; its most enforceable part is going unenforced

§4.3b deliberately made a playbook's `action_scope` a *declaration for review, not a grant*: its allow side is expected to be the union of its steps' scopes, that union is often not computable (inline steps, scope-less units), and enforcing an uncomputable allowlist would be unsound. But that reasoning does not transfer to `deny`. A denylist needs no union over steps to be sound — it only ever *removes* permissions, so it can be enforced against each step independently, computable-union or not. The v0.29 design left the one enforceable sub-object of the playbook scope inside a field marked "declarative", and v0.31 then shipped `deny` semantics one level down. This RFC closes that seam.

### Inline steps are scope-unbounded; a playbook `deny` is the first bound they can have

§4.3b is explicit that an inline (`action`) step is *unbounded in scope, not merely widely scoped* — `action_scope` enforcement attaches to the `uses` unit, and there is none. A playbook-level `deny` is enforceable at the orchestration gate rather than the unit, so it applies to inline steps too. It cannot make an inline step well-bounded (nothing enumerates what it *may* touch), but it puts the first hard edge on what it may *never* touch — strictly better than the current one-axis governance.

### Provenance of the claims in this section

Surfaced by the same downstream KCP consumer as RFC-0029, modelling a governed agent library and its playbooks: the playbook definitions carry process-wide "absolutte forbud" (blanket prohibitions independent of which agent performs a step) that currently have to be duplicated onto every skill or carried as prose.

## Design

### `action_scope.deny` on `kind: playbook`

| Field | Requiredness | Type | Semantics |
|---|---|---|---|
| `action_scope.deny` | OPTIONAL | object | The §4.3a `deny` shape: `{tools?, paths?, capabilities?}`. **Normative for enactment**, unlike the rest of the playbook `action_scope` envelope: a conformance checker MUST apply it to every step of the playbook, inline steps included. |

### Composition: union of denies

The effective denylist for a step is, per dimension (`tools`, `paths`, `capabilities`):

```
effective_deny(step) = playbook.action_scope.deny ∪ uses(step).action_scope.deny
```

A requested token matching **either** source is denied, overriding any allow, deny-first, exactly as §4.3a (v0.31) specifies for the skill-level list. When both sources match, the trace SHOULD name both; otherwise the matching source is the **binding source**, named in the decision trace — the same auditing shape as the `grant_ceiling` minimum's binding source (§3.13).

Union is the only sound composition. It is **monotonic**: adding a deny source can only refuse more, never less — the scope-axis mirror of the authority-axis lowest-of rule, and the same property that makes both safe to compose automatically without human review of each combination. Any composition that let a playbook *remove* a skill's deny would turn composition into a bypass.

### The declarative/normative split, made explicit

This RFC changes the status of exactly one sub-object. After it:

- `action_scope.{tools, paths, capabilities}` on a playbook — **declarative**, unchanged (§4.3b scope-verifiability rules apply as today, including the *unverified* report when the union is not computable).
- `action_scope.deny` on a playbook — **normative**, enforced per step.

The asymmetry is principled, not incidental: an enforced playbook-level *allow* would be a grant that could widen a step beyond its skill's scope, and is unsound whenever the steps' union is uncomputable. An enforced *deny* can only narrow, and needs no union to be sound. The narrow direction is enforceable; the widening direction stays declarative.

### Interaction with existing rules

- **§4.3a skill `deny`** — unchanged; playbook `deny` composes with it by union and can never relax it.
- **§4.3b scope verifiability** — unchanged for the allow side. A playbook `deny` does not make the declared allow scope *verified*, and a validator MUST NOT treat it as doing so.
- **§4.3c `load_eligible`** — unaffected; eligibility gates loading, `deny` gates what an enacted step may do.
- **§4.17 / RFC-0025 authority** — independent axes, both apply: an action must pass the effective authority ceiling **and** not match the effective deny.
- **§3.14 escalation** — clarified by this RFC; see *A deny is never grantable* below.
- **Orchestrator** — the RFC-0027 orchestrator steers rather than executes, but the conformance gate it consults MUST fold the playbook `deny` into every per-step adjudication, including steps enacted after `on_failure: continue` or resumption from escalation.

### A deny is never grantable

§4.3a (v0.31) says an action a `deny` holds "SHOULD raise a grant request rather than fail silently," by analogy with over-threshold `spend`. The analogy is half right, and this RFC makes the halves explicit — because for `spend`, a granted request **changes the outcome**, and if a deny worked the same way it would stop being a prohibition and become a high-friction allow.

**Normative clarification (amends §4.3a, applies to skill-level and playbook-level `deny` alike):**

- An action held by a `deny` is **refused, finally**. The escalation raised is a **prohibited-attempt notification** — an auditable §17 event, not a request for permission.
- No response to that notification — human or otherwise — enacts the refused action. A grant resolved against a prohibited-attempt event records acknowledgement; it **MUST NOT** cause enactment.
- The only way past a `deny` is to **change the manifest**: a new unit version that no longer declares the prohibition, via the ordinary lifecycle — `supersedes`, review, signature. Governance changes go through authoring, never through a runtime exception.

**Worked contrast — the two escalations side by side:**

*Grantable (spend, §4.3a.1):* a step in `pb-002-gdpr-sletting` calls a paid verification API; the run's `spend` ceiling is 50 NOK and the call costs 120 NOK. A grant request is raised, the playbook owner approves 120 NOK, **the call proceeds**. The ceiling was a *threshold* — human judgment can move it per-case.

*Never grantable (deny, this RFC):* the `slett` step attempts to delete `legal/hold/2025-brekstad/**`, matching the playbook's `deny.paths: ["legal/hold/**"]`. The step is **refused**; a prohibited-attempt event is emitted naming the playbook as binding source; the playbook owner sees it. Whatever the owner clicks, **the deletion does not happen**. If legal hold has genuinely been lifted, the owner ships a new playbook version without that path in `deny` — reviewed, signed, superseding — and *re-runs*. The prohibition was a *boundary* — human judgment can redraw it, but only where boundaries are drawn: in the manifest.

**Why the hard line, concretely:** the prohibited-attempt event exists because *repeated attempts to do forbidden things is a governance signal* — an agent that keeps hitting `transfer_ownership` denials is misconfigured, compromised, or probing. That signal is only trustworthy if the answer at the gate is always no. The moment one deny-hit can be waved through, every deny-hit becomes a negotiation, the audit log stops meaning "this could not happen" and starts meaning "this usually didn't happen" — and the compliance claim built on it ("structurally cannot touch legal hold") is gone.

### Validator

- `deny` on a playbook must be a valid §4.3a deny shape → error otherwise.
- An empty `deny` object prohibits nothing → the §4.3a "lists nothing" lint applies.
- A step whose used skill's allowlist is entirely contained in the effective deny for some dimension is self-nullified on that dimension → warning (the step reads enactable but cannot act).
- `deny` on the playbook plus the existing §4.3b rules compose without change: an eligible playbook still may not have inline steps, so enforced-deny-on-inline-steps arises only on ineligible (reviewed, human-run) playbooks — where it is still worth checking.

### Migration / backward compatibility

`deny` on a playbook is OPTIONAL; absent means today's behaviour. No existing manifest changes meaning — under v0.29–v0.31 rules the field was legal but declarative, so the only change a conformant v0.32 runtime introduces is *refusing* actions the manifest already said were prohibited. Pre-0.32 parsers ignore it and enact ungoverned on this dimension: as with every §4.3a governance field, publishers should not rely on it as a hard boundary until the consuming runtime is known conformant — with the added caveat that under v0.29–v0.31 a playbook `deny` *read* as reviewable documentation while enforcing nothing, which is precisely the gap this RFC closes.

## Alternatives Considered

- **Deny reason-codes** (machine-readable category per prohibition) — deferred; additive later without breaking this shape.
- **Grantable deny (escalate-past-prohibition)** — rejected, and the v0.31 §4.3a wording that invited the reading is amended by this RFC (*A deny is never grantable*). The future §3.14 active-coordination RFC inherits the notify-only semantics for deny-hits; it may define the request/response surface, but not a response that enacts.
- **Enforcing the whole playbook `action_scope`** — rejected; the allow side is unsound to enforce when the steps' union is uncomputable (§4.3b), and §4.3b's declarative framing remains correct for it.

## Acceptance

1. A step whose skill allows `transfer_ownership` under a playbook with `deny.tools: [transfer_ownership]` is **denied**, with the playbook named as binding source in the trace.
2. Union: a step is refused on a token matched only by the skill's `deny` and on a token matched only by the playbook's `deny`; a token matched by both names both sources.
3. An inline (`action`) step on an ineligible playbook is refused an action matching the playbook `deny` — the first scope bound an inline step has ever had.
4. Validator: malformed playbook `deny` → error; empty `deny` → lint; skill allowlist ⊆ effective deny per dimension → self-nullified-step warning; declared allow scope still reported *unverified* where the union is not computable.
5. A deny-hit emits a prohibited-attempt event naming the binding source; resolving that event with a grant records acknowledgement and does **not** enact the action — the refused operation is verifiably absent from the subsequent trace. An over-threshold `spend` grant on the same playbook, by contrast, does enact — the two escalation outcomes are distinguishable in the audit log.
6. Round-trips through the manifest JSON projection (MCP bridge) with the field present.
