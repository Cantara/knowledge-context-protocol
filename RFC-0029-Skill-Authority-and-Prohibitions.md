# RFC-0029: Skill-Level Authority and Prohibitions

**Status:** Draft
**Version target:** 0.31 (§4.3a addition)
**Related:** RFC-0025 (Authority Level and Grant Ceiling) — this RFC makes a `kind: skill` unit a *source* in the lowest-of, and gives the scale a per-skill attachment point. RFC-0027 (Playbooks) — a step's `uses` skill now contributes its own ceiling and prohibitions to the step's effective authority. SPEC.md §4.3a (`kind`, `action_scope`), §3.13 (`authority_level`, `grant_ceiling`), §4.17 (permission values).

## What This RFC Proposes

Two additions to `kind: skill`, both fail-closed and backward-compatible (absent = today's behaviour):

1. **`authority_level` on a `kind: skill` unit** — the skill's *own* capability ceiling, on the RFC-0025 scale (`observe < explain < suggest < prepare < commit`). It becomes an additional **source in the `grant_ceiling` minimum** (§3.13). An agent whose skill is declared at `suggest` can never be driven to `commit`, no matter what a playbook step, task-type, or tenant grant permits — the lowest still wins.

2. **`action_scope.deny`** — an explicit **negative scope** with the same `{tools, paths, capabilities}` shape as the existing allowlist, expressing "**never**, regardless of any allow". A match in `deny` **denies**, overriding any allow (including a wider grant), fail-closed.

```yaml
- id: dokument-og-bevisagent
  kind: skill
  authority_level: prepare            # this agent's own ceiling — a source in the lowest-of
  load_eligible: true
  action_scope:
    tools: [finn_gjeldende_kilde, read]
    paths: ["evidence/**"]
    deny:                           # explicit prohibitions — override any allow
      tools: [publish, set_status]    # "aldri sette formell status" — structural, not prose
      capabilities: [llm_quality_grade]
```

## The Problem

### An agent's own ceiling is unrepresentable; the lowest-of is missing a source

RFC-0025's `grant_ceiling` resolves the effective authority as the **minimum** across *policy / customer / playbook / task-type*. But a real agent library also has **per-agent** ceilings — "the Regelverksagent may only *suggest*, never *commit*". Today that limit can only be enforced by never *granting* the agent more elsewhere — an implicit, un-declared, un-auditable constraint. The agent's own ceiling should be a first-class **source** in the minimum, so the constraint is declared once, on the agent, and holds everywhere the agent is used.

### `action_scope` is allowlist-only; "never X" cannot be said

`action_scope` (§4.3a) is a positive allowlist (`tools`, `paths`, `capabilities`). There is no way to declare a **prohibition** that survives a wider grant. Real agent definitions carry hard "absolutte forbud" — *"never give a holistic LLM quality grade"*, *"never set a formal status"*, *"never delete"*. Encoded as prose they are unenforceable; encoded as *absence* from an allowlist they are fragile (a later, broader allow silently re-permits them). A negative scope that **overrides** allow makes the prohibition structural.

### Provenance of the claims in this section

The two gaps were surfaced by a downstream KCP consumer modelling a governed agent library as `kind: skill` units: each agent had (a) a distinct maximum authority and (b) an explicit list of prohibitions, and neither could be expressed against the current §4.3a schema — the authority had to live outside the manifest and the prohibitions became comments.

## Design

### `authority_level` on `kind: skill`

| Field | Requiredness | Type | Semantics |
|---|---|---|---|
| `authority_level` | OPTIONAL | string | An RFC-0025 scale token. The skill's own ceiling. When present, it is **source (5)** in the `grant_ceiling` minimum for any step or invocation that `uses` this skill. Absent → the skill contributes no ceiling (today's behaviour). A token off the manifest's `authority_level_scale` is **fail-closed**: it ranks below everything (blocks every action). |

The effective authority for a step that `uses` a skill is the minimum over: (1) step `authority_level`, (2) playbook `authority_level`, (3) task-type `grant_ceiling`, (4) tenant/agent grant, **(5) the skill's own `authority_level`** — this RFC adds (5). A skill can only ever *lower* the effective ceiling; it can never raise it. That monotonicity is what keeps it safe to select automatically.

### `action_scope.deny`

| Field | Requiredness | Type | Semantics |
|---|---|---|---|
| `action_scope.deny` | OPTIONAL | object | Same shape as `action_scope` itself: `{tools?, paths?, capabilities?}`. A requested tool/path/capability that matches `deny` is **denied**, and this denial **overrides any allow** — including a wider `grant_ceiling` or an allowlist match. Evaluation is deny-first: *deny* is checked before *allow*; a `deny` match short-circuits to refused. |

Naming follows the access-control convention (XACML `deny-overrides`, AWS IAM explicit-deny, Kubernetes/OPA/Cisco) and KCP's own "deny-by-default" (§4.3c) — `deny` in this domain already implies override-of-allow. Path matching reuses the existing `action_scope` path-glob semantics. An empty `deny` object is a no-op. `deny` is **not** a substitute for omitting an allow — it is an assertion that a capability is prohibited *even if otherwise granted*, which is the property prose and allowlist-absence cannot provide.

### Interaction with existing rules

- **§4.3c `load_eligible`** unaffected — eligibility gates *loading*; `authority_level`/`deny` gate *what a loaded skill may do*.
- **RFC-0027 steps** — a step's effective authority now folds in the used skill's ceiling (source 5); a step's conformance check now also applies the used skill's `deny`.
- **§4.17 permission caps** — `deny` composes *after* the §4.17 authority→permission cap: an action must pass the authority cap **and** not be forbidden.
- **Validator** — `authority_level` must be on the scale; `deny` must be a valid scope shape; a unit whose `deny` fully contains its own `allow` for a dimension SHOULD emit a §7 warning (self-nullifying scope).

### Migration / backward compatibility

Both fields are OPTIONAL and absent-means-today. No existing manifest changes meaning. Parsers that predate this RFC ignore unknown fields and load the skill ungoverned on the new dimensions — so the fields **MUST** be treated as fail-open by old parsers but fail-closed by conformant ones; publishers targeting mixed fleets should not rely on `deny` for a hard security boundary until the consuming runtime is known to be conformant (same caveat as every §4.3a governance field).

## Acceptance

1. A `kind: skill` with `authority_level: suggest` used by a step granted `commit` resolves to effective `suggest` (the skill is the binding source, named in the trace).
2. An `action_scope.deny.tools: [publish]` denies a `publish` request even when `tools: [publish]` would otherwise allow it, fail-closed, with the deny cited.
3. Validator: off-scale `authority_level` → error; malformed `deny` → error; self-nullifying `deny` ⊇ `allow` → warning.
4. Round-trips through the manifest JSON projection (MCP bridge) with both fields present.
