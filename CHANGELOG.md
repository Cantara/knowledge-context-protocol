# Changelog

All notable changes to the Knowledge Context Protocol specification and reference implementations are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [Unreleased]

---

## [0.28.0] — 2026-07-27 — Implementation Parity

First release since 0.26.0 (14 July). Ships v0.27 (Authority Level and Grant Ceiling,
RFC-0025) and v0.28 (Escalation and Grant Requests, RFC-0026), both promoted into SPEC.md
on 24 July but never tagged — and brings every implementation up to the spec they claim.

### Added

- **`action_scope.spend` — the spend dimension of a `kind: skill` envelope (§4.3a).** A skill may
  now declare what it is permitted to **buy** alongside the tools/paths/capabilities it may touch:
  an optional `spend` object with a per-purchase `max_spend` cap, an `allowed_vendors` allowlist,
  and the `currency` the cap is denominated in (all sub-fields OPTIONAL; `action_scope` stays an
  opaque passthrough object). The conformance gate adjudicates purchases fail-closed the way it does
  `tools`/`paths` — an unlisted vendor or over-cap buy is held — composing with (never overriding) a
  cumulative session budget where the caller maintains one — that budget is supplied at run
  time and is deliberately not a manifest field (§4.3a.1). The purchase **price** comes from the paid
  resource's own `payment`/`price_per_request` declaration (§4.14, x402): KCP governs the buy
  *decision*, a runtime wallet settles. Additive and backward-compatible. (#139)

---

- **`grant_request_events` (§17, v0.28)** — the escalation audit trail is now implemented.
  Written by `bridge/java/.../GrantRequestLogger.java`, read by `kcp stats`. One row per
  state transition, not one per request: a request's history is reconstructed by grouping
  on `id`, so the log can answer "how long was this pending" and "was it denied before it
  was granted". Nothing raises grant requests yet — RFC-0026 deferred the request/response
  mechanism to a future RFC — so the writer is a library seam for an adjudicator that lives
  in the planner. Stated in the class javadoc rather than left to be inferred. (#146)

- **`action_scope` in the Java and Python models.** `ActionScope` and `Spend` types, unit
  field, parsing and tests in both, mirroring `shared/src/parser.ts` rather than each
  inventing a shape. All three agree on the semantic that matters: absent yields
  `null`/`None`, never an empty object — "declares no scope" and "declares a scope
  permitting nothing" are different statements, and §4.3a makes the first authorize
  nothing. (#146)

### Fixed

- **Two of four validators rejected v0.27 and v0.28 manifests.** `KNOWN_KCP_VERSIONS` in
  `parsers/python/kcp/validator.py` and `KcpValidator.java` stopped at 0.26. The v0.27
  entry below records this same enum gap being closed for the JSON Schema and
  `shared/src/validator.ts` — two of four; the others were missed. Releasing without this
  would have shipped a version no reference implementation accepts. (#146)

- **`kcp init` scaffolded stale manifests.** `SCAFFOLD_KCP_VERSION` was `0.26`, so every
  project created after v0.27 began life two versions behind. (#146)

- **`bridge/typescript/src/mapper.ts` dropped `action_scope`.** Parsed, then discarded at
  the bridge boundary, so a consumer reading a manifest over MCP saw a `kind: skill` unit
  with no declared scope. Copied wholesale rather than rebuilt field by field, since §4.3a
  defines the object as an opaque passthrough. (#146)

- **`action_scope.spend` had no specification.** The field shipped in the JSON Schema in
  v0.26 and was described in no section of SPEC.md, while both the schema and this
  changelog justified it by citing a `money_budget` ceiling in "§4.14" — §4.14 is
  `payment` and defines no such thing. `money_budget` exists only as a planner gate. Now
  specified in §4.3a.1, with the dangling citation removed. (#144)

- **Version alignment across the repository.** CLI, both bridges, both parsers, the help
  banner, `RENDERER_VERSION` and the init scaffold all moved to 0.28.0. Each was required
  by an assertion in `cli/src/consistency.test.ts` — a cross-language version-drift guard
  that had been failing, and which is exactly the check that would have caught this drift
  months earlier. (#146)

### Known gaps

- `render_events` and `quarantine_events` (§17, normative since v0.16) remain unimplemented.
  Both belong to the render pipeline (§16), which `docs/PARITY.md` places outside the bridge
  parity contract. Recorded rather than left to be rediscovered.

---

## [0.28] — 2026-07-24 — Escalation and Grant Requests

**Spec version:** `"0.28"` | **Prior:** v0.27 (2026-07-24)

Promoted from [RFC-0026](./RFC-0026-Escalation-and-Grant-Requests.md). Closes the "a human
should approve" gap left open by `delegation.human_in_the_loop` (§3.4), `authority.<action>:
requires_approval` (§4.17), and `grant_ceiling` (§3.13): none of them define what happens
*after* a human is asked — who, how, what an answer looks like, how long it lasts, or how a
granted answer feeds back into evaluation.

### Added

- **§3.14 Escalation and Grant Requests.** Trigger vocabulary (`requires_approval`,
  `insufficient_authority_level`, `confidence_below_threshold`), the atomicity rule for tied
  `grant_ceiling` binding sources (a grant must raise *all* tied sources together — raising a
  subset is a no-op by construction), a rejection condition for `requested_level` failing to
  exceed the current effective level, and forced-expiry semantics for `standing` grants against
  a `mandatory_sources`-protected source (closing a loophole where a single unreviewed approval
  could permanently defeat §3.13's silent-erosion protection).
- **`task_types[].confidence_threshold`** — an agent-reported-confidence escalation trigger,
  evaluated *after* synthesis rather than before (categorically different from every other KCP
  mechanism, which is static declared metadata). Documented as self-reported and gameable,
  mirroring RFC-0009 Appendix D's honest treatment of authority-declaration enforcement.

### Provisional

- **Wire format not fixed.** Whether `grant_request` is a manifest field, a separate API/event
  shape, or both, is unresolved (RFC-0026 Open Question 1). The semantics above are normative;
  the concrete object representation is not. Conformance levels for this section are marked
  provisional pending resolution.

---

## [0.27] — 2026-07-24 — Authority Level and Grant Ceiling

**Spec version:** `"0.27"` | **Prior:** v0.26.1 (2026-07-14)

Promoted from [RFC-0025](./RFC-0025-Authority-Level-and-Grant-Ceiling.md). Extends §4.17
`authority` (a per-unit, per-action permission) with a coarser, per-task-type ordinal ceiling,
and resolves RFC-0009's own Open Question 2 (a federation-wide floor), generalized from
hub-vs-sub-manifest to N independently-owned named sources.

### Added

- **§3.13 Authority Level and Grant Ceiling.** `authority_level_scale` (fixed, total-ordered:
  `observe < explain < suggest < prepare < commit`), `task_types[]` and `agents[]` id-keyed
  collections, and `grant_ceiling` — a multi-source minimum computation over named ceiling
  sources (org policy, regulatory constraint, task-type, agent capability, customer setting),
  with **required cycle detection** on the `unit_ref`/`task_type_ref`/`agent_ref` resolution
  chain (matching `composition.includes`/`superseded_by` precedent) and `mandatory_sources`
  enforcement to prevent a leaf task-type from silently dropping an org-wide ceiling.
- **§4.23 `authority_level`** — the unit-level ceiling, consumable as a `grant_ceiling` source
  via `unit_ref`.
- **Normative §4.17 capping table** — an effective `authority_level` caps `authority.<action>`
  permission values per a fixed 5×5 lookup, removing implementer discretion from how the two
  mechanisms interact.
- Two new §7 validation warnings (`authority_ceiling_undeclared`, unknown `authority_level`
  value) and three new manifest-error conditions (`grant_ceiling` reference cycles, missing
  `mandatory_sources` entries, duplicate `task_types[]`/`agents[]` ids).

### Fixed

- A red-team pass over the initial RFC-0025 draft found and corrected: a fabricated precedent
  citation (referenced `money_budget`/`max_units` filters that do not exist anywhere in
  SPEC.md), a semantically-empty `may_only_lower` flag (a minimum computation cannot be raised
  by any single source — the flag was a no-op, removed and replaced with prose stating the
  general principle), and a naming collision between the scale's top level and §4.17's
  `execute` action (renamed `execute` → `commit` on the ordinal scale).
- **`kcp_version` enum gap.** Neither the JSON Schema nor `shared/src/validator.ts`'s
  `KNOWN_KCP_VERSIONS` set recognized `"0.27"` or `"0.28"` — found while smoke-testing the
  schema update in this same release; a manifest declaring either would have failed schema
  validation and warned as an unknown version despite being fully conformant. Fixed in both
  places.

### Implementation

- Reference implementation landed in `shared/src/{model,parser,validator}.ts` (picked up
  automatically by the TypeScript bridge via its existing symlinks), `parsers/java`, and
  `parsers/python` — parsing, structural validation, `computeGrantCeiling()` (minimum +
  named binding source(s), reporting the full tied set rather than a single source), and
  `applyAuthorityCap()` (the §4.17 capping table). JSON Schema and conformance fixtures
  (7 new fixture pairs, `expected.json` generated by running the reference validator rather
  than hand-authored) updated to match.

---

## [0.26.1] — 2026-07-14 — v0.26 Hardening

**Spec version:** `"0.26"` (unchanged — one conformance fix + clarifications, no new fields) | **Prior:** v0.26.0 (2026-07-14)

A red-team pass over the v0.26 features (`serving`/C22 and `aliases`) surfaced one genuine
fail-open, a cross-implementation parser divergence, and several spec overclaims. All corrected
here. No new fields; conformant v0.26.0 manifests are unaffected except where a `serving.manifest`
mismatch should have demoted and previously did not.

### Fixed (security / correctness)

- **C22 present-empty fail-open (§3.12).** A **present-but-empty** `serving.manifest: []` is an
  *exhaustive-empty* assertion ("no HTTP(S) URL is authoritative") and MUST demote every HTTP(S)
  retrieval. The renderer previously gated on list length and treated `[]` as "no assertion",
  silently keeping `trusted` — reopening the T11 rogue-representative attack for MCP-only
  publishers. It now gates on presence, not length. (`kcp render`)
- **Parser coercion parity (all four).** A YAML scalar where `aliases`/`serving` expect a list, and
  non-string list entries, now resolve identically across TypeScript, Python, and Java: a non-list
  is *absent* (never coerced into a one-element list) and non-string entries are dropped. Java's
  `serving:` cast is now type-guarded so a non-object block can no longer throw. Previously Java
  coerced scalars while TS/Python dropped them — the same signed bytes could resolve to different
  trust decisions per implementation.
- **Bridge `get_unit` parity.** All three bridges now match `unit_id` exactly (no whitespace
  trimming — Python previously trimmed) and never resolve an empty `unit_id`.

### Clarified / hardened (spec text)

- **§3.12 `serving.mcp` honesty.** C22 covers only `serving.manifest`; the spec now states plainly
  that `serving.mcp` has no MUST-level consumer enforcement in this version (only a server-side
  startup SHOULD-warn and an optional client-side check), so the rogue-MCP-proxy case is closed only
  for clients that independently re-fetch and compare.
- **§3.12 scoping of "can only add protection."** True only for verifiers predating the block; for a
  v0.26 verifier an incomplete/stale `serving.manifest` demotes legitimate retrievals. Added an
  operator note (enumerate every CDN edge/mirror/redirect target; exact post-redirect matching, no
  normalization) and two limitations: `serving` is consultable only after signature verification
  (no origin bootstrap), and it binds location not recency (a once-listed URL can replay old signed
  bytes — anti-rollback needs §4.22 pinning).
- **§4.2a alias char rule.** Corrected the prose: an alias rule (`^[a-z0-9][a-z0-9._-]*$`) is *close
  to but not identical* to the `id` rule — it permits an underscore and forbids a leading dot/hyphen.
- **§4.2a uniqueness reconciled.** A collision is a §7 **warning** (not "MUST reject", which
  contradicted the field prose); resolution now defines a deterministic tie-break (canonical `id`
  first, then first-declared alias) so the outcome never depends on map ordering. RFC-0023's
  conformance line updated to match.
- **§4.2a resolver-side MUST.** Aliases are barred as `depends_on` / `supersedes` / `overrides` /
  `excludes` / **`external_depends_on`** targets not just for authors but for resolvers: reference
  resolution MUST match only canonical `id`, never aliases, including across federation boundaries
  (closes a cross-manifest reference-hijack).
- **§4.2a citation integrity.** An alias asserts *resolution*, not content-span containment;
  `content_hash` covers the whole unit. Audit/citation systems MUST NOT treat an alias match as
  evidence the content contains the aliased element.

---

## [0.26.0] — 2026-07-14 — Serving Endpoint Binding + Unit Aliases

**Spec version:** `"0.26"` | **Prior:** `"0.25"` (v0.25.1, 2026-07-04)

A combined promotion wave of two independent, additive declarations. Both live inside the signed
manifest bytes, both are OPTIONAL, and a verifier predating them ignores them (unknown-field rule) —
they can only *add* capability, never make a conforming manifest behave differently.

### Promoted — Serving Endpoint Binding (RFC-0024, §3.12)

- **`serving.manifest` / `serving.mcp`** — a signed, in-manifest declaration of the HTTPS URLs at
  which the manifest is *authoritatively served* and the MCP endpoints authorized to represent it.
  A KCP signature proves **who** signed and that the bytes are intact; `serving` adds **where** the
  knowledge web is legitimately served. Each declared list is exhaustive for its class; every entry
  MUST be HTTPS (an `http://` entry is a §7 validation error).
- **C22 (§16.5) — retrieval-URL demotion.** When a verifier retrieved the manifest over HTTP(S),
  `serving.manifest` is declared, and the final post-redirect URL is **not** in that list (per the
  §3.12 matching rules — lowercase scheme/host, drop default `:443`, strip query/fragment, exact
  path, no wildcards), a render or plan that would tier `trusted` is demoted to `known` and a
  warning names both the retrieval URL and the declared list. Closes **T11** (rogue-representative:
  a genuinely-signed manifest fronted by an endpoint the signer never authorized), the network-layer
  sibling of T9 (§4.21) and T10 (§3.11).
- **`kcp render --retrieved-from <url>`** — surfaces `serving` as data and records a `serving_check`
  (`match` / `mismatch` / `not_declared`) in the `trust` block; enforces C22 deterministically and
  offline (the URL is a caller-supplied input, never fetched by the renderer). Local retrieval
  (file paths, git checkouts) stays out of scope — governed by RFC-0019 origin evidence.

### Promoted — Unit Aliases (RFC-0023, §4.2a)

- **`aliases`** on a unit — additional identifiers that resolve to the **same unit** as its
  canonical `id`. Lets consumers reference fine-grained logical subdivisions (regulation
  sub-clauses, standard clauses, contract paragraphs, individual API endpoints) while content stays
  at its natural authoring granularity — no file-per-sub-clause sprawl, no undeclared
  suffix-stripping heuristics. Each alias follows the `id` character rules and MUST be unique across
  all ids **and** aliases (a collision is a §7 warning). `id` remains canonical: aliases are NOT
  valid targets for `depends_on`, `supersedes`, `relationships`, `overrides`, or `excludes`, and
  they share their unit's temporal window and `content_hash`.
- **Bridge resolution (all three) —** `get_unit` resolves a declared alias to its canonical unit and
  leads the response with a `{ matched_alias, canonical_id }` metadata block; `search_knowledge`
  matches alias terms (`alias` in `match_reason`) and reports `matched_alias` on the hit. A direct
  id lookup is unchanged (single content item, no metadata block).

### Parsers + validators (all four)

- Parse and expose `unit.aliases` and the manifest `serving` block. Validators warn on alias
  character-rule violations, alias/id collisions, and >100 aliases per unit (RECOMMENDED cap); they
  error on a non-HTTPS `serving.manifest`/`serving.mcp` entry. TypeScript (shared core → CLI +
  bridge), Python, Java.

### Renderer

- The `kcp render` whitelist (`render-schema.json`) surfaces `aliases` as a unit field and `serving`
  as a manifest block. `RENDERER_VERSION` → `kcp-cli 0.26.0`.

### Tooling

- `RFC-0023` and `RFC-0024` marked **Accepted**. Version strings bumped to `0.26.0` across the CLI,
  all three bridges, and the JSON-schema `kcp_version` enum. New example
  [`examples/serving-and-aliases/`](./examples/serving-and-aliases/) exercises both features
  end-to-end.

---

## [0.25.1] — 2026-07-04 — Interop Clarifications

**Spec version:** `"0.25"` (unchanged — clarifications only, no new fields) | **Prior:** v0.25.0 (2026-07-04)

Two normative clarifications surfaced by cross-testing the spec against the
[kcp-agent](https://github.com/Cantara/kcp-agent) reference agent, plus one advisory
validator warning. No behavioural change for conformant manifests.

### Clarified (kcp-agent interop, #114/#115)

- **§4.11 `access` declares the authentication gate only** — payment requirements are never
  expressed through `access`. An anonymous pay-per-request unit is `access: public` with a
  `payment` block (the RFC-0005 `none | x402` anonymous-micropayment cell), not
  `access: restricted`. Mirrored in the §4.14 auth-relationship paragraph. (#115)
- **§4.22 temporal boundaries are inclusive** — a unit is active for `valid_from <= d <=
  valid_until`, codifying what the query-time filter already encoded. Overlapping validity
  windows during transitions are resolved by supersession: a unit whose `superseded_by`
  successor is itself selectable SHOULD NOT be selected. (#114)

### Validators (all four)

- New §7 warning: units with `access: authenticated`/`restricted` in a manifest whose `auth`
  block declares only method `none` — no credential can satisfy the gate; the pattern a
  payment-as-access confusion produces. TypeScript (shared core, CLI + bridge), Python, Java.

---

## [0.25.0] — 2026-07-04 — Economic Metadata

**Spec version:** `"0.25"` | **Prior:** `"0.24"` (v0.24.0, 2026-07-04)

Promotes the economic layer RFC-0005 defined but the core spec only stubbed (`payment.default_tier`
and `rate_limits.default`). Lets an agent decide *what access costs* and *how much it can consume*
before issuing a request. Advisory throughout — **KCP declares the economics and settles nothing**;
the renderer surfaces the blocks as data and dereferences no wallet or pricing URL.

### Promoted (RFC-0005)

- **`payment.methods[]` (§4.14)** — ordered by publisher preference: `free`, `x402` (per-request
  micropayment: `currency`, decimal-string `price_per_request`, `networks`, `wallet`), `meter`
  (`provider`, `plans_url`), `subscription` (`plans_url`, `free_tier`, `free_requests_per_day`,
  `upgrade_url`). Plus **`payment.billing_contact`**.
- **`rate_limits` per-tier (§4.15)** — `authenticated` and `premium` tiers, `requests_per_hour`,
  the `unlimited` sentinel, a `tokens` sub-block for LLM pipelines, response-header mapping
  (`headers`), and a recommended `backoff` strategy.
- Unit-level `payment`/`rate_limits` overrides **replace** the root block (no merge), so mixed-
  economics manifests are unambiguous.

### Implementations

- **Parsers (all four):** TypeScript shared core, Python, Java, and the `kcp render` pipeline parse
  and expose the structured blocks (previously `payment` was an opaque passthrough). The renderer
  surfaces `payment` and `rate_limits` at manifest and unit level as data — never dereferenced.
- **Validators:** warn on an `x402` method missing `currency`/`price_per_request`, a non-decimal
  `price_per_request`, an unknown method `type`, a `metered`/`subscription` `default_tier` whose
  only method is `free`, and an off-vocabulary `backoff`.
- **Example + tutorial + demo:** [`examples/paid-knowledge-api/`](./examples/paid-knowledge-api/)
  (free + x402 + subscription tiers), [`guides/monetizing-knowledge-with-payment.md`](./guides/monetizing-knowledge-with-payment.md),
  and a runnable `demo.js` (+ a monetize stop in the grand tour and an economic-metadata reel on
  the showcase page).

### Open questions resolved conservatively

RFC-0005's open questions landed on the cautious side: methods stay **publisher-ordered** (agent
picks the first it supports); price stays a flat `price_per_request` + `currency` decimal string (no
structured amount object); agent-budget declaration and `on_limit_exhausted` are **out of scope**;
quota windows remain **advisory** (most-restrictive binds). RFC-0005 is now **fully promoted** —
nothing remains RFC-only.

---

## [0.24.0] — 2026-07-04 — Org-Federation

**Spec version:** `"0.24"` | **Prior:** `"0.23"` (v0.23.0, 2026-07-04)

Answers the enterprise-bootstrap question — how an agent that knows only a company domain finds
its first manifest, gets through the door, and progressively learns what else exists — by promoting
the two concrete fields from RFC-0011 to the §3.6 federation core. Declaration-level and advisory:
KCP surfaces them and never acts on them; **no new conformance rules**.

### Promoted (RFC-0011)

- **`manifests[].context` (§3.6)** — a list of environment labels (`dev`/`test`/`staging`/`prod`,
  non-normative) for which a sub-manifest reference is valid. One hub publishes a federation list
  that spans environments; an agent selects only the entries matching its runtime. Absent = valid
  everywhere.
- **`manifests[].agent_identity` (§3.6)** — a pre-fetch credential-planning hint (`required`,
  `credential_hint`, `issuer_hint`, `docs_url`). Tells a traversing agent what credential to
  acquire *before* it fetches a sub-manifest, so it plans instead of failing a fetch and
  backtracking. A declaration layer, not an auth protocol — the sub-manifest's own `auth` block
  (§3.3) remains the enforcement point.

The **Org Hub** (`network.role: hub` + a public front-door unit + a `manifests[]` block) and
**progressive disclosure** (`public` → `internal` → `confidential` sensitivity tiers) patterns ship
as usage conventions layered on existing fields — no new spec surface.

### Implementations

- **Parsers (all four):** TypeScript shared core, Python, Java, and the `kcp render` pipeline parse
  and expose both fields. The renderer surfaces `context` and `agent_identity` as data and never
  dereferences `docs_url` or `issuer_hint` (deterministic, network-free — consistent with C19).
- **Validators:** warn on empty `context`, `agent_identity.required` without a `credential_hint`,
  and `issuer_hint` used with a `credential_hint` other than `oauth2`.
- **Example + tutorial:** [`examples/org-federation/`](./examples/org-federation/) (a runnable hub)
  and [`guides/enterprise-discovery-with-org-federation.md`](./guides/enterprise-discovery-with-org-federation.md).

### Open questions resolved conservatively

RFC-0011's open questions (normative vs free-string vocabularies; Org-Hub-as-Level-4) landed on the
cautious side: both the `context` and `credential_hint` vocabularies ship **non-normative** and
extensible, and **no new conformance level** is introduced — parse-and-expose is Level 1.

---

## [0.23.0] — 2026-07-04 — Trust & Auth Completion

**Spec version:** `"0.23"` | **Prior:** `"0.22"` (v0.22.0, 2026-07-04)

Finishes the trust/auth story opened across v0.16–v0.22 and reconciles the RFCs with what
actually shipped. Declaration-level fields — no new gating and **no new conformance rules**; the
security *enforcement* was v0.16–v0.22's job.

### Promoted (RFC-0002 + RFC-0004)

- **Per-unit `auth` override (§3.3)** — a unit's `auth` block overrides the root `auth.methods`
  for that unit alone (multi-tenant sources).
- **`trust.audit.provides_access_receipts` / `receipt_format` (§3.2)** — declares the source
  issues verifiable access receipts and their format (`jws`, `vc`, or a spec URL). A capability of
  the source, not a requirement on the agent.
- **`trust.provenance.publisher_did` (§3.2)** — a W3C DID for the publisher, complementing
  `publisher`/`publisher_url` with a cryptographically resolvable identity.
- **`require_delegation_proof` (§3.4)** — was in the delegation block's example but missing from
  the field-reference table *and* every parser; now documented and implemented.

### RFC drift audit

The v0.23 pass reconciled the RFC headers with the spec: RFC-0002/0004 "Still RFC-only" lists
were stale — per-unit `delegation` (§3.4) and per-unit `compliance` (§3.5) were **already
promoted** but still marked pending. Corrected; the four fields above marked Accepted → v0.23.

### Implementations

- All four parsers (shared-TS, Python, Java) parse and expose the new fields; validators warn on
  a non-DID `publisher_did` and on `provides_access_receipts` without a `receipt_format`.
- The renderer surfaces `publisher_did` through the provenance passthrough (`render-schema.json`).
- `knowledge-schema.json` updated; cross-language tests added. Packages bumped to 0.23.0.

---

## [0.22.0] — 2026-07-04 — Trust & Attestation

**Spec version:** `"0.22"` | **Prior:** `"0.21"` (v0.21.0, 2026-06-13)

The consumer-identity half of the security model. Where v0.16–v0.21 answered "is this knowledge
intact and current?", v0.22 answers "who may consume it, and how do they prove it?" — promoting
the attestation half of RFC-0004 and the extended-auth remainder of RFC-0002.

**Load-bearing principle: KCP *declares* trust requirements; it never *performs* auth.** The
renderer never dereferences `attestation_url` (deterministic/network-free, C1/C7); the bridge
gates restricted-unit content on a *presented* credential but never verifies it. Agents attest.

### Spec — RFC-0004 + RFC-0002 promotion

- **`trust.agent_requirements` (§3.2)** — `require_attestation`, `trusted_providers` (identity),
  `attestation_url` + `attestation_jwks` (credential), and `propagate_to_governed`. Declares what
  an agent must prove about *itself* before a source serves `access: restricted` units — the
  consumer counterpart to `content_integrity`, which authenticates the producer.
- **Extended `auth.methods` types (§3.3)** — `bearer_token`, `spiffe`, `did`, `http_signature`
  (RFC 9421) promoted; `type` values beyond the now-seven defined remain silently ignored.
- **`governs` attestation propagation (closes #47)** — `agent_requirements.propagate_to_governed`:
  a governing manifest's attestation requirements become a floor on its governed sources (C21).
- **Conformance C19–C21 (§16.5).**

### Parsers (Level 1)

- All four implementations parse, expose, and validate `trust.agent_requirements` and the
  extended `auth.methods` sub-fields (`trust_domain`, `supported_methods`, `key_id`, `algorithm`).
- Validator warnings: non-HTTPS `attestation_url`/`jwks`; unsatisfiable `require_attestation`
  (no providers/url); `propagate_to_governed` with no `governs` relationship.
- `knowledge-schema.json` gains `trust_agent_requirements` + the new auth sub-fields.

### Renderer (C19)

- `kcp render` surfaces `trust.agent_requirements` as data and marks `access: restricted` units
  `requires_attestation: true` when the manifest requires it — copying declared strings verbatim,
  never dereferencing them. Load-eligibility is **not** gated on attestation (flag, not gate — the
  renderer can't attest; the bridge does the real gate). `render-schema.json` whitelists the block.

### Bridges (C20)

- All three MCP bridges refuse restricted-unit *content* on every retrieval path (`get_unit`,
  `read_resource`, `list_resources`) unless the client presents an `attestation` argument, which
  the bridge checks for presence only — it never calls `attestation_url`. `get_unit` returns
  `attestation_required` (surfacing the declared providers/url); `search_knowledge` marks restricted
  units `requires_attestation`. New `attestation` argument on `get_unit` + `search_knowledge`.
- Bridges bumped to 0.22.0 (parity rule).

---

## [0.21.0] — 2026-06-13 — Composition Integrity & Federation Temporal

**Spec version:** `"0.21"` | **Prior:** `"0.20"` (v0.20.0, 2026-06-12)

### Implemented — federated temporal filtering (C18) in all three bridges

- The TypeScript, Python, and Java MCP bridges now apply manifest-level (federation source)
  temporal filtering at query time (RFC-0021, SPEC §3.6 / §16.5 C18). Each `manifests[]`
  entry's `local_mirror` is associated with the sub-manifest loaded from it, so its units
  inherit the source's `temporal` window. On `search_knowledge`, a sub-manifest whose source
  window excludes the effective date (`as_of`, else today) is skipped *before* scoring and
  *before* unit-level temporal — none of its units are scored or returned. `include_all_temporal`
  bypasses the stage, consistent with the unit-level semantics. This is the resolution-time half
  of C18; parsers already exposed `manifests[].temporal` and detected `superseded_by` cycles.
- `list_manifests` now surfaces each federation entry's `temporal` block and a computed
  `temporally_active` flag (evaluated against today).
- Validated: 5 discriminating tests per bridge (mutation-verified) over a shared `fed-temporal`
  fixture — a compliance hub federating an expired GDPR-2018 corpus and an active GDPR-2023
  corpus with disjoint source windows, where neither corpus declares unit-level temporal so the
  source window is the only discriminator.

### Implemented — composition resolution + enforcing C17 in `kcp render`

- `kcp render` now resolves a `composition` block (RFC-0020/0022 §3.11): fetches/reads
  each `includes[]` source (local path or remote URL), verifies its `integrity` pin
  (`manifest_hash` or `expected_signer`), applies `as` namespacing, `overrides`, and
  `excludes`, then merges local units last. Network access is a pre-render step, so the
  render core stays deterministic (C1) and LLM-free (C7) — the same contract as RFC-0019
  corroboration.
- **C17 is now enforcing, not just spec text:** at `trusted` tier, units from an
  `unverified` or `failed` include render `load_eligible: false` (pointer only); a `failed`
  pin is demoted at every tier with a §7 warning. The composed *tier* is still the composing
  file's signature — only included-unit *load-eligibility* is gated. Closes the T10
  composition-substitution attack in code.
- Validated: 7 deterministic unit tests (`cli/src/render.test.ts`, mutation-verified) plus
  live harness cases **B21–B23** that serve the include over a local HTTP server (the real
  substitution channel). SPEC §16.5 and RFC-0022 updated from PENDING to implemented.

### Dogfooding — content integrity on the repo's own manifest

- The root `knowledge.yaml` now binds per-unit `content_hash` (RFC-0019, SPEC §3.2)
  over its 23 spec + RFC documents, so the Ed25519 signature covers the *content*
  of those files, not just the manifest bytes — the repo is now a real RFC-0019
  producer. `kcp render knowledge.yaml` returns `tier: trusted` with
  `content_verified: true` on the bound units.
- The `sign-manifests` workflow runs `kcp sign --update-hashes` (refreshing the
  digests before signing) and now also triggers on changes to `SPEC.md` / `RFC-*.md`
  so a document edit re-hashes and re-signs. (Closes the format half of #78: the
  workflow already emits the RFC-0018 §4.2 envelope `kcp render` verifies.)

### Added — Agent Skills (`skills/`)

- Four portable [Agent Skills](./skills/README.md) (`SKILL.md`) so users can work
  with KCP from Claude Code or any Skills-capable agent without standing up an MCP
  bridge: **kcp-adopt** (add a manifest to a project), **kcp-author** (write
  effective units), **kcp-navigate** (load only the units a task needs), and
  **kcp-render** (ingest an untrusted manifest through the trusted render pipeline).
  Complements the bridge's MCP prompts (`kcp-explore`, `sdd-review`). The navigate
  and render skills encode the protocol's guarantee that a manifest is data, never
  instructions.

### Implementation — temporal validation backlog (closes spec-vs-code gap)

Promoted-but-unimplemented normative rules from v0.19/v0.21 are now enforced in
all four reference implementations (TypeScript CLI + bridge, Python, Java):

- **Parsing parity (Level 1):** the CLI parser now reads unit- and root-level
  `temporal` (it silently dropped them before); all four parsers now expose
  `manifests[].temporal` (RFC-0021) and `discovery.verified_by` / `discovery.evidence`
  (RFC-0020 §2.3 / §4.18).
- **Unit-level temporal validation (§4.22):** `superseded_by` cycle detection
  (manifest **error**); §7 warnings for dangling `superseded_by`, stale `valid_until`
  (past with no successor), empty window (`valid_until` before `valid_from`), and
  `verification_status: verified` without `verified_by`. Root-level `temporal`
  defaults apply field-by-field.
- **Federation temporal validation (§3.6):** the same window/dangling warnings and
  `superseded_by` cycle error for `manifests[].temporal` entries.
- Cross-language test parity: matched temporal-validation suites added to CLI,
  Python, and Java (15 cases each). An outdated Java parser test fixture (a
  `verified` discovery with no `verified_by`) was made spec-compliant.

Both remaining serving-side features have since landed in this release: bridge
skip-before-fetch federation temporal filtering (RFC-0021 Level 2 / C18) and composition
resolution + C17 enforcement in the renderer — see the implementation subsections above.

### Federation temporal — RFC-0021 (promoted to SPEC §3.6, §16.5 C18)

- **`manifests[].temporal` block:** an OPTIONAL source-level validity window
  (`valid_from`, `valid_until`, `superseded_by`) on federation entries. Declares when a
  sub-manifest is relevant *as a knowledge source*, distinct from unit-level `temporal`
  (§4.22) which declares when individual knowledge is valid. Hub author controls source
  relevance; sub-manifest author controls unit validity.
- **Skip-before-fetch (Level 2):** bridges MUST NOT fetch sub-manifests outside their
  validity window for the effective date (`as_of` or today) — no HTTP request, no unit
  loading. `include_all_temporal: true` bypasses. Manifest-level temporal applies before
  unit-level temporal. Filtering removes sources only; it never elevates trust.
- **Validation:** `superseded_by` cycles among `manifests[]` entries are a manifest error;
  dangling/stale/empty-window cases are §7 warnings.

### Security — RFC-0022 Composition Integrity (promoted to SPEC §3.11, §16.5 C17, §4.22)

- **T10 (composition include substitution):** a `trusted`, signed manifest that
  `composition.includes` a source it does not authenticate would have its included
  units inherit the composing file's `trusted` tier and become load-eligible into
  standing context — even though the composing signature covers the `source:`
  directive, not the bytes the source resolves to. This is the RFC-0019 T9 pattern
  in the composition path. Found by analysis before composition resolution shipped
  in the renderer.
- **Fix (SPEC §3.11, §16.5 C17):** include integrity is now **enforcing** at
  `trusted` tier, not advisory. Units from an `unverified` or `failed` include
  render `load_eligible: false`; a `failed` include (present-but-mismatched pin)
  is demoted at every tier with a §7 warning — mirroring the RFC-0019 `content_hash`
  demotion (C11). The composed *tier* is still the composing file's (no transitive
  trust); only included-unit *load-eligibility* is gated.
- **Hash encoding unified (SPEC §3.11):** `composition.includes[].integrity.manifest_hash`
  changes from the `"sha256:<hex>"` string to the `{algorithm, value}` object used by
  RFC-0004 `content_integrity.manifest_hash` and RFC-0019 `content_hash`.
- **Temporal correction (SPEC §4.22):** removed the v0.19 §7 warning that fired when
  `recorded_at` was later than `valid_from` — that is RFC-0010's foundational
  retroactive-recording case, not an anomaly. Replaced with a warning for an empty
  validity window (`valid_until` earlier than `valid_from`).
- Regression guards added (`cli/src/consistency.test.ts`) asserting the enforcing
  composition language and the corrected temporal warnings remain in the spec.

### Notes

- The RFC-0021 and RFC-0022 spec promotions deliberately led the implementation: the
  `manifests[].temporal` parser exposure (Level 1), the bridge skip-before-fetch filter
  (Level 2 / C18), and the composition resolver (which C17 governs) all ship together in
  this release — see the implementation subsections above.

---

## [0.20.0] — 2026-06-12 — Temporal Query Release

**Spec version:** `"0.20"` | **Prior:** `"0.19"` (v0.19.0, 2026-06-12)

### What changed

- **`as_of` query parameter (§15.2, §15.13):** Point-in-time reconstruction for
  `search_knowledge`. Return only units whose `valid_from ≤ as_of` AND (`valid_until`
  is null OR `valid_until ≥ as_of`). Bridges without temporal evaluation MUST ignore
  the parameter.
- **`include_all_temporal` query parameter (§15.2, §15.13):** Audit mode — bypass
  temporal filtering, return all units with their full `temporal` metadata. Mutually
  exclusive with a non-default `as_of`.
- **`temporal_query_conflict` error:** Returned when both `as_of` (non-default) and
  `include_all_temporal: true` are present in the same request.
- **New §15.13:** Normative section for the two temporal query parameters.
- **§15.12:** Cross-reference to §15.13 added.
- **§16.5 C16:** Conformance requirement for bridges implementing temporal query evaluation.
- **RFC-0010 Accepted:** Query phase (as_of + include_all_temporal) promoted to SPEC.md
  §15.13 (schema phase already promoted in v0.19).

### RFC status
- RFC-0010: Accepted — fully promoted (schema: v0.19 §4.22; query: v0.20 §15.13)

---

## [0.19.0] — 2026-06-12 — Temporal Composition Release

### Spec

- **v0.19.0 — Temporal Composition Release** (RFC-0020 promoted)
  - **`temporal` block (§4.22):** optional at manifest root and at unit level. Declares two independent timelines per unit: *valid time* (`valid_from`, `valid_until` — when the knowledge is true in the real world) and *transaction time* (`recorded_at` — when this version was authored; `superseded_by` — id of the replacement unit). Root-level `temporal` provides defaults; unit-level overrides apply field-by-field. Parsers MUST read and expose `temporal` fields; bridges without temporal evaluation MUST treat all units as active (safe backward-compatible default). Bridges that implement temporal evaluation MUST filter: `valid_from <= today AND (valid_until IS NULL OR valid_until >= today)`. `superseded_by` cycles are a manifest error. Three new §7 advisory warnings: `superseded_by` referencing nonexistent id; `valid_until` in the past without `superseded_by`; `recorded_at` later than `valid_from`.
  - **`composition` block (§3.11):** optional root-level block. Declares how a manifest is assembled from other manifests via `includes[]` (relative path or URL, optional `as` namespace prefix, optional `integrity` pin), `overrides[]` (adapt units from included sources), and `excludes[]` (suppress units). Resolution order: includes → overrides → excludes → local `units[]`; completed before trust tiering. Trust tier derived from composing file's signature only — included sources with lower-tier signatures produce §7 warnings but do not lower the composed result's tier. Integrity-pin (`manifest_hash`, `expected_signer`) mismatches produce §7 warnings, not hard failures. `composition.overrides` MAY add a `temporal` block to a unit that did not originally have one. `superseded_by` MAY cross composition boundaries using `namespace:id` form.
  - **`discovery.verified_by` and `discovery.evidence` (§4.18 amendment):** two new OPTIONAL fields add attribution to the `verified` epistemic tier. `verified_by`: key id or agent identity of the verifier; SHOULD be present with `verification_status: verified` (§7 warning if absent). `evidence`: URL or path to the verification artifact.
  - **Temporal filter order (§15.12):** normative query filter order: score → `not_for` filter → temporal filter → top-N cut. Bridges MUST apply temporal filter after `not_for` and before top-N.
  - **Renderer conformance C15 (§16.5):** composition resolution completes before trust tiering; trust tier derived from composing file only; §7 warnings for integrity mismatches, circular includes, nonexistent override/exclude targets.
  - `KNOWN_KCP_VERSIONS` updated to include `"0.19"` in all validators. Appendix examples updated to `kcp_version: "0.19"`.

### RFC Status

- **RFC-0020 (Temporal Composition):** Accepted — promoted to SPEC.md §4.22, §3.11, §4.18 amendment, §15.12, §16.5 C15 (v0.19).
- **RFC-0010 (Bi-Temporal Unit Validity):** Accepted — schema phase promoted via RFC-0020/§4.22.
- **RFC-0014 (Manifest Composition):** Accepted — promoted via RFC-0020/§3.11.

### CLI (`kcp`) — v0.19.0

- `kcp validate` — extended: detects `superseded_by` cycles; warns on nonexistent `superseded_by` ids, stale-with-no-successor, `recorded_at > valid_from`, missing `verified_by` on `verified` status, and composition integrity mismatches.
- `kcp render` — extended: C15 composition resolution before trust tiering; integrity-pin warning emission.
- `kcp query` — temporal filter applied when bridge implements temporal evaluation.

### Parsers

- TypeScript, Java, Python: `temporal` and `composition` fields parsed and exposed; `KNOWN_KCP_VERSIONS` updated.

---

## [0.18.0] — 2026-06-12 — Unit Integrity Release

### Spec

- **v0.18.0 — Unit Integrity Release** (RFC-0019 promoted)
  - **`content_hash` (§4.21):** optional per-unit digest block (`algorithm: sha256 | sha384 | sha512`, `value: <hex>`). Because the block lives inside `knowledge.yaml`, the existing detached JWS covers it — signing the manifest now signs the expected content. Closes T9 (manifest relocation attack) for hashed units: a relocated genuine signed manifest whose unit paths resolve to attacker-authored files produces `content_hash_mismatch` for every hashed unit, demoting each to a pointer and recording the observed digest. `kcp sign` computes or refreshes `content_hash` before signing; `kcp validate` recomputes and compares.
  - **Origin evidence classes (§16.2):** each derived origin now carries an evidence class — `asserted` (consumer-provided `--origin`), `fetched` (federation channel), `derived` (git remote from checkout's own `.git/config`), or `none`. Trust-tier escalation to `trusted` requires `asserted` or `fetched` evidence; a manifest that would otherwise qualify with only `derived` evidence renders at `known` with `reason: origin_evidence_derived` (C13). Scope pinning (→ `failed`) accepts any evidence class.
  - **Corroboration (§16.2):** `kcp render --corroborate` upgrades `derived` to `fetched` by fetching and byte-comparing the manifest from the claimed origin. When `trusted` tier rests on a corroboration upgrade, standing context is restricted to units with a verified `content_hash` (C14) — corroboration confirms the manifest, not the checkout.
  - **Conformance C11–C14 (§16.5):** C11 (render-time hash verification), C12 (runtime re-verification before load), C13 (origin evidence gate for `trusted`), C14 (corroboration-only escalation restricted to hash-verified units).
  - `KNOWN_KCP_VERSIONS` updated to include `"0.18"` in all validators. Appendix examples updated to `kcp_version: "0.18"`.

### RFC Status

- **RFC-0019 (Unit Content Integrity and Origin Evidence):** Accepted — promoted to SPEC.md §4.21 and §16.2/§16.5 (v0.18). Validated by 7 new executable experiments (A10–A12, B17–B20) in `experiments/rfc-0018-render/`, including B20 (corroborated relocation: verbatim-copy attack corroborates clean but yields zero load-eligible units via C11+C14).

### CLI (`kcp`) — v0.18.0

- `kcp sign` — new command: computes `content_hash` for declared units and produces a detached JWS over the canonical manifest bytes.
- `kcp validate` — extended: recomputes and compares `content_hash` values; reports mismatches with expected and observed digests.
- `kcp render` — extended: C11 hash verification with mismatch recording, C13 origin evidence gate, `--corroborate` flag for evidence upgrade, C14 corroboration-rested escalation guard.

### Parsers

- TypeScript, Java, Python: `content_hash` field parsed and exposed; `KNOWN_KCP_VERSIONS` updated.

---

## [0.17.0] — 2026-06-11 — Content Wave

### Spec

- **v0.17.0 — Content Wave** (RFC-0015 + RFC-0016 promoted)
  - **`content_structure` (§4.19)**: unit-level block promoted from RFC-0016 declaring the internal modality composition and density of a unit's content — `primary` (dominant modality: `prose` | `table` | `code` | `list` | `diagram` | `reference` | `mixed`), `contains` (all modalities present, same vocabulary), `density` (`sparse` | `normal` | `dense`). Lets retrieval agents and RAG pipelines route queries and choose an extraction strategy before fetching. Parsers MUST read and expose all sub-fields; vocabulary values MUST come from the defined sets, with unknown values passed through (warn, not reject) for forward compatibility.
  - **`not_for` / `not_for_strict` (§4.20)**: unit-level negative-space declaration promoted from RFC-0015. `not_for` is a list of natural-language strings describing contexts the unit does NOT address — the spec's first **subtractive** matching field. Default behaviour is soft demotion with a `caution` annotation on a query match; `not_for_strict: true` makes a bridge MUST-exclude the unit from results on a match. Parsers MUST read and expose both fields and MUST NOT silently ignore `not_for` when scoring.
  - **Manifest-level `not_for` (§3.10)**: advisory root-level scope boundary for federation decisions — does NOT support `not_for_strict` (federation routing is always advisory).
  - **Query vocabulary (§15.11)**: negative-space filtering semantics — soft demotion vs strict exclusion evaluated after scoring (§15.4) and before the top-N cut; new `caution` response field; root-level `not_for` informs federation routing only. Subtractive filtering is a navigation convenience, not access control (§14.1).
  - New §7 advisory warnings for unknown `content_structure.primary`/`contains[]`/`density` values and for `not_for_strict` present without `not_for`. Level 2 conformance (§8) extended with both blocks.
  - `KNOWN_KCP_VERSIONS` updated to include `"0.17"` in all validators. Appendix examples updated to `kcp_version: "0.17"`.

### RFC Status

- **RFC-0015 (Negative Space Declarations):** Accepted — promoted to SPEC.md §4.20 (v0.17).
- **RFC-0016 (Content Structure Declaration):** Accepted — promoted to SPEC.md §4.19 (v0.17).

---

## [0.16.0] — 2026-06-11 — Trusted Ingestion Release

> Note: there is no 0.15 spec version. The `kcp` CLI release train had already used 0.15.0;
> this release re-synchronises spec and tooling version numbers.

### Spec

- **v0.16.0 — Trusted Ingestion Release** (RFC-0018 + RFC-0017 promoted, RFC-0004 `content_integrity` activated, RFC-0012 amended)
  - **Trusted Render Pipeline (§16)**: `kcp render` consumes `knowledge.yaml` and emits a derived, sanitized artifact — deterministic, LLM-free, fail-closed. Trust tiers (`trusted` | `known` | `unsigned` | `failed`, plus `unrendered` pseudo-tier) computed consumer-side over `trust.content_integrity`; producers cannot self-assign a tier. Scope pinning: an allowlisted key's `scope` creates a signing expectation — unsigned manifests from pinned origins render at `failed` (closes the signature-stripping downgrade). Normative origin derivation (explicit arg > federation URL > normalized git remote), with unknown-origin strict mode. Sanitization: render-schema whitelist, versioned imperative-mood lint with quarantine-not-reject, kind-based load eligibility (`service`/`executable`/unknown kinds never load-eligible at any tier). Federation: no transitive trust, no auto-traversal below `trusted`. Rendered artifacts are not self-authenticating: runtimes never ingest a render they did not produce or verify in-session.
  - **Observability (§17)**: local-first event store at `~/.kcp/usage.db` promoted to core (`usage_events`), extended with `render_events` and `quarantine_events` tables. Quarantine-count drift between commits is a CI-diffable security signal.
  - **`trust.content_integrity` (§3.2)**: activated from RFC-0004 — `manifest_hash`, `signing` (`method: jws | http_signature`), with detached JWS over canonical manifest bytes and `EdDSA` (Ed25519, RFC 8037) as the mandatory-to-implement algorithm.
  - **`discovery.verification_status: declared` (§4.18)**: first-party self-description by the publisher. Epistemic ordering `rumored < declared < observed < verified`; confidence SHOULD be in [0.5, 0.8). New §7 advisory warning for out-of-band confidence.
  - `KNOWN_KCP_VERSIONS` updated to include `"0.16"` in all validators. Appendix examples updated to `kcp_version: "0.16"`.

### RFC Status

- **RFC-0018 (Trusted Render Pipeline):** Accepted — promoted to SPEC.md §16 (v0.16). Validated by 22 executable experiments (21 pass, 1 documented known-gap) over threats T1–T8 in `experiments/rfc-0018-render/` (see RESULTS.md there; the known-gap is descriptive-mood injection passing the lint by design — C8 data-framing is the load-bearing control).
- **RFC-0017 (Observability Hooks):** Accepted — promoted to SPEC.md §17 (v0.16) with the two new render tables.
- **RFC-0004 (Trust and Compliance):** `trust.content_integrity` activated and promoted to SPEC.md §3.2; remaining blocks (access receipts, agent attestation, `publisher_did`) stay RFC-only.
- **RFC-0012 (Capability Discovery Provenance):** amended — `declared` added to the `verification_status` vocabulary.

### CLI (`kcp`) — v0.16.0

- `kcp render` — new command implementing SPEC.md §16: trust tiering with Ed25519 signature verification, scope pinning, origin derivation, imperative-mood lint (`imperative-lint-0.2`), render-schema sanitization, deterministic output. Exit code 2 with no output on `failed` tier.

### Experiments

- `experiments/rfc-0018-render/` — executable validation harness for the render pipeline: drives the shipping `kcp render` over a 22-case experiment matrix (legitimate use cases + threats T1–T8) with real per-run Ed25519 keys, mutation-tested expectations, generated RESULTS.md.

---

## [0.14.3] — 2026-03-27 — Observability + Content Structure + Negative Space

### RFC

- **RFC-0015 (Negative Space Declarations):** `not_for` — list of strings declaring what a unit does NOT address. `not_for_strict: true` for hard exclusion on match. First subtractive field in the spec. Open RFC.
- **RFC-0016 (Content Structure Declaration):** `content_structure` block on knowledge units: `primary` (dominant modality: prose/table/code/list/diagram/reference/mixed), `contains` (all modalities present), `density` (sparse/normal/dense). Lets RAG pipelines route before fetching. Open RFC.
- **RFC-0017 (Observability Hooks):** Local-first usage event schema in `~/.kcp/usage.db` (SQLite). Bridges MAY log `search` and `get_unit` events. Fields: `timestamp`, `event_type`, `project`, `query`, `unit_id`, `result_count`, `token_estimate`, `manifest_token_total`. Enables `kcp stats`. WAL mode required. Open RFC.

### Bridge (Java)

- `UsageLogger.java`: async SQLite logger (CompletableFuture, never blocks MCP responses). Schema init with WAL + indexes on startup.
- `KcpServer.java`: `manifestTokenTotal` computed once in `buildResources()`. `logSearch()` called in `handleSearchKnowledge()`, `logGetUnit()` called in `handleGetUnit()`.
- `ResourceSet` record: added `manifestTokenTotal` field.
- `sqlite-jdbc 3.49.1.0` added as production dependency.

### CLI (`kcp`) — v0.15.0

- `kcp stats` — new command showing queries served, units fetched, tokens saved, top units, top queries. Reads `~/.kcp/usage.db`. Flags: `--days N`, `--json`, `--project <name>`.
- `better-sqlite3` added as dependency (native addon, ships prebuilts for all platforms).

### Bug Fixes

- Python validator: `discovery.contradicted_by` now checked for type before set membership test — fixes `TypeError: unhashable type: 'list'` crash when value is a list.
- Example `scenario7`: `contradicted_by` corrected to single string (per spec).

---

## [0.14.0] — 2026-03-25 — Query Vocabulary Release

### Spec

- **v0.14.0 — Query Vocabulary Release** (RFC-0007 + RFC-0008 promoted, RFC-0014 published)
  - `query` block (§15): normative query vocabulary for agent manifest selection. Fields: `terms` (keyword match against triggers/intent), `audience` (filter by audience), `max_token_budget` (budget-constrained selection), `has_capabilities` (exclude units requiring capabilities the agent lacks), `exclude_stale` (drop units past `freshness_policy.max_age_days`), `federation_scope: declared` (expand to all sub-manifests in `manifests[]` in one hop).
  - `query_response` structure: scored results with `score`, `path`, `token_estimate`, `match_reason`, and `source_manifest` for federated results.
  - Normative rule: empty query matches all units.
  - Normative rule: `federation_scope: declared` results MUST include `source_manifest` field.
  - `KNOWN_KCP_VERSIONS` updated to include `"0.14"` in all three validators.
  - Appendix examples updated to `kcp_version: "0.14"`.

### RFC Status

- **RFC-0007 (Query Vocabulary):** Status updated to "Accepted — promoted to SPEC.md v0.14 §15".
- **RFC-0008 (Budget-Constrained Selection):** Status updated to "Accepted — promoted to SPEC.md v0.14 §15".
- **RFC-0014 (Manifest Composition):** Published as open RFC. Proposes `composition` block with three primitives: `includes` (pull base manifest by reference), `overrides` (modify unit-level metadata locally), `excludes` (suppress units by id). Open for community input — not yet promoted.

---

## [0.12.0] — 2026-03-17 — Governance Release

### Spec

- **v0.12.0 — Governance Release** (RFC-0009 schema wave + RFC-0012 full promotion)
  - `visibility` block on units and root (§3.8, §4.16): conditional access by `environment` and `agent_role`. First-match-wins condition evaluation. Replaces flat `sensitivity` for units where access depends on context.
  - `authority` block on units and root (§3.8, §4.17): action permission declarations (`read`, `summarize`, `modify`, `share_externally`, `execute`) with values `initiative` | `requires_approval` | `denied`. Safe defaults: read/summarize=initiative, all others=denied. Custom actions supported.
  - `discovery` block on units and root (§3.9, §4.18): capability provenance tracking. Fields: `verification_status` (rumored/observed/verified/deprecated), `source` (manual/web_traversal/openapi/llm_inference), `confidence` (0.0–1.0), `contradicted_by`, `observed_at`, `verified_at`. Enables automated manifest generators to express epistemic state.
  - Normative rule: `verification_status: rumored` MUST declare `confidence < 0.5`.
  - Normative rule: `verification_status: deprecated` SHOULD NOT be loaded for live operation.
  - `KNOWN_KCP_VERSIONS` updated to include `"0.12"` in all three validators.
  - 4 new conformance fixtures: `level3/valid-with-authority`, `level3/valid-with-discovery`, `level3/valid-with-visibility`, `level3/invalid-discovery-rumored-high-confidence`.
  - 7 new §7 validation warnings: discovery confidence normative rules, verified_at misuse, contradicted_by unknown reference, visibility condition shape, unknown authority values.
  - §8 Conformance: `authority` + `discovery` added to Level 2; `visibility` (with conditions) added to Level 3.
  - Appendix examples updated to `kcp_version: "0.12"`.

### RFC Status

- **RFC-0009 (Visibility and Authority):** Status updated to "Accepted — promoted to SPEC.md v0.12". Query extensions (agent_role, environment, authority_filter filters) remain deferred to v0.13.
- **RFC-0012 (Capability Discovery Provenance):** Status updated to "Accepted — promoted to SPEC.md v0.12".

### Parsers

- **Java parser**: `Visibility`, `Authority`, `Discovery` records added. `KnowledgeUnit` and `KnowledgeManifest` extended. `KcpParser` and `KcpValidator` updated.
- **Python parser**: `Visibility`, `Authority`, `Discovery` dataclasses added. `KnowledgeUnit` and `KnowledgeManifest` extended. Parser and validator updated.
- **TypeScript bridge model**: `Visibility`, `Authority`, `Discovery` interfaces added. `KnowledgeUnit` and `KnowledgeManifest` extended. Parser, validator, and mapper updated.

---

## [0.11.1] — 2026-03-17 — Housekeeping

### Fixed

- `docs/.well-known/kcp.json` `kcp_version` corrected from `"0.9"` to `"0.11"` — discovery endpoint now reflects the current spec version.
- `knowledge.yaml` (the KCP repo's own dogfood manifest) updated from `kcp_version: "0.10"` to `"0.11"`, version bumped to `0.11.0`.

### Infrastructure

- Node 24 upgrade merged (Renovate PR #42).
- vitest v4 PR #48 closed with note — will re-open automatically once Node 24 is active in CI.

---

## [0.11.0] — 2026-03-15 — Agent Readiness Release

### Spec

- **v0.11.0 — Agent Readiness Release** (RFC-0008 schema wave)
  - `freshness_policy` block on root and units (§3.7): `max_age_days`, `on_stale` (`warn`/`degrade`/`block`), `review_contact`.
  - `requires_capabilities` on units (§3.7): advisory capability list with `tool:` / `permission:` / `role:` prefix convention.
  - `network` field in `/.well-known/kcp.json` (§3.7): `role` (`hub`|`leaf`|`standalone`), `entry_point`, `registry_label`.
  - `kcp init` extended: generates `.well-known/kcp.json` with `network.role: standalone`; prints `llms.txt` snippet to stdout.
  - `KNOWN_KCP_VERSIONS` updated to include `"0.11"` in all three validators.
  - 4 new conformance fixtures: `level2/valid-with-freshness-policy`, `level2/valid-with-requires-capabilities`, `level3/valid-with-freshness-policy-root-default`, `level3/valid-with-freshness-and-capabilities`.

### CLI

- **`kcp reflect` subcommand** — session-end skill lifecycle reflection checklist.
  - Scans `~/.claude/skills/` (or `--skills-dir`) for recently modified and stale skills.
  - Prints a 4-item session-close checklist (repeated patterns, skill updates, overlap, dedup).
  - Reminds the agent of the recommended skill template shape (narrow trigger, do_not_use_for, lessons_learned, owner).
  - `--log` appends a timestamped entry to `.kcp/reflect-log.md` for audit trail.
  - 12 new tests in `test_reflect.py`.

### Parsers

- **Java parser**: `FreshnessPolicy` record added; `KnowledgeUnit` and `KnowledgeManifest` extended; `KcpParser.parseFreshnessPolicy()` added.
- **Python parser**: `FreshnessPolicy` dataclass added; `KnowledgeUnit` and `KnowledgeManifest` extended; `_parse_freshness_policy()` added.
- **TypeScript bridge model**: `FreshnessPolicy` interface added; `KnowledgeUnit` and `KnowledgeManifest` extended; `parseFreshnessPolicy()` added.

---

## [0.14.0] — 2026-03-15 — Query Baseline Release

### Bridges

- **RFC-0007 query baseline — all three bridges now at full parity.**
  - `search_knowledge` tool added to Python bridge (was absent).
  - `sensitivity_max` filter: excludes units whose sensitivity exceeds the declared ceiling (`public < internal < confidential < restricted`).
  - `exclude_deprecated` filter: excludes units with `deprecated: true` by default (pass `false` to include).
  - `match_reason` field in results: list of scoring rules that fired (`trigger`, `intent`, `id`, `path`).
  - `token_estimate` field in results: exposes `hints.token_estimate` for budget-aware selection without a second lookup.
  - `summary_unit` field in results: exposes `hints.summary_unit` for budget-constrained substitution.
  - 14 new tests across all three bridges covering the new filters and result fields.

- **TypeScript bridge** (kcp-mcp 0.14.0): 160 tests passing.
- **Java bridge** (kcp-mcp 0.14.0): 145 tests passing.
- **Python bridge** (kcp-mcp 0.14.0): 61 tests passing.

---

## [0.10.0] — 2026-03-13 — Discovery & Versioning Release

v0.10.0 adds federation version pinning, a query vocabulary RFC, an instruction file bridge guide, and `kcp init` specification. Zero breaking changes.

### Added

- **Federation version pinning (section 3.6)** -- `version_pin` (string) and `version_policy` (exact/minimum/compatible) fields on `manifests[]` entries. Validators emit WARNING on mismatch, never reject. `local_mirror` takes precedence over version checking.
- **RFC-0007: Query Vocabulary** -- normative query semantics for pre-invocation capability discovery. Defines request shape (terms, audience, scope, sensitivity_max, max_token_budget), response shape (scored results with match_reason), and scoring algorithm (trigger: 5pts, intent: 3pts, id/path: 1pt).
- **Instruction File Bridge guide** -- `guides/instruction-file-bridge.md` documents how to generate vendor-specific instruction files (CLAUDE.md, copilot-instructions.md, agents.json) from knowledge.yaml.
- **`kcp init` specification** -- added to the adopting guide. Levels 1-3, `--scan` flag for deeper file inspection, token estimation heuristic (file size / 4).
- **Conformance fixtures** -- `valid-federation-version-pin.yaml` (Level 3) and `valid-federation-version-pin-mismatch.yaml` (edge case, warning not error).

### Changed

- `kcp_version` current value updated from `"0.9"` to `"0.10"` in spec, schema, examples, and all parsers/bridges.
- Known limitations in section 3.6 updated: version pinning is now supported; only peer-to-peer limitation remains.
- All conformance fixtures updated to `kcp_version: "0.10"`.

### Parsers

- **Java parser**: `ManifestRef` record extended with `versionPin` and `versionPolicy` fields; validator adds `VALID_VERSION_POLICIES` set and version pin warnings.
- **Python parser**: `ManifestRef` dataclass extended with `version_pin` and `version_policy` fields; validator adds `VALID_VERSION_POLICIES` set and version pin warnings.
- **TypeScript bridge**: `ManifestRef` interface extended; parser, validator, and server updated. `KNOWN_KCP_VERSIONS` includes `"0.10"`.
- **Java bridge**: `KcpServer` list_manifests tool includes `version_pin` and `version_policy` in output.
- **Python bridge**: mapper and server include `version_pin` and `version_policy` in federation output.

---

## [0.9.0] — 2026-03-10 — Federation Release

v0.9.0 promotes federation (RFC-0003) to the core specification. This is the first release using full semver.

### Added

- **Federation: `manifests` block (section 3.6)** -- root-level declaration of sub-manifests with `id`, `url`, `label`, `relationship`, `auth`, `update_frequency`, and `local_mirror` fields.
- **Federation: `external_depends_on` (section 3.6)** -- unit-level cross-manifest dependency with `manifest`, `unit`, and `on_failure` (skip/warn/degrade) fields.
- **Federation: `external_relationships` (section 3.6)** -- root-level cross-manifest relationships using the shared vocabulary (`enables`, `context`, `supersedes`, `contradicts`, `depends_on`, `governs`).
- **`governs` relationship type (section 5)** -- sixth relationship type added to the shared vocabulary. Available in both intra-manifest `relationships` and cross-manifest `external_relationships`.
- **`list_manifests` MCP tool** -- all three bridges (TypeScript, Java, Python) expose a tool that lists declared sub-manifests with their `id`, `url`, `label`, `relationship`, `has_local_mirror`, and `update_frequency`.
- **Cycle detection and fetch limits (section 3.6, section 14.3)** -- visited URL set per resolution session, max 50 manifests, 1MB max size, 10K unit limit, 10s fetch timeout.
- **`local_mirror` support** -- air-gapped/offline federation via local file fallback before remote fetch.
- **Manifest relationship vocabulary** -- `child`, `foundation`, `governs`, `peer`, `archive` for `manifests[].relationship`.
- **Conformance fixtures** -- 4 new Level 3 fixtures (federation-basic, federation-local-mirror, federation-external-relationships, with-governs) and 3 edge-case fixtures (federation-cycle, federation-diamond, federation-on-failure-degrade).
- **Enterprise federation example** -- `examples/federation/` updated with complete hub manifest demonstrating all federation features.

### Changed

- `kcp_version` current value updated from `"0.8"` to `"0.9"` in spec, examples, and all parsers.
- RFC-0003 status updated to "Promoted to core -- see SPEC.md section 3.6 (v0.9.0)".
- `governance` renamed to `governs` everywhere (verb form, consistent with relationship vocabulary).
- Federation topology changed from hub-and-spoke (RFC-0003 original) to DAG with local authority.

### Parsers

- **Java parser** (kcp-parser 0.1.0): `ManifestRef`, `ExternalDependency`, `ExternalRelationship` records; parser and validator updated. 90 tests passing.
- **Python parser** (kcp 0.1.0): `ManifestRef`, `ExternalDependency`, `ExternalRelationship` dataclasses; parser and validator updated. 100 tests passing.
- **TypeScript bridge** (kcp-mcp 0.11.0): model, parser, validator, mapper, server updated. 152 tests passing.
- **Java bridge** (kcp-mcp 0.11.0): `KcpServer` updated with `list_manifests` tool. 137 tests passing.
- **Python bridge**: server and mapper updated with `list_manifests` tool. 54 tests passing.

### Deferred

- Version pinning for remote manifests (planned for v0.10).
- Peer-to-peer cross-referencing without hub (future RFC).

---

## [0.8] — 2026-03-09 — Consolidation Release

v0.8 is a consolidation release that fixes spec debt, promotes `rate_limits` to core, and resolves parser/schema divergences. No breaking changes.

### Added

- **`rate_limits` block (§4.15)** — promoted from RFC-0005. Parsers and validators updated across Java, Python, and TypeScript.
  - `rate_limits.default.requests_per_minute` (OPTIONAL integer)
  - `rate_limits.default.requests_per_day` (OPTIONAL integer)
  - Available at root level (manifest default) and unit level (per-unit override).
- **`depends_on` relationship type (§5)** — added to the `relationships[].type` vocabulary alongside `enables`, `context`, `supersedes`, and `contradicts`.
- **Conformance fixtures** — two new Level 3 fixtures: `valid-with-rate-limits` and `valid-with-payment`.

### Fixed

- **JSON schema** — `human_in_the_loop` corrected from a string enum (`always/on-sensitive/never`) to an object with `required`, `approval_mechanism`, and `docs_url` fields, matching the spec and all parsers.
- **Section 14 numbering** — Security Considerations sub-sections were incorrectly numbered `13.1/13.2/13.3`; corrected to `14.1/14.2/14.3`.
- **`require_delegation_proof`** — removed from the normative field table (was listed but never implemented); moved to a "Known limitations" note in §3.4.
- **TypeScript validator divergences** — `version` field demoted from REQUIRED (error) to RECOMMENDED (warning), matching spec §6.2. Duplicate unit IDs demoted from error to warning, matching spec §7.
- **RFC promotion tables** — RFC-0002, RFC-0003, RFC-0004, RFC-0005 updated to reference current spec version `v0.8`. RFC-0005 gains a Promotion History section for `rate_limits`.

### Changed

- `kcp_version` current value updated from `"0.7"` to `"0.8"` in spec, examples, and all parsers.
- Conformance fixtures updated from `kcp_version: "0.7"` to `"0.8"`.

---

## [0.7] — 2026-03-07

- Promoted `delegation` block to core (§3.4): `max_depth`, `require_capability_attenuation`, `audit_chain`, `human_in_the_loop` (object form).
- Promoted `compliance` block to core (§3.5): `data_residency`, `sensitivity`, `regulations`, `restrictions`.
- `human_in_the_loop` is an object with optional `required` (bool), `approval_mechanism` (`oauth_consent`|`uma`|`custom`), and `docs_url` fields.
- Per-unit `delegation.max_depth` MUST NOT exceed root `delegation.max_depth`.
- Added `auth_scope` (unit-level, §4.11) to core.
- Added `trust.audit` fields `agent_must_log` and `require_trace_context` to core.
- Java, Python, and TypeScript parsers and validators updated.
- JSON schema updated for all v0.7 fields.

## [0.6] — 2026-03-05

- Promoted `auth` block to core (§3.3): `methods[]` with types `none`, `oauth2`, `api_key`.
- Promoted `access` (unit-level) and `auth_scope` companion field.
- Added `trust.provenance` (`publisher`, `publisher_url`, `contact`) to core.
- Added `sensitivity` (unit-level, four values: `public`, `internal`, `confidential`, `restricted`).

## [0.5] — 2026-02-20

- `access` (unit-level) promoted to core with values `public`, `authenticated`, `restricted`.
- `trust.provenance` promoted to core.
- `sensitivity` (unit-level) promoted to core.
- Conformance test suite introduced (levels 1–3).

## [0.4] — 2026-02-10

- `hints` block promoted to core (§4.10): `summary_available`, `summary_unit`, `chunk_of`, `chunk_index`, `total_token_estimate`.
- `triggers` (unit-level, §4.9) promoted to core: max 20 triggers, max 60 chars each.

## [0.3] — 2026-02-01

- `indexing` shorthand vocabulary introduced (`open`, `read-only`, `no-train`, `none`).
- `update_frequency` vocabulary introduced.
- `depends_on` (unit-level list of unit IDs) added with cycle detection (§4.7).

## [0.2] — 2026-01-20

- `kind` and `format` vocabularies added to units.
- `audience` field introduced with initial vocabulary.
- Relationship types: `enables`, `context`, `supersedes`, `contradicts`.

## [0.1] — 2026-01-10

- Initial draft. Core unit fields: `id`, `path`, `intent`, `scope`, `audience`.
- Root fields: `project`, `version`, `updated`.
- `/.well-known/kcp.json` discovery path.
