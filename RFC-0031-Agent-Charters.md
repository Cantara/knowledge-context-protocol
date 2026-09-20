# RFC-0031: Agent Charters on `kind: knowledge`

**Status:** Draft
**Version target:** 0.33 (§4.3d addition)
**Related:** SPEC.md §4.3a (`kind`, the default-kind rule, unknown-kind handling, `action_scope`), §4.3b (`steps`), §4.3c (`load_eligible`), §4.17 (`authority` — the unit-level action/permission block), §4.23 (`authority_level`), §3.13 (`grant_ceiling`, the multi-source minimum), §3.14 (escalation and grant requests). RFC-0027 (Playbooks) — this RFC explains why an Agent is *not* a playbook. RFC-0028 (Eligibility Grants) — a charter is not an eligibility grant and never becomes one. RFC-0029 / RFC-0030 (skill- and playbook-level prohibitions) — `charter.scope.never` is a *declaration*, not an `action_scope.deny`, and the difference is load-bearing.

## What This RFC Proposes

One addition, OPTIONAL and backward-compatible (absent = today's behaviour):

**`charter` — an OPTIONAL structured object that MAY appear on a `kind: knowledge` unit.** It
declares the standing governance an *agent* runs under: a deny-by-default action scope, a set of
decision rules, the conditions that force escalation, and the named authority the agent answers
to. A knowledge unit that carries a `charter` **is** an Agent, in the only sense a file-format
specification can mean it; a knowledge unit without one is unchanged.

No new `kind` is sealed. No existing gate is weakened. **A charter grants nothing** — it is a
declaration of limits, not an authorisation, and this is the property that makes attaching it to
the *default* kind safe.

```yaml
- id: expense-approval-agent
  kind: knowledge
  path: agents/expense-approval.md
  intent: "When may an expense be auto-approved, and when must it go to a named human?"
  scope: project
  audience: [agent, operator]
  charter:
    authority: "Meridian Systems AS — Finance"   # the office this charter answers to
    persona: "Fjalar"                            # COSMETIC ONLY — never read as authority
    scope:                                       # deny-by-default
      read:    [expense-request, approved-vendor-list, recent-claims]
      propose: [auto-approve, escalate]
      never:   [execute-payment, add-vendor, modify-policy, escalate-authority]
    rules:
      - id: EXP-1
        text: "Routine spend ≤ €500 in an operational category from an approved vendor is auto-approvable."
        decision: auto-approve
      - id: EXP-2
        text: "Any amount above €500 exceeds delegated authority."
        decision: escalate
        authority_required: cost-centre-owner
    escalate_when:
      - "the rule does not clearly apply to the request"
      - "a receipt is missing"
    authority_required: finance-controller       # default authority for escalations
```

## The Problem

### An Agent is knowledge-shaped, and KCP has no way to say so

A governed AI agent, in deployment, is four things bound together: an identity that is
accountable to some office, an explicit boundary of what it may touch, a set of decision rules it
reasons within, and a rule for when it must stop and ask a human. Every one of those is a
*standing fact* — true of the agent between invocations, true regardless of which request arrives.
That is the shape of `kind: knowledge`: facts plus a decision policy, loaded and embedded.

KCP today can express none of it in one place. `action_scope` (§4.3a) attaches to `kind: skill`
and bounds an *enactment*. `authority` (§4.17) governs what may be done with *this unit's
content*. `authority_level` (§4.23) is an ordinal ceiling, not a named accountable office. There
is no structure that says "this is the standing policy an agent operates under", so an
implementation that needs one either invents a private `kind` or carries the governance outside
the manifest, where nothing validates it.

### A tenant-private `kind: agent` is worse than no kind at all

The obvious move is a new `kind: agent`. Its consequences under the current spec are not
theoretical, and they are all bad:

- §4.3a: "Unknown `kind` values MUST be silently ignored by parsers." A conformant parser
  discards the value.
- §4.3a: "If `kind` is omitted, parsers MUST treat the unit as `kind: knowledge`." Having
  discarded the unknown value, the parser lands on the default — so every compliant KCP parser
  in the world reads a `kind: agent` unit as a `kind: knowledge` unit, **and emits no diagnostic
  while doing it**.
- §4.3c: unknown kinds "are never eligible whatever a manifest declares (C4)". A private `kind`
  is therefore permanently ineligible as well as silently misread.

So a Canvas-private `kind: agent` would not be a KCP kind. It would be a string that KCP throws
away, producing exactly the fragmentation the RFC process exists to prevent: two products both
claiming conformance, disagreeing about what a unit *is*, with no diagnostic anywhere. If Agent
deserves a kind, it deserves one through this process. This RFC's position is that it does not
need one yet — see *Alternatives Considered*.

### Modelling an Agent as a Playbook does not fit, and the misfit is structural

`kind: playbook` (§4.3b, RFC-0027) is the other candidate, and it fails on two independent
counts:

1. **A charter is not a sequence.** RFC-0027's semantics are normatively about *ordering*:
   `steps` are enacted in order, `depends_on` forms an acyclic graph, `on_failure` decides what
   continues, `success_condition` is evaluated per step. A standing policy has no step 1. Encoding
   one as a playbook means declaring an ordering that means nothing and that a conformance checker
   will nonetheless check.
2. **Multi-agent orchestration becomes inexpressible.** RFC-0027 OQ1 forbids nesting outright — "a
   validator MUST error when `uses` names a `kind: playbook` unit". If an Agent is a Playbook, then
   an orchestrator that dispatches to other agents is a playbook whose steps name playbooks, which
   is a MUST error. The model rules out the case it would most need to support.

### Provenance of the claims in this section

Two independent implementations, both shipped before this RFC was drafted, converged on the same
shape. They are cited here as *prior art the RFC formalises*, not as evidence that the design is
correct.

**A running governed-agent gateway** (`gateway/src/gateway.mjs`, ~450 lines, systemd-managed, in
the `exoreaction/Sunstone-Atlas` repository) enforces a deny-by-default action scope against every
agent decision and emits a signed receipt per decision. Its policy corpus
(`gateway/seed-kb/*.yaml`, six units) carries, per policy, exactly: `authority` (a named office,
as a string), `rules[]` with `id` / `text` / `decision` / `authority_required`, `escalate_when[]`,
and a `scope` block of `read` / `propose` / `never`. Personas in that gateway are display-only and
live in code, never in the policy files. That vocabulary was written for a working system, not for
this document.

**A canvas authoring tool** in the same repository (`canvas/schema/knowledge.schema.json`,
`canvas/src/validate.mjs`) generalised that corpus into a `charter` object on a `kind: knowledge`
unit, with a shape pass (`charterShapeErrors`) and a semantic pass (`charterErrors`) implementing
seven named `CHARTER-*` rules, under test. It reached the design in this RFC by refusing to invent
a private `kind` — the reasoning in *A tenant-private `kind: agent` is worse than no kind at all*
above is that implementation's, reproduced.

What the convergence does **not** establish: that two implementations in one repository are
independent of each other in the way two vendors would be. They share authorship and a corpus.
The evidence supports "this shape is workable and was arrived at from operational need"; it does
not support "this shape is what a second, unrelated runtime would choose". See *Open Questions*.

## Design

### `charter` on a `kind: knowledge` unit

| Field | Requiredness | Type | Semantics |
|---|---|---|---|
| `charter` | OPTIONAL | object | Present on a `kind: knowledge` unit only. Declares the standing governance an agent bound to this unit operates under. Absent → the unit is an ordinary knowledge unit (today's behaviour). Declaring it on any other `kind` is a manifest error. |

This follows the established §4.3a/§4.3b pattern for optional structured extensions to a kind —
`action_scope` on `skill`, `steps` on `playbook` — with one deliberate difference: those attach to
kinds that *act*, and `charter` attaches to the kind that does not. That is the point. Because
`kind: knowledge` has no enactment path (no `load_eligible`, §4.3c; no `action_scope` enforcement,
§4.3a), a `charter` **cannot** widen anything. It states limits and names an accountable office;
whatever enacts on its behalf is governed by the ordinary skill/playbook machinery.

### `charter` fields

| Field | Requiredness | Type | Semantics |
|---|---|---|---|
| `charter.scope` | REQUIRED | object | Deny-by-default action scope. See below. |
| `charter.authority` | REQUIRED | string | The entity or office this charter's authority derives from (e.g. `"Meridian Systems AS — Finance"`). A charter with no accountable authority is not a governance object. |
| `charter.rules` | OPTIONAL | array of object | The charter's decision rules. See below. |
| `charter.escalate_when` | OPTIONAL | array of string | Plain-language trigger conditions requiring escalation to a human, independent of any single rule. |
| `charter.authority_required` | OPTIONAL | string | Default named role an escalation defers to when the triggering rule names none. |
| `charter.persona` | OPTIONAL | string | **Cosmetic display label only.** See *`persona` is cosmetic, normatively* below. |

`charter.scope` (all sub-fields arrays of string):

| Field | Requiredness | Semantics |
|---|---|---|
| `scope.read` | OPTIONAL | What the agent may read or ground its reasoning in. |
| `scope.propose` | OPTIONAL | What the agent may **propose** — never execute. Entries name proposals, not acts. |
| `scope.never` | REQUIRED, non-empty | What the agent may never do, regardless of confidence. |

`charter.rules[]`:

| Field | Requiredness | Semantics |
|---|---|---|
| `id` | REQUIRED | Stable short id, unique within the charter (e.g. `EXP-1`). What a decision cites. |
| `text` | REQUIRED | Plain-language statement of the rule. |
| `decision` | OPTIONAL | Free-text decision the rule leads to (e.g. `auto-approve`, `escalate`). |
| `authority_required` | OPTIONAL | Named role this rule's escalation defers to. |

`decision` is deliberately **not** a closed enum. The prior-art corpus uses the field
inconsistently across its six policy files, and closing the vocabulary here would either reject
working data or standardise a taxonomy no implementation has yet earned. A later RFC may close it;
opening a closed enum later is compatible, narrowing an open one is not.

### `scope.never` is REQUIRED and non-empty — deny-by-default has to bite

A charter whose `never` list is absent or empty is not deny-by-default; it is a permission
document with a governance vocabulary. Requiring `never` non-empty is the one place this RFC
imposes a cost on the author, and it is imposed deliberately: it forces every charter to state at
least one thing its agent structurally cannot do, which is the claim a charter exists to make.

`scope.propose` names *proposals*. An entry that reads as the executed act ("grant journal
access") rather than the proposal of it ("propose journal access") collapses the distinction the
scope is built on, and is caught by validation below.

### `persona` is cosmetic, normatively

`charter.persona` is a display label — a name a viewer may show, nothing more.

**A conformant implementation MUST NOT derive authority, scope, routing, or any rule from
`charter.persona`.** It is not an identity, not a key, not a selector. The field exists because
deployments give agents names and those names end up somewhere; declaring it explicitly as
cosmetic is safer than leaving it to a tenant extension that some consumer later reads as
meaningful. The normative MUST NOT is what makes a wrong or missing `persona` incapable of
widening anything.

The running gateway keeps its personas in code precisely so they cannot be mistaken for policy.
This field brings them into the manifest without bringing them into the governance path.

### Interaction with existing rules

- **§4.3c `load_eligible`** — unaffected and inapplicable. `load_eligible` is defined only for
  `skill` and `playbook`; a `charter` neither requires nor confers it. **A charter is not an
  eligibility grant**, and an implementation MUST NOT treat one as authorising enactment of
  anything.
- **§4.3a `action_scope` / RFC-0029 / RFC-0030 `deny`** — different objects, different jobs, and
  they are not interchangeable. `action_scope.deny` lists *tools, paths, and capabilities* — a
  runtime's own tokens, adjudicated at a gate. `charter.scope` lists *domain acts* in the
  charter's own vocabulary (`execute-payment`, `add-vendor`). The first is enforceable by string
  matching; the second is a declaration a human reviews and a runtime projects onto its own tokens.
  Conflating them would either put unmatched free text into a security gate or force domain policy
  into a tool vocabulary. See *Open Questions* 2 for the projection question this leaves open.
- **§4.17 `authority`** — orthogonal. §4.17 declares what an agent may do *with this unit's
  content* (read / summarize / modify / share_externally / execute). `charter.scope` declares
  what the agent bound to this charter may do *in its domain*. Both may appear on the same unit;
  neither overrides the other.
- **§4.23 `authority_level` / §3.13 `grant_ceiling`** — orthogonal, and the names are dangerously
  close. `authority_level` is an ordinal token on a declared scale, resolved as a minimum across
  sources. `charter.authority` is a *named office*, a free string, participating in no ordinal
  computation whatsoever. A `charter` contributes **nothing** to the §3.13 minimum. See *Open
  Questions* 1 — this RFC considers the collision real and does not consider it resolved.
- **§3.14 escalation** — `escalate_when` and `authority_required` name *when* to escalate and *to
  whom*. They define no request/response surface; the §3.14 machinery is where that lives. As with
  RFC-0027's `success_condition`, these are **prose, not an expression language** — this
  specification defines no evaluator for them and an implementation MUST NOT infer that one exists.
- **§17 observability** — no new event table. A charter is declarative; the events worth recording
  are the enactment events the existing tables already cover.

### Validation

A conformant implementation:

- **MUST** error when `charter` appears on a unit whose `kind` is not `knowledge` (including the
  omitted-kind case, which §4.3a resolves *to* `knowledge` and is therefore legal).
- **MUST** error when `charter.scope` or `charter.authority` is absent.
- **MUST** error when `charter.scope.never` is absent or empty — deny-by-default with nothing
  denied is not deny-by-default.
- **MUST** error when two rules in one charter share an `id`. Rule ids are what a decision cites;
  an ambiguous citation is worse than none.
- **MUST** error when `charter.scope.propose` and `charter.scope.never` both list the same entry.
  A charter that both permits and forbids the same act has no defined behaviour, and picking a
  winner in the spec would make the contradiction survivable.
- **SHOULD** warn when a rule whose `decision` reads as an escalation names no
  `authority_required` and the charter declares no default `authority_required` — an escalation
  with no addressee is an escalation that does not happen. Recognising "reads as an escalation" is
  a heuristic over free text, which is why this is a SHOULD and not a MUST.
- **SHOULD** warn when a `charter.scope.propose` entry reads as an executed act rather than a
  proposal (an entry opening with an executing verb: execute / write / delete / pay / grant). Also
  a free-text heuristic, also therefore a SHOULD.
- **SHOULD** warn when `charter.persona` appears verbatim inside the unit's `intent` or any rule's
  `text` — flavour has leaked into governed text, and a reader cannot tell which is which.

Two conventions belong to the *corpus*, not to this specification, and are named here so
implementers do not mistake them for spec rules:

- An implementation serving a demonstration or synthetic corpus **SHOULD** enforce a corpus-level
  constraint that `charter.authority` names a fictional entity. KCP itself defines no such
  requirement — a real deployment's charter names a real office, which is the entire point of the
  field. (The reference implementation enforces this against its own synthetic-tenant naming
  scheme; that rule is that corpus's safety policy, not KCP's.)
- An implementation **MAY** require baseline entries in `scope.never` (the reference
  implementation requires a policy-modification guard and an authority-escalation guard —
  an agent may *request* escalation, never grant it). This RFC does not mandate a baseline
  vocabulary; standardising one requires a controlled taxonomy that does not exist yet.

### Migration / backward compatibility

`charter` is OPTIONAL and absent-means-today. No existing manifest changes meaning. Pre-0.33
parsers ignore the unknown field (§2 forward compatibility) and read a charter-bearing unit as the
ordinary `kind: knowledge` unit it already is — which is the correct degradation, because a
charter authorises nothing that an old parser could then fail to withhold. This is a materially
weaker failure mode than the §4.3a governance fields carry: an ignored `action_scope.deny` means a
prohibition goes unenforced, whereas an ignored `charter` means a declaration goes undisplayed.

## Alternatives Considered

- **A sealed `kind: agent`.** Rejected *for now*, not on principle. Under the current spec it buys
  nothing that `charter` on `knowledge` does not: an agent is selected like knowledge, loaded like
  knowledge, and its enactment is governed by the skill/playbook machinery either way. It costs a
  permanent addition to a sealed vocabulary that appears in two SPEC tables, a schema enum, three
  validators and every kind-enumerating test, plus a compatibility cliff for every parser below
  the version that seals it. The condition under which this becomes the right answer is stated in
  *Open Questions* 3.
- **An Agent as a `kind: playbook` with extension metadata.** Rejected on the two structural
  grounds in *The Problem*: RFC-0027's normative semantics are about step ordering, which a
  standing policy does not have, and RFC-0027 OQ1's nesting prohibition would make a multi-agent
  orchestrator a MUST error.
- **Reusing `action_scope` for `charter.scope`.** Rejected: `action_scope` entries are runtime
  tokens matched at a gate; charter scope entries are domain acts in the charter's own vocabulary.
  Putting free-text domain phrases into a matching gate produces a gate that matches nothing while
  appearing to enforce something — the exact failure mode RFC-0030 was written to close one level
  down.
- **A closed `decision` enum.** Deferred. The prior-art corpus is internally inconsistent on this
  field; closing the vocabulary now would reject working data on the strength of one corpus.
  Opening later is compatible; narrowing later is not.
- **Making `charter.scope.never` normatively enforceable.** Rejected for this RFC. Enforcement
  needs a projection from domain acts onto a runtime's tools/paths/capabilities, and no such
  projection is specified. Declaring enforcement without specifying the projection would produce
  a prohibition every implementation enforces differently — see *Open Questions* 2.

## Open Questions

1. **`charter.authority` collides with two existing names.** SPEC already defines `authority` as a
   unit-level *object* (§4.17, an action→permission map) and `authority_level` as an *ordinal
   token* (§4.23). This RFC adds `authority` as a *string naming an office*, one level down inside
   `charter`. The nesting disambiguates it mechanically, and it matches the shipped prior art —
   but a specification with three differently-typed things called "authority" is asking to be
   misread, and a reviewer may reasonably prefer `charter.accountable_authority` or
   `charter.authority_source`. This RFC proposes keeping `authority` and considers the question
   genuinely open; it is the single most likely thing in this document to change before promotion.
2. **Should `charter.scope` project onto `action_scope`?** When a charter-bearing unit's agent
   enacts through a `kind: skill` unit, nothing connects `charter.scope.never` to that skill's
   `action_scope.deny` (RFC-0029). An author can write a charter that forbids `execute-payment`
   and a skill that allows the payment tool, and no validator objects. A projection rule — or a
   requirement that the two be declared consistent — is the obvious next step and is deliberately
   not specified here, because the mapping from domain acts to runtime tokens is exactly the part
   no implementation has yet solved. Until it is, **a charter documents a boundary that only the
   skill layer enforces**, and this RFC says so rather than implying otherwise.
3. **Should `charter` eventually graduate to a sealed `kind: agent`?** Unresolved. The trigger this
   RFC proposes: a **second, independently authored runtime** needing to dispatch on agent-ness
   before loading the unit — that is, a case where "is this an Agent?" must be answerable from the
   `kind` field alone rather than from the presence of `charter`. One repository's two
   implementations are not that evidence (see *Provenance*). Until then, presence-of-`charter` is
   the discriminator, and it costs nothing to keep.
4. **Multi-agent orchestration is out of scope, and the gap is real.** An orchestrator that
   dispatches to other charter-bearing agents — which the reference gateway implements in code, as
   a hard-coded list of dispatchable policy ids — has no expression here. This RFC deliberately
   specifies no dispatch semantics, no charter composition, and no answer to whether a dispatching
   charter's `scope.never` binds the agents it dispatches to. That last question is the one a
   future RFC must answer first; the union-of-denies rule in RFC-0030 is the obvious precedent but
   this RFC does not assert that it transfers.
5. **No lifecycle beyond what `knowledge` already has.** A charter-bearing unit publishes, signs,
   supersedes and versions exactly as any knowledge unit does (§4.2, §4.8). This RFC proposes no
   charter-specific publish gate, no signature requirement, no version ladder, and no rule that a
   charter change must be reviewed more heavily than a prose change — even though a charter change
   is a governance change and a prose change is not. Whether that asymmetry is acceptable is open.
6. **The free-text heuristics are SHOULDs, and two implementations will diverge.** The
   escalation-without-authority check, the mutating-verb check and the persona-leak check all
   pattern-match natural language. They catch real defects in the prior-art corpus and they will
   not agree across implementations. They are specified as SHOULD warnings so that divergence is
   visible rather than load-bearing; whether a specification should describe such checks at all is
   a fair objection to this RFC.

## Acceptance

1. A `kind: knowledge` unit carrying a well-formed `charter` validates clean, and a pre-0.33
   parser reads the same unit as an ordinary knowledge unit with no diagnostic and no behaviour
   change.
2. `charter` on a `kind: skill` or `kind: playbook` unit → validation error. `charter` on a unit
   with `kind` omitted → clean (§4.3a resolves the omission to `knowledge`).
3. Absent `charter.scope`, absent `charter.authority`, and absent-or-empty `charter.scope.never`
   each produce an error; duplicate rule ids and a `propose` ∩ `never` overlap each produce an
   error naming the offending entries.
4. A rule whose `decision` reads as an escalation and which names no `authority_required`, with no
   charter-level default, produces a warning; adding either the rule-level or the charter-level
   authority clears it.
5. A `propose` entry opening with an executing verb produces a warning; the same act phrased as a
   proposal does not.
6. A `persona` string appearing in the unit's `intent` or in a rule's `text` produces a warning;
   the same `persona` used only as a display label does not.
7. No charter field participates in the §3.13 authority minimum, and no charter confers
   eligibility: a charter-bearing unit with no `load_eligible` grant remains ineligible for
   enactment, verifiably, before and after this RFC.
8. Round-trips through the manifest JSON projection (MCP bridge) with the field present, `persona`
   included and inert.
