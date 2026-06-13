# RFC-0022: Composition Integrity

**Status:** Accepted — promoted to SPEC.md §3.11, §16.5 C17, §4.22 correction (v0.21).
**Author:** Thor Henning Hetland (eXOReaction AS)
**Created:** 2026-06-13
**Target version:** v0.21
**Depends on:** RFC-0018 (Trusted Render Pipeline), RFC-0019 (Unit Content Integrity and Origin Evidence), RFC-0020 (Temporal Composition)
**Amends:** RFC-0020 (§2.4/§3.11 — makes include integrity enforcing at `trusted` tier; unifies the hash encoding), RFC-0018 (§2 threat model adds T10; §16.5 adds C17), SPEC.md §4.22 (corrects the `recorded_at` advisory warning)
**Related:** RFC-0004 (Trust and Compliance), RFC-0003 (Federation), RFC-0014 (Manifest Composition)

---

## Summary

RFC-0019 closed the manifest-relocation attack (T9) by establishing a single
invariant for the render pipeline:

> Content that enters an execution-capable agent's standing context at `trusted`
> tier MUST be authenticated by the trusted key — the signature must cover the
> *territory* (the bytes loaded), not merely the *map* (the manifest that points
> at them).

RFC-0020's manifest composition (§3.11) reopens exactly that hole through a new
door. A signed, `trusted` manifest may `include` units from another source — a
local path or a **remote URL** — and the composed result is tiered from the
*composing* file's signature. But that signature covers the composing file's
bytes, which contain the `source:` directive, not the bytes *at* that source.
The composition author's `integrity` pin — the one mechanism that would
authenticate the included content — is specified as an **advisory warning only**
(§3.11, C15): a mismatch is logged, the composed units remain `trusted` and
load-eligible.

This is the T9 pattern, one version later, in the same pipeline — and unlike
T9 it is *non-enforcing by construction*. This RFC names the attack (**T10**),
makes include integrity **enforcing** at `trusted` tier using the same
demote-to-pointer mechanism RFC-0019 uses for `content_hash` (C11), and
unifies the composition hash encoding with the rest of the spec.

It also corrects a temporal-validity warning promoted in v0.19 that fires on
RFC-0010's own foundational use case (§4).

All changes preserve backward compatibility for manifests that declare no
`composition` block. This correction was specified *before* composition
resolution shipped in the reference renderer — the ideal time to fix it — and
the enforcing C17 rule is now implemented in `kcp render` (§5, harness B21–B23).

---

## 1. Threat Model Addition

This RFC adds one row to RFC-0018 §2:

| # | Threat | Vector |
|---|--------|--------|
| T10 | Composition include substitution | A `trusted` composing manifest includes a source it does not authenticate; an attacker who controls, MITMs, or supply-chain-poisons that source injects units that inherit the composing file's `trusted` tier and enter standing context |

### 1.1 Why the composing signature does not cover the include

The detached JWS over a composing manifest `M` (RFC-0018 §4.2) authenticates
`M`'s canonical bytes. Those bytes contain:

```yaml
composition:
  includes:
    - source: https://raw.githubusercontent.com/acme/kcps/main/platform/knowledge.yaml
      as: platform
```

The signature proves the composing author wrote *this `source:` line*. It does
not — and cannot — prove anything about the bytes the URL resolves to at render
time. Resolution (§3.11: "MUST complete before trust tiering") fetches those
bytes, merges their units, and the render pipeline then tiers the *composed*
result from `M`'s signature → `trusted` → the `platform:*` units become
load-eligible into standing context (RFC-0018 §16.4).

The attacker needs no fabricated origin (unlike T9): they need only control the
included source, which is frequently a third-party URL outside the composing
author's signing domain. A dependency update, a compromised CDN, a typosquatted
raw-content host, or a force-push to the included repo all suffice.

### 1.2 Why advisory integrity is not a mitigation

RFC-0020 §2.4 / SPEC §3.11 make `integrity.manifest_hash` and
`integrity.expected_signer` *advisory*: "a mismatch produces a §7 warning but
does not lower the composed result's trust tier." A §7 warning does not change
`load_eligible`. The units still enter context. The control is observable but
not preventive — the opposite of how RFC-0019 treated the analogous boundary,
where a `content_hash` mismatch *forces* `load_eligible: false` (C11) and a
derived origin *caps* the tier (C13).

The existing C15 clause "never allows included sources with lower-tier
signatures to elevate the composed result's tier" guards only the *upward*
direction (a weak include cannot raise the result). T10 is the *downward*
direction: a strong composing signature laundering unauthenticated content into
trusted load-eligibility. C15 does not address it.

---

## 2. Design

The fix reuses RFC-0019's machinery rather than inventing a parallel one.

### 2.1 Integrity-verified includes

An `includes[]` entry is **integrity-verified** at render time iff at least one
of:

- **Hash pin:** `integrity.manifest_hash` is present and equals the digest of
  the included source's fetched raw bytes (using the unified `{algorithm,
  value}` shape, §2.3); **or**
- **Signer pin:** `integrity.expected_signer` is present, the fetched source
  carries a valid detached signature (its own `.sig` / `content_integrity`,
  RFC-0018 §4.2), and that signature is from the pinned key.

An entry with no `integrity` block is **unverified** (the author opted out of
authenticating it). An entry whose declared pin is present but does **not**
match is **failed** (positive evidence of substitution or drift).

### 2.2 Enforcing rule (C17)

Tier governs *placement*; integrity governs *whether included bytes may be
placed*, mirroring RFC-0019 §6.4 / C11.

- **Unverified include, `trusted` tier:** its units MUST render with
  `load_eligible: false` (pointer only). The author may still compose them for
  structure and discovery; they simply do not enter standing context without
  authentication. Below `trusted`, no units are load-eligible anyway, so the
  rule is a no-op there (consistent with RFC-0019).
- **Failed include, any tier:** its units MUST render with `load_eligible:
  false` AND the renderer MUST emit a §7 warning recording the field
  (`manifest_hash` / `expected_signer`), the expected value, and the observed
  value. A present-but-wrong pin is stronger evidence than absence and is
  surfaced at every tier — exactly as a `content_hash` mismatch is (RFC-0019
  §3.3).
- **Verified include:** its units are load-eligible per their `kind` and the
  composed tier, identically to local units.

This is **pinned inclusion, not transitive trust** (the RFC-0020 framing is
preserved and now true): a verified include's units are load-eligible because
the composing author authenticated *these specific bytes*, not because the
pipeline trusts the source's key chain. `expected_signer` is an explicit
per-include delegation the composing author chose and signed over.

### 2.3 Determinism and the remote fetch

Verifying a remote include requires a network fetch, which must not enter the
deterministic render core (RFC-0018 C1, C7). As with RFC-0019 §4.3
corroboration, the fetch-and-compare is an **evidence-resolution step that
precedes rendering**; its outcome (`verified` / `unverified` / `failed` per
include) is part of the `(input, keys, renderer-version)` determinism triple's
input. A fixed resolution outcome yields byte-identical output.

A local-path include (`source: ./base/knowledge.yaml`) is verified the same
way, against the local file bytes — no network, fully offline, directly
analogous to an RFC-0019 directory `content_hash`. A local include is **not**
implicitly authenticated by proximity: the composing signature does not cover
sibling files, so an unpinned local include is `unverified` and follows the
same trusted-tier demotion. This keeps one rule for both source kinds.

### 2.4 Hash encoding unification

RFC-0020 §2.4 claims `integrity.manifest_hash` is "identical to
`trust.content_integrity.manifest_hash` semantics," but encodes it as a single
colon-prefixed string (`"sha256:a1b2c3…"`), whereas RFC-0004
(`content_integrity.manifest_hash`) and RFC-0019 (`content_hash`) both use an
`{algorithm, value}` object. The byte-level semantics match; the field *shape*
does not, so an implementation cannot mechanically reuse the RFC-0004/0019 hash
path against a composition pin.

This RFC unifies on the object shape:

```yaml
composition:
  includes:
    - source: https://…/platform/knowledge.yaml
      as: platform
      integrity:
        manifest_hash:
          algorithm: sha256          # sha256 | sha384 | sha512 (RFC-0004 §content_integrity)
          value: "a1b2c3d4e5f6…"     # hex digest of the source's raw bytes
        expected_signer: "cantara-platform-2026"   # allowlist key_id (RFC-0018 §9)
```

Because composition resolution is unimplemented, there is no migration cost.
Renderers MUST accept the `{algorithm, value}` form; the `"sha256:<hex>"`
string form is removed.

`expected_signer` is a `key_id` joining against the consumer allowlist
(RFC-0018 §9), not a raw public key — so the consumer's own allowlist decides
which signer identities are acceptable, consistent with how tiering already
resolves keys.

---

## 3. Normative Specification

Amends SPEC.md §3.11 (replace the advisory-integrity bullets) and §16.5 (add
C17). The `composition` block shape is otherwise unchanged from §3.11.

- `includes[].integrity.manifest_hash`: OPTIONAL `{algorithm, value}` object.
  `algorithm` ∈ `{sha256, sha384, sha512}`; `value` hex. Computed over the
  included source's raw file bytes (before the source's own composition
  resolution, if any), matching RFC-0004 `content_integrity.manifest_hash`.
- `includes[].integrity.expected_signer`: OPTIONAL string `key_id`.
- An include is **verified** iff a present `manifest_hash` matches the fetched
  bytes, or a present `expected_signer` matches a valid signature on the
  fetched source. No `integrity` block ⇒ **unverified**. Present-but-mismatched
  ⇒ **failed**.
- **C17 (renderer):** When the composed manifest tiers `trusted`, units from an
  `unverified` or `failed` include MUST render `load_eligible: false`. Units
  from a `failed` include MUST be `load_eligible: false` at every tier and MUST
  emit a §7 warning recording field, expected, and observed values.
- The trust tier of the composed result is still derived from the composing
  file's signature only (no transitive trust). Integrity gating changes
  *load-eligibility of included units*, never the composed *tier*.
- Resolution determinism: remote-include verification is a pre-render
  evidence-resolution step; its per-include outcome is a render input (C1
  preserved).

C15 is retained but no longer load-bearing for security: its "advisory
warning" disposition applies to the *recording* of mismatches; C17 governs the
*load-eligibility* consequence.

---

## 4. Temporal Correction (SPEC §4.22)

v0.19 promoted four §7 advisory warnings on the `temporal` block. One is wrong:

> `recorded_at` is later than `valid_from` (manifest authored after the fact it
> describes)

This fires on RFC-0010's *defining* bi-temporal scenario. RFC-0010 §"The two
clocks problem":

> A security policy was effective February 15th, but the manifest author did
> not update `knowledge.yaml` until March 1st.

Here `valid_from` = Feb 15, `recorded_at` = Mar 1: `recorded_at > valid_from`,
the retroactive-recording case transaction time exists to capture. RFC-0010's
own Example 1 (`deploy-to-production`: `valid_from: 2026-02-01`, `recorded_at:
2026-02-03`) trips it too. Neither direction of `recorded_at` vs `valid_from`
is anomalous — future-dating records before validity, retroactive recording
records after — so the warning punishes honest audit trails and trains authors
to fudge `recorded_at`.

**Change:** remove the `recorded_at`-vs-`valid_from` warning. Replace it with a
warning that flags an actually-impossible window, which the spec currently does
*not* catch:

> `valid_until` is earlier than `valid_from` (empty validity window — the unit
> can never be active)

The other three v0.19 temporal warnings (dangling `superseded_by`, stale
`valid_until` with no successor, `verified` without `verified_by`) are correct
and retained.

---

## 5. Conformance

Extends RFC-0018 §10 / SPEC §16.5:

- **C17.** When the composed manifest tiers `trusted`, the renderer never emits
  `load_eligible: true` for a unit originating from an `unverified` or `failed`
  composition include; records a §7 warning for every `failed` include with
  field, expected, and observed values.

Test corpus (implemented under `experiments/rfc-0018-render/` — the includes are
served over a local HTTP server, the real substitution channel — and as
deterministic unit tests in `cli/src/render.test.ts`):

- **B21** — T10 substitution: a `trusted` composing manifest with an unverified
  remote include → its units `load_eligible: false` (pointer), §7 warning. (Without
  the rule they would be `load_eligible: true`.)
- **B22** — verified include: same composition with a matching `manifest_hash`
  → composed units load-eligible.
- **B23** — failed pin: `manifest_hash` present but not matching the fetched
  bytes → units `load_eligible: false` at every tier, §7 warning recorded.

A guard test (`cli/src/consistency.test.ts`) asserts the enforcing language is
present in SPEC §3.11/§16.5 and that the corrected §4.22 warning set is in
effect — so neither the security regression nor the temporal false-positive can
silently return.

---

## 6. Deferred

- **Composition resolution is implemented** in `kcp render` (includes/overrides/
  excludes, `as` namespacing, local + remote fetch, integrity verification, C17
  enforcement). **Recursive composition** (includes within an included manifest)
  is resolved one level only; deeper nesting and its cycle detection remain
  future work (Level 2).
- **`verified_by` / `evidence` enforcement.** RFC-0020 §2.3 added these
  attribution fields advisorily; tightening them (e.g. requiring `verified_by`
  to be an allowlist `key_id`) is out of scope here.
- **`verified_by` / `evidence` enforcement.** RFC-0020 §2.3 added these
  attribution fields advisorily; tightening them (e.g. requiring `verified_by`
  to be an allowlist `key_id`) is out of scope here.
- **Transitive trust for composition.** Unchanged: no key-chain trust
  propagates. `expected_signer` is an explicit per-include delegation, not a
  trust chain.

---

## Appendix A: Relationship to RFC-0019

| RFC-0019 (local content) | RFC-0022 (composed content) |
|--------------------------|------------------------------|
| `content_hash` on a unit binds referenced file bytes to the signed manifest | `integrity.manifest_hash` on an include binds source bytes to the composing manifest |
| Mismatch → `load_eligible: false` (C11) | Unverified/failed include → `load_eligible: false` (C17) |
| Origin evidence caps tier (C13) | Composed tier unchanged; load-eligibility gated instead |
| Network corroboration is a pre-render step (C1 preserved, §4.3) | Remote-include verification is a pre-render step (C1 preserved, §2.3) |
| `{algorithm, value}` hash shape | unified to `{algorithm, value}` (§2.4) |

The two RFCs together state the full invariant: **whether content reaches an
agent's standing context from a local file or a composed include, at `trusted`
tier it must be authenticated against bytes the trusted key covered.**

---

*Co-authored with Claude. The design and normative choices are the author's;
Claude performed the analysis that surfaced T10 and drafted this document.*
