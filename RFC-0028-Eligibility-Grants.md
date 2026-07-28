# RFC-0028: Eligibility Grants and Their Composition

**Status:** Draft
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-07-28
**Related:** RFC-0018 (Trusted Render Pipeline) — §16.3 C4 requires the grant this RFC specifies.
RFC-0027 (Playbooks) — the composition case. SPEC.md §4.3a (`kind: skill`, added directly in
v0.26 with no RFC of its own), §4.3b, §16.3.
**Spec:** [SPEC.md](./SPEC.md) (current: v0.29) — targets **v0.30**.

---

## What This RFC Proposes

Two things the specification currently leaves to implementers:

1. **Specify the eligibility grant as a manifest field.** §16.3 C4 requires that a `kind: skill`
   unit "becomes load/invoke-eligible only when the manifest carries an explicit eligibility grant
   for it" — and the specification never says what the author writes to carry it.
2. **Say whether eligibility composes.** A `kind: playbook` step names another unit via `uses`.
   Whether the playbook's grant reaches that unit is undefined.

```yaml
- id: complete-promotion
  kind: skill
  load_eligible: true            # specified as an input field here for the first time
  action_scope: { tools: [git], paths: ["deploy/**"] }
```

## The Problem

### C4 mandates a mechanism with no input surface

The phrase "an explicit eligibility grant" appears three times in SPEC.md — §4.3a, §16.3 C4, and
the C1–C10 summary. It is load-bearing: without the grant, a `kind: skill` unit renders as a
pointer with `invocation: explicit` and cannot be enacted.

**The token `load_eligible` does appear in SPEC.md — eight times — and every one is renderer
output.** They read "MUST render `load_eligible: false`", "is forced to", "MUST NOT emit". The
specification describes at length what a renderer *emits* and never once says what an author
*writes* to earn it. §4.3a's field table for `kind: skill` lists only the `action_scope`
sub-fields (`tools`, `paths`, `capabilities`, `spend`).

A conformance requirement whose input has no declared form is not checkable. Two independent
implementers reading C4 would invent two different fields and both would claim conformance.

### What implementations actually did

`load_eligible: true` on the unit — the same token the renderer emits, reused as an input. Chosen
by the reference planner (kcp-agent) and the CLI renderer, and **undeclared everywhere else**:

| | knows `load_eligible` |
|---|---|
| `schema/knowledge-schema.json` | **no** — accepted only because `additionalProperties: true` |
| `shared/src/validator.ts` | **no** |
| `parsers/python/kcp/validator.py` | **no** |
| `KcpValidator.java` | **no** |
| `cli/src/render.ts` | yes (as output) |
| kcp-agent (separate repo) | yes (as input) |

Adoption is narrow and worth stating accurately rather than impressively: **15 of 285
`knowledge.yaml` files under `/src/cantara` declare it**, concentrated in kcp-agent's own examples
and pi-kcp's demo fixtures. It is the de-facto field, not a widely deployed one.

The consequence of being undeclared: a manifest can misspell it — `load-eligible`,
`loadEligible`, `load_elegible` — and validate clean while failing closed forever, with no
diagnostic anywhere. That fails safe, which is why nobody has noticed.

### Whether eligibility composes has no answer

RFC-0027 gave a playbook step `uses`, a reference to another unit. Consider a **granted** playbook
whose step names an **ungranted** skill.

Observed with **kcp-agent 0.20.0**, `plan --strict --json`, against a two-unit fixture
(`--strict` and the reason string are kcp-agent's, not the specification's):

```
selected: promote-release          (kind: playbook, load_eligible: true)
skipped : run-suite                (kind: skill, no grant)
          "kind: skill not invoke-eligible: no explicit eligibility grant"
```

The planner offers the composition while withholding the part it invokes. The agent receives a
procedure it cannot legally execute — and if a downstream enforcer does not check per step, the
playbook is a laundering path for the withheld skill.

This is a **different axis** from RFC-0027's lowest-of rule, which governs `authority_level`.
§4.3b is silent on eligibility, so neither reading is currently non-conformant.

## Design

### The grant field

```yaml
load_eligible: true
```

| Field | Required | Type | Applies to | Absent means |
|---|---|---|---|---|
| `load_eligible` | OPTIONAL | boolean | `kind: skill`, `kind: playbook` | not eligible |

**The field is defined only for governed kinds** — `skill` and `playbook`, the kinds that *act*.
An earlier draft gave it a default of `true` for every other kind, which contradicted C4's
existing ban on emitting `load_eligible: true` for `executable`/`service`/unknown kinds. The field
has no input meaning for those kinds; Conformance below makes declaring it there an error.

**Why `executable` and `service` are exempt from the grant rather than subject to it.** They are
not less dangerous — an `executable` is arguably the most directly action-taking kind. They are
governed *elsewhere*: C4 forbids a renderer from ever marking them load-eligible, so they are
permanently pointer-only and no grant could change that. `skill` and `playbook` are the kinds
where a grant is the difference between inert and enactable, which is why the field exists for
them alone. This RFC does not revisit that division.

**The name is deliberately the deployed one.** `load_eligible` conflates loading into context with
invoking a procedure, and `invoke_eligible` would read better for a playbook. It is specified
as-is because it is already the renderer's output vocabulary, the planner's input, and present in
every fixture that exercises skills — and renaming a security-relevant field to improve its prose
is a poor trade.

**It is a grant, not a capability claim.** `load_eligible: true` asserts that this unit is
authorised to be enacted. It does not widen `action_scope`, does not raise `authority_level`, and
carries no meaning for a unit that declares neither.

**Its authority is the manifest's signature, and nothing finer.** A bare boolean in a signed
manifest is granted by whoever can sign — in practice, whoever can merge. There is no separate
authorisation event, no named grantor, and no record of when the grant was made. That is a real
weakness and it is not fixed here: attributing a grant belongs with RFC-0026's
`grant_request_events`, which already models grantor, resolver, and timestamp. Open Question 2.

### Eligibility does not compose

**A playbook step MUST NOT be enacted unless both the playbook and the unit named by its `uses`
are themselves eligible.** A grant on the composition does not reach the parts.

The alternative — treating the playbook's grant as blessing everything it names — makes
`load_eligible: true` on a playbook a **universal grant**: any skill in the manifest becomes
reachable by naming it in a step, including one deliberately withheld. That is the same shape as
the defect RFC-0027's implementation had to fix, where a composition escaped the gate its parts
were held to.

It also matches the grain of the protocol. Authority is a minimum across sources (§3.13); a
playbook can never raise it (§4.3b); the render pipeline sanitises the map but not the territory
(RFC-0018 §2.1). Eligibility composing *upward* would be the only place where assembling parts
yields more than the parts had.

#### The cost, and why it is not paid by relaxing the rule

Under this rule a skill cannot be "enactable only within an approved playbook". Granting it for
playbook use also makes it independently invocable.

That is not a small cost — it is arguably the composition use case RFC-0027 was written for, and
an author who wants narrow exposure must instead grant broadly, which *enlarges* the attack
surface. The reviewer's objection stands: this RFC makes the safe choice on the composition axis
and pays for it on the exposure axis.

It is still the right order to do things in. Permissive composition is unrecoverable — once a
playbook grant reaches arbitrary units, no later RFC can narrow it without breaking manifests that
relied on it. A restrictive rule plus a scoped grant (Open Question 1) reaches the same place and
can be added compatibly. Trading a precise limitation for an imprecise hole is the trade this RFC
declines.

## Conformance

Normative statements appear here only; the Design section above is explanatory.

**Schema**

- `load_eligible` MUST be declared as `{"type": "boolean"}`.
- The kind-conditional meaning MUST NOT be encoded as a JSON Schema default. Plain JSON Schema
  cannot express "default depends on the sibling `kind`" without `if`/`then`, and a declared
  default would be wrong for at least one kind. The default is a *validator* rule, below.

**Validation**

- A validator MUST error when `load_eligible` is present and is not a boolean after parsing.
- Authors MUST write `true` or `false`. The scalars `yes`, `no`, `on`, `off` are **not**
  interchangeable: measured against this project's own parsers, PyYAML (YAML 1.1) reads `yes` as
  boolean `true` while js-yaml (YAML 1.2) reads it as the string `"yes"`. The same manifest
  therefore grants eligibility in Python and fails to in TypeScript.

  This cannot be fixed by a validator rule, which is why it is stated as an authoring
  requirement. By the time a validator sees the value, a 1.1 parser has already turned `yes` into
  `true` and it is indistinguishable from an intended grant. The schema rejects it in the 1.2
  case and cannot in the 1.1 case. A linter that reads the *raw bytes* could catch it; that is a
  tooling matter, not a conformance rule, and the hazard applies to every boolean field in KCP
  rather than only this one.
- A validator MUST error when `load_eligible` is declared on a unit whose `kind` is not `skill` or
  `playbook`.
- A validator MUST error when a **`kind: skill`** unit declares `load_eligible: true` and no
  `action_scope`. Such a unit is authorised to act and bounded in nothing it may touch.
- This rule MUST NOT be applied to `kind: playbook`. §4.3b makes a playbook's `action_scope`
  declarative rather than a grant, and not computable at all when any step is inline — so
  demanding one as a condition of eligibility would require a field the specification says does
  not bound anything. A playbook is bounded by the units its steps `uses`.
- **A validator MUST error when an eligible `kind: playbook` declares any inline (`action`) step.**
  This closes the hole the previous rule would otherwise open. §4.3b already states that an inline
  step is scope-*unbounded* rather than widely scoped: it names no unit, so there is nothing whose
  `action_scope` could bound it, and the playbook has no computable scope of its own. Exempting
  playbooks from the scope requirement without this rule would make a granted inline playbook the
  one construct in KCP that acts with no scope at all.

  Inline steps remain legal on an **ineligible** playbook — §4.3b introduced them as a transitional
  affordance for authoring before every step has a unit, and that use is unaffected. What is
  forbidden is granting one.
- A validator MUST error when a step's `uses` names a unit whose kind is `executable`, `service`,
  or unknown. Those kinds can never be eligible (C4), so such a step can never be enacted; §4.3b's
  existing SHOULD-warn on non-`skill` targets is the right strength for `knowledge`/`policy`/
  `schema`, which are merely wrong rather than unenactable.
- A validator MUST error when an **eligible** `kind: playbook` has a step whose `uses` target is
  not eligible — the playbook cannot be enacted as written.
- A validator SHOULD warn when an **ineligible** playbook has such a step. The playbook cannot be
  enacted at all, so the inner defect is not yet reachable; reporting it as an error would bury
  the actual problem, which is the missing grant on the playbook itself.
- A validator MUST report a `uses` target that resolves into a **composed** manifest (§3.11) as
  *unverified* for this check — a warning naming the step, the target, and the source manifest —
  rather than passing it silently or failing it. Eligibility is a property of the unit in the
  manifest that declares it, and this RFC does not define whose grant governs a cross-manifest
  reference; an unverifiable check that lints clean reads as checked. Open Question 3.

**Rendering** (amends RFC-0018 C4, which is otherwise unchanged)

- A renderer MUST treat absent `load_eligible` on a governed kind as `false`.
- A renderer MUST emit `load_eligible` explicitly — `true` or `false` — rather than omitting it,
  so a consumer can distinguish "declared ineligible" from "this renderer does not implement C4".
  A consumer that finds the field absent MUST treat the unit as ineligible.
- A renderer MUST emit `invocation: explicit` for an ineligible governed unit (C4, unchanged) and
  MUST NOT emit `invocation: explicit` for an eligible one — otherwise the two states are
  indistinguishable downstream, which is the whole point of the grant.

**Enaction**

- An implementation MUST NOT enact a playbook step unless both the playbook and the `uses` target
  are eligible.
- An implementation MUST NOT treat a grant on a composition as a grant on the units it names.
- An implementation that enacts directly from a manifest, without going through a renderer, MUST
  apply every rule above that a renderer or validator would have applied — the kind restriction
  (`executable`, `service` and unknown kinds are never eligible), the `action_scope` requirement
  for a granted skill, and the inline-step prohibition for a granted playbook. A conformance rule
  that only binds implementations which happen to run a validator is not a conformance rule.
- An implementation MUST NOT enact a step whose `uses` target resolves into a composed manifest
  until Open Question 3 is settled. The validator reports that case as unverified; an enactor has
  no such option, and unverified eligibility must fail closed.

## Open Questions

1. **Scoped grants.** Should a unit declare eligibility that holds only within a named playbook —
   `load_eligible: {within: [promote-release]}`? It removes the cost named above. It raises what
   this RFC does not answer: what happens when the naming playbook is superseded, whether scopes
   nest once nested playbooks are permitted (forbidden today, RFC-0027 OQ1), and whether a scoped
   grant is visible to a consumer that never selects the playbook.
2. **Who signed the grant?** A bare boolean carries no grantor, no timestamp, no separate
   authorisation event. RFC-0026's `grant_request_events` already models all three. Whether the
   grant should reference such a record, or remain a manifest-signature-scoped assertion, is
   unresolved — and it is the weakest point in this design.
3. **Cross-manifest `uses`.** Composed manifests (§3.11) can supply units a playbook names. Whose
   grant governs, and whose signature attests it, is undefined. Conformance above fails closed by
   reporting such references as unverified.
4. **Multi-hop composition.** The rule checks the playbook and its step's target — two parties.
   If nested playbooks are ever permitted, the check must become transitive, and "eligible" will
   need to mean "eligible along the whole chain."

## Backward Compatibility

Two changes, one benign and one deliberate.

**Benign.** `load_eligible` is already the de-facto input field, so manifests declaring it keep
their meaning and gain schema validation. Manifests not declaring it were already failing closed
for governed kinds. The new MUST-error on a granted unit with no `action_scope` may fail existing
manifests — that is intended, and such a unit is unbounded today.

**Deliberate.** A granted playbook whose steps name ungranted skills is offered by the reference
planner today and becomes a validation error. Such a playbook cannot be enacted as written now
either; it fails later, at the step, instead of at the manifest.

An earlier revision of this RFC gated the composition rule on `kcp_version: "0.30"`, so a
manifest declaring 0.29 got a warning instead of an error. **That was wrong, and it is worth
recording why.**

A signature does catch *tampering*: `kcp sign` covers the whole manifest bytes, so editing 0.30
down to 0.29 in a signed manifest breaks verification. But tampering is not the threat. An
attacker authors their manifest, they do not edit someone else's — writing `kcp_version: "0.29"`
from the start and signing it with their own key produces a perfectly valid signature and a
weaker check. Most manifests are unsigned besides, and `known` is not `trusted`.

The defect is structural: **a manifest must not declare which rules it is judged by.** Signing
proves who wrote a document, not that its claims about itself are safe to believe.

So there is no manifest-declared gate. An implementation MAY offer a compatibility mode that
downgrades the composition error to a warning, and that mode MUST be consumer-side configuration —
a flag, a policy file, a constructor argument — never a value read from the manifest under
inspection.

## Changelog

- 2026-07-28 — initial draft.
- 2026-07-28 — fourth revision. Round three found that exempting playbooks from the
  `action_scope` requirement left a granted playbook with inline steps unbounded — three of four
  lenses independently. Granting an inline-step playbook is now an error; inline steps stay legal
  on an ungranted one. Also: `uses` may not name a kind that can never be eligible, and a direct
  enactor must apply every rule a validator would, since a conformance rule that binds only
  implementations which happen to run a validator is not one.
- 2026-07-28 — third revision after a second review round (21 findings; citation-check clean).
  **Removed the `kcp_version` gate on the composition rule** — it let a manifest declare which
  rules it is judged by, which a signature does not prevent, because an attacker authors rather
  than tampers. Compatibility is now consumer-side configuration only. Dropped the unenforceable
  MUST on YAML `yes`/`no`/`on`/`off` in favour of an authoring requirement, having measured that
  PyYAML reads `yes` as `true` while js-yaml reads it as `"yes"` — the same manifest means
  different things in two of our own parsers. Restricted the no-`action_scope` error to
  `kind: skill`, since §4.3b makes a playbook's scope declarative and often not computable.
  Specified the eligible-side render output, absent-field handling, and the severity and shape of
  the cross-manifest report.
- 2026-07-28 — revised after adversarial review (25 findings). Removed the `true` default for
  non-governed kinds, which contradicted C4's emission ban; reconciled the granted-with-no-scope
  rule, which had opposite trigger conditions in two sections, and raised it to MUST. Corrected
  the Related header (RFC-0024 is Serving Endpoint Binding; `kind: skill` has no RFC). Corrected
  "the specification never names the field" — the token appears 8 times, always as renderer
  output, which is the precise claim. Corrected "deployed across every Cantara manifest" to the
  measured 15 of 285. Attributed the `--strict` transcript to kcp-agent 0.20.0 rather than
  presenting it as specification behaviour. Added: why `executable`/`service` are exempt; YAML
  boolean-scalar handling; explicit `false` emission; the ungranted-playbook case; cross-manifest
  `uses`; version gating for the breaking change; and a straight admission that grant
  attribution is the weakest point in the design.
