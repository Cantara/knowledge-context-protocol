# RFC-0019: Unit Content Integrity and Origin Evidence

**Status:** Draft
**Author:** Thor Henning Hetland (eXOReaction AS)
**Created:** 2026-06-12
**Target version:** v0.18
**Depends on:** RFC-0004 (Trust and Compliance), RFC-0018 (Trusted Render Pipeline)
**Amends:** RFC-0018 (adds T9 to the threat model; refines §4.1 origin determination with evidence classes; adds conformance C11–C13), RFC-0004 (resolves open question 3: per-unit content hashes)
**Related:** RFC-0003 (Federation), RFC-0012 (Capability Discovery Provenance)

---

## Summary

The trusted render pipeline (RFC-0018, SPEC §16) authenticates the
**manifest**: who signed it, and whether its bytes are intact. It does not
authenticate two things the `trusted` tier implicitly relies on:

1. **The files the manifest points to.** A signature over `knowledge.yaml`
   says nothing about the bytes at `units[i].path` when they are loaded.
2. **Where the manifest actually is.** Origin derivation for local
   checkouts reads the git remote URL — bytes that live *inside* the
   directory being evaluated, which the repository author controls in
   tarball and vendored-copy scenarios.

Combined, these admit a **manifest relocation attack** (T9): a genuinely
signed, allowlisted manifest is copied verbatim into an attacker-controlled
directory whose fabricated `.git/config` claims the original origin. Every
RFC-0018 check passes — valid signature, allowlisted key, origin in scope —
and the render reaches `trusted` tier while the unit paths resolve to
attacker-authored files, which then enter **standing context at the highest
placement the spec allows**.

This RFC closes both halves:

- **Per-unit content hashes** (§3) bind referenced content to the signed
  manifest, so relocated or post-render-swapped content fails closed
  per-unit. This activates the question RFC-0004 left open (its open
  question 3) and RFC-0018 re-raised (its open question 4).
- **Origin evidence classes** (§4) distinguish origins the consumer
  asserted or observed from origins merely *derived from repo-resident
  bytes*, and forbid trust-tier escalation on derived evidence alone.

Both mechanisms are deterministic and LLM-free, preserving RFC-0018's
render contract (C1, C7).

---

## 1. Problem Statement

### 1.1 T9: manifest relocation

RFC-0018's tier model has a deliberate asymmetry: spoofing an origin can
only make the outcome *stricter*. Faking a pinned origin without the
org's key yields `failed` (§4.1 scope pinning); faking an unpinned origin
changes nothing. An attacker cannot forge their way *up* the tier table —
as long as the signature and the origin are evaluated against content the
attacker authored.

The relocation attack breaks that assumption by authoring neither:

1. The attacker copies a **genuinely signed** manifest from a public,
   allowlisted organisation — say `github.com/Cantara/lib-pcb`'s
   `knowledge.yaml` and its detached JWS. Both are public artifacts;
   copying them verbatim preserves signature validity.
2. The attacker ships them in a tarball (or vendored subdirectory, or any
   distribution channel that carries a `.git` directory as plain files)
   whose fabricated `.git/config` declares
   `origin = https://github.com/Cantara/lib-pcb.git`.
3. The consumer's renderer derives the origin from that remote URL
   (RFC-0018 §4.1, derivation rule 3). Signature: valid. Key:
   allowlisted. Origin: within the key's scope. Tier: **`trusted`**.
4. The unit `path`s — `docs/setup.md`, `src/main/java/...` — resolve
   relative to the manifest's location. The attacker owns every byte at
   those paths.

Result: attacker-authored prose loads into standing context at `trusted`
tier. RFC-0018's C8 (content is data, never instructions, at every tier)
still applies and remains the last line of defense — but the entire point
of tiering *placement* is defeated: the strongest placement was granted to
content the signature never covered.

The same gap exists without any attacker, as drift: content edited after
signing is served under a signature that predates it. RFC-0018 §3.4
narrows this for the *manifest* (the `source.sha256` freshness check);
nothing narrows it for unit content.

### 1.2 Why the signature cannot be stretched to cover this

The JWS is detached over the canonical manifest bytes (RFC-0018 §4.2).
Re-signing on every content edit of every referenced file is the correct
*mechanism* — content hashes embedded in the signed manifest do exactly
that — but it must be an explicit, per-unit, opt-in declaration with
tooling support, not an implicit reinterpretation of what the existing
signature means. Deployed signatures must keep meaning what they meant.

### 1.3 Why origin derivation needs an evidence model

RFC-0018 §4.1 made origin derivation normative so two renderers pin
identically. Its three sources are not equally trustworthy, though the
current text treats them as a flat priority order:

| Source | Who controls the bytes consulted |
|--------|----------------------------------|
| Explicit `--origin` argument | The consumer (or its harness) |
| Federation fetch URL | The consumer's own network channel |
| Git remote `origin` URL | Whoever produced the directory contents |

In the common case — the consumer ran `git clone` themselves — the third
source is consumer-controlled too, because `git clone` writes the remote.
But the renderer cannot distinguish "I cloned this" from "this arrived as
a tarball with a `.git` directory inside." The bytes are identical. Only
the consumer's harness knows the provenance of the checkout, which is why
the fix is an evidence classification, not a different derivation order.

---

## 2. Threat Model Addition

This RFC adds one row to RFC-0018 §2:

| # | Threat | Vector |
|---|--------|--------|
| T9 | Manifest relocation | Genuine signed manifest + fabricated origin evidence + attacker-controlled files at unit paths ⇒ `trusted`-tier placement of unsigned content |

T9 is a hybrid: it uses T8's move (a repository-resident artifact
masquerading as an authority) against the *origin*, to weaponize the gap
T6 documented (the map is sanitized; the territory is not). Per-unit
hashes shrink the T6 surface for hashed units from "any content at the
path" to "the exact bytes the key-holder signed"; evidence classes deny
the tier escalation that makes T9 worth mounting.

Out of scope, unchanged from RFC-0018 §2.1: prose *inside* genuinely
signed content is still data-framed (C8), never linted. A hash
authenticates bytes; it does not make their prose safe to obey.

---

## 3. Per-Unit Content Hashes

### 3.1 Manifest field

A unit MAY declare a `content_hash` block:

```yaml
units:
  - id: gerber-output
    kind: knowledge
    path: src/main/java/no/exo/pcb/gerber/
    intent: "Gerber file generation and validation"
    content_hash:
      algorithm: sha256            # sha256 | sha384 | sha512 (RFC-0004 §content_integrity)
      value: "4be1d6…"             # hex digest per §3.2
```

Because the block lives inside the manifest, the existing detached JWS
covers it with no envelope change: signing the manifest now signs the
expected content. `kcp sign` (the workflow that produces `.sig` files
today) computes or refreshes `content_hash` for every unit that declares
one before signing; `kcp validate` recomputes and compares.

`content_hash` is OPTIONAL per unit. Hash churn is real friction — every
content edit requires re-hash and re-sign — and is only proportionate
where the payoff is standing-context placement. The expected profile:
organisations that sign at all (and therefore already run `kcp sign` in
CI, where the refresh is one flag) hash their load-eligible units;
unsigned manifests gain nothing from hashing and are not expected to.

### 3.2 Digest computation (normative)

Two conforming implementations must produce identical digests:

- **File path:** the digest is the hash of the file's raw bytes.
- **Directory path:** for every regular file under the path (recursive,
  symlinks not followed, no exclusions), compute
  `entry = relative_path + "\0" + lowercase_hex(hash(file_bytes)) + "\n"`
  with `relative_path` POSIX-separated and relative to the unit path.
  Sort entries bytewise. The digest is the hash of the concatenated,
  sorted entries.

No exclusion list: exclusions are where determinism dies. A directory
containing volatile files (build output, VCS metadata) is not a suitable
hash target; point the unit at a stable subtree instead, or hash a file.

### 3.3 Verification (renderer and runtime)

- **At render time**, the renderer MUST verify every declared
  `content_hash`. A mismatch does not fail the render (the manifest
  itself is intact); it fails the **unit**: `load_eligible` is forced to
  `false`, the unit renders as a pointer, and the event is recorded in
  the `sanitization` block with `reason: content_hash_mismatch` and the
  *observed* digest — giving the consumer the diff anchor.
- **At load time**, a runtime loading a unit's content MUST re-verify the
  hash against the bytes it is about to inject, not rely on the
  render-time check. This closes the render→load TOCTOU window that
  RFC-0018 open question 4 identified.
- A `trusted`-tier render MUST mark each rendered unit with
  `content_verified: true | mismatch | absent` (§5), so the agent-facing
  artifact distinguishes "bytes match the signature" from "the key-holder
  never made a content claim." Consumers MAY set
  `require_unit_hashes: true` to deny standing-context eligibility to
  `absent` units at `trusted` tier.

Verification is pure hashing: deterministic, LLM-free, offline. C1 and
C7 are unaffected.

### 3.4 Effect on T9

A relocated manifest with per-unit hashes is now inert even when the
origin spoof succeeds: the attacker's files do not match the signed
digests, every hashed unit demotes to a pointer, and the mismatch events
are themselves a loud relocation signal (every unit failing at once is
not drift). The attack only retains value against units the key-holder
left unhashed — which `require_unit_hashes` lets a consumer refuse.

---

## 4. Origin Evidence Classes

### 4.1 Classification

RFC-0018 §4.1's derivation order stands. Each derived origin now carries
an **evidence class**, recorded in the output (§5):

| Class | Source | Bytes controlled by |
|-------|--------|---------------------|
| `asserted` | Explicit `--origin` from the consumer or its harness | Consumer |
| `fetched` | Federation fetch: the URL the consumer's own channel retrieved | Consumer's channel |
| `derived` | Git remote URL read from the checkout's own `.git/config` | The directory's producer |
| `none` | No origin derivable (`origin: unknown`) | — |

The names deliberately avoid RFC-0012's `verification_status` vocabulary
(`observed`, `verified`) — the `verified`/`trusted` collision RFC-0018
had to rename its top tier over is not a mistake to repeat.

### 4.2 The escalation rule

> Scope **pinning** (RFC-0018 §4.1) accepts any evidence class: pinning
> only ever moves a render toward `failed`, and an attacker gains nothing
> by fabricating evidence that makes their own render stricter.
>
> Trust-tier **escalation** does not: the `trusted` tier's "origin within
> key scope" condition is satisfiable only by an origin with `asserted`
> or `fetched` evidence. A manifest that would otherwise qualify, but
> whose in-scope origin has only `derived` evidence, renders at `known`
> with `reason: origin_evidence_derived` recorded.

This is the same asymmetry RFC-0018 applied to signatures (gate, don't
endorse), applied to origins: repo-resident bytes may *restrict* the
repo's own trust, never extend it.

### 4.3 Origin corroboration

The legitimate case hit by §4.2 is a consumer who cloned the repository
themselves but whose harness did not pass `--origin`. Two remedies, in
preference order:

1. **Harness assertion (preferred).** The component that materialized the
   checkout knows the true source URL and SHOULD pass it as `--origin`.
   Agent harnesses, CI bootstrap, and `kcp`-aware wrappers all have this
   information at clone time. This keeps the renderer fully offline.
2. **Corroboration (convenience).** `kcp render --corroborate` fetches
   `knowledge.yaml` from the derived origin over the consumer's own
   channel and compares canonical bytes against the local manifest. On a
   match, the evidence upgrades to `fetched` (it has now been observed via
   a consumer-controlled channel); on mismatch or fetch failure, evidence
   stays `derived` and the outcome is recorded. Corroboration is
   conceptually an evidence-resolution step that *precedes* rendering;
   its result is part of the (input, keys, renderer-version) determinism
   triple's input, so C1 is preserved for a fixed corroboration outcome.

Air-gapped consumers who cannot assert or corroborate MAY configure
`allow_derived_origin: true`, accepting T9 exposure explicitly — the
knob exists so the default can stay safe.

Note the defense in depth: even where §4.2 is waived, §3's content
hashes independently neutralize T9 for hashed units. Either mechanism
alone degrades the attack; together they close it.

---

## 5. Render Output Changes

Additions to the RFC-0018 §5.2 artifact:

```yaml
trust:
  tier: trusted
  origin: "github.com/Cantara/lib-pcb"
  origin_evidence: asserted        # asserted | fetched | derived | none   (§4.1)
  pinned: true
  # …signature block unchanged…

units:
  - id: gerber-output
    kind: knowledge
    path: "src/main/java/no/exo/pcb/gerber/"
    intent: "Gerber file generation and validation"
    load_eligible: true
    content_verified: true         # true | mismatch | absent   (§3.3)

sanitization:
  dropped:
    - path: "units[4]"
      reason: content_hash_mismatch     # §3.3 — unit demoted to pointer
      expected_sha256: "4be1d6…"
      observed_sha256: "97c0a2…"
```

`content_hash_mismatch` entries record both digests; all other RFC-0018
output semantics (leaf-based stats identity, R1–R5) are unchanged.
`origin_evidence` joins `origin` and `pinned` in the auditable trust
record (extends R5).

Observability (RFC-0017 / RFC-0018 §8): `render_events` gains one
nullable column, `origin_evidence TEXT`, added lazily via
`ALTER TABLE` on first post-v0.18 render. Hash mismatches reuse
`quarantine_events` (`field_path = "units[4].content_hash"`,
`reason = "content_hash_mismatch"`, `original_sha256` = observed digest);
no new table. A repository whose mismatch count jumps from 0 to *all
units* in one commit is the T9 signature, diffable in CI exactly like
quarantine drift.

---

## 6. Conformance

Extending RFC-0018 §10:

- **C11.** Verifies every declared `content_hash` at render time;
  forces `load_eligible: false` with both digests recorded on mismatch;
  never emits `content_verified: true` without a matching digest.
- **C12.** (Runtime) Re-verifies the content hash against the exact bytes
  being injected at load time; refuses to load on mismatch.
- **C13.** Records `origin_evidence` for every render; never satisfies
  the `trusted` tier's scope condition with `derived` (or `none`)
  evidence unless the consumer has explicitly configured
  `allow_derived_origin: true`.

Test corpus (extend `experiments/rfc-0018-render/`):

- **A10** — signed manifest with per-unit hashes, intact content: renders
  `trusted`, all units `content_verified: true`.
- **A11** — directory-digest determinism: two implementations (or two
  runs over a re-created tree) produce identical digests per §3.2.
- **B17** — the T9 relocation: genuine signed manifest, fabricated
  `.git/config`, attacker files at unit paths. Without this RFC:
  `trusted` with attacker content load-eligible. With it: tier capped at
  `known` (C13) *and* every hashed unit demoted (C11).
- **B18** — single-file post-sign edit: one unit demotes with both
  digests recorded; the rest render normally (drift, not relocation).
- **B19** — corroboration mismatch: derived origin whose remote serves a
  different manifest stays `derived`; outcome recorded.

---

## 7. Open Questions

1. **Digest cost on large trees.** §3.2 hashes every file under a
   directory unit. Should the spec admit a declared file-count/byte
   budget above which `kcp sign` refuses and asks for a narrower path,
   or is tooling guidance enough?
2. **Federation unit fetches.** For federated manifests, unit content is
   fetched over HTTP, not read from a local tree. The hash check applies
   unchanged, but should the renderer also record the *transport*
   integrity (RFC 9421) alongside `content_verified`?
3. **Upgrading `declared` for verified content.** A unit with
   `content_verified: true` under an allowlisted key is materially
   stronger than bare self-description. Should it qualify for an
   RFC-0012 `verification_status` upgrade in the rendered output, and to
   what — this is RFC-0018 open question 2 made concrete.
4. **Evidence for non-git checkouts.** Jujutsu, Sapling, and plain
   directory syncs have no `.git/config`. Today they derive `none`
   (safe). Is a per-VCS derivation table worth specifying, or should
   non-git users rely on harness assertion?

---

## 8. Relationship to Prior Work

- **RFC-0004** asked (open question 3) whether `manifest_hash` should
  extend to per-unit hashes. This RFC answers: yes, embedded in the unit
  declaration so the existing signature covers them; algorithm vocabulary
  reused unchanged.
- **RFC-0018** supplies the pipeline this RFC hardens. T9 completes its
  threat table; evidence classes refine its §4.1 without changing the
  derivation order; C11–C13 extend its conformance suite; its open
  question 4 (per-unit hashes narrowing T6) is resolved as §3.
- **RFC-0012**'s vocabulary is deliberately *not* reused for evidence
  classes (§4.1) and is a candidate consumer of `content_verified`
  (open question 3).
- **RFC-0003**: pinning and evidence classification apply per federation
  edge, consistent with RFC-0018 §7's per-edge rule; fetched manifests
  start at `fetched` evidence by construction.
