# RFC-0020: Temporal Composition

**Status:** Accepted — promoted to SPEC.md §4.22, §3.11, §4.18 amendment, §15.12, §16.5 C15 (v0.19)
**Author:** Thor Henning Hetland (eXOReaction AS)
**Created:** 2026-06-12
**Target version:** v0.19
**Depends on:** RFC-0010 (Bi-Temporal Unit Validity), RFC-0014 (Manifest Composition), RFC-0018 (Trusted Render Pipeline)
**Amends:** RFC-0010 (promotes schema phase to spec), RFC-0014 (promotes to spec with trust-pipeline integration), SPEC.md §4.18 (adds `verified_by`, `evidence`)
**Related:** RFC-0003 (Federation), RFC-0004 (Trust and Compliance), RFC-0012 (Capability Discovery Provenance)

---

## Summary

v0.16 gave manifests a trust model. v0.17 gave units a content model. v0.18 gave units
integrity hashes. v0.19 gives knowledge a **time axis** and a **layering model** — the two
features most commonly blocking adoption in regulated, multi-team environments.

This RFC promotes the schema phase of RFC-0010 (bi-temporal unit validity) and the core of
RFC-0014 (manifest composition) to normative spec status, adds two provenance fields to §4.18,
and introduces an optional integrity-pinning primitive for composed includes. Together these
form the **Temporal Composition** release.

> **Design principle for this release:** A manifest should be able to say when its knowledge
> is valid, where that knowledge came from, and that the source has not been altered since
> authoring time — without requiring any change to existing manifests.

All additions are backward-compatible. Every new field is OPTIONAL. Existing manifests require
no changes.

---

## 1. Problem Statement

### 1.1 Static manifests lose temporal context

KCP units today express current state. A unit declaring `authority.execute: requires_approval`
is correct today — but there is no record of what it said last week, and no way to declare
that it will stop being true on April 30th.

Three concrete scenarios motivate the temporal block (from RFC-0010):

**Compliance audit:** A regulator asks what security policy was in effect on February 15th.
The manifest cannot answer — it only records what is true now.

**Future-dated policy:** A security team wants to declare a new policy effective March 1st
without disrupting agents running today. Without validity windows, the change either takes
effect immediately or requires two separate manifests.

**Knowledge expiry:** A migration runbook is only relevant until the migration completes.
After that date, agents loading the manifest will find and load a unit that no longer
describes reality.

### 1.2 Multi-team manifests drift on fork

Large platform manifests are copied and diverge. Teams need 80% of a base manifest plus
project-specific units, but federation (`manifests[]`) provides no way to suppress or adapt
units from the included source. The only option today is forking, and forks drift.

### 1.3 Composed includes have no integrity guarantee

When a manifest composes from an external source (via `composition.includes`), the
composition author has no way to declare what they built against. A dependency update,
supply-chain compromise, or simple file rename silently changes what the composed manifest
contains. The render pipeline (§16) assigns the composing file's trust tier to the composed
output — but it cannot detect that the included source has changed since authoring time.

### 1.4 Verification claims have no attribution

The `discovery.verification_status` ladder (`rumored < declared < observed < verified`)
was extended with `declared` in v0.16. But `verified` status has no associated verifier
identity and no link to the artifact that verification was performed against. The claim
is epistemic but not attributable.

---

## 2. Design

### 2.1 Bi-Temporal Unit Validity (`temporal` block) — schema phase only

A `temporal` block, OPTIONAL at both root and unit level, declares two independent timelines:

- **Valid time** (`valid_from`, `valid_until`): when the knowledge was true in the real world
- **Transaction time** (`recorded_at`, `superseded_by`): when the manifest recorded this version

```yaml
# Root-level defaults (applied to all units that do not override)
temporal:
  valid_from: "2026-01-01"
  valid_until: "2026-12-31"

units:
  - id: migration-runbook
    title: "Q2 Database Migration Runbook"
    temporal:
      valid_from: "2026-04-01"
      valid_until: "2026-06-30"      # expires after migration window
      recorded_at: "2026-03-28"
    # ...

  - id: legacy-sso-policy
    title: "Legacy SSO Policy (deprecated)"
    temporal:
      valid_until: "2026-03-01"
      superseded_by: "new-sso-policy" # id of the replacement unit
    # ...
```

All four sub-fields are OPTIONAL strings in ISO 8601 date or datetime format.
Root-level `temporal` provides defaults; unit-level overrides field-by-field (not block-level).

**Query extensions (`as_of`, `include_all_temporal`) are deferred to v0.20.** v0.19 ships
the schema and basic temporal filtering (§4.22, §15.12) so authors can begin declaring
validity windows immediately. Bridges that do not yet implement temporal evaluation MUST
treat all units as active (safe, backward-compatible default).

### 2.2 Manifest Composition (`composition` block)

A root-level `composition` block, OPTIONAL, declares how this manifest is assembled from
other manifests:

```yaml
kcp_version: "0.19"

composition:
  includes:
    - source: ./base/knowledge.yaml
    - source: https://raw.githubusercontent.com/acme/kcps/main/platform/knowledge.yaml
      as: platform                      # namespace prefix for included unit ids

  overrides:
    - id: platform:submit-expense-report  # namespaced: overrides a unit from the 'platform' include
      title: "Submit Expense Report (EU region)"
      triggers:
        - "expense report"
        - "speserapport"

  excludes:
    - id: platform:legacy-sso-login       # suppress a unit from the included manifest

units:
  - id: my-local-unit                     # local units declared normally
    # ...
```

**Resolution order:** includes (in list order, later wins on id collision), then overrides
(applied on top), then excludes (removed from result), then local `units[]` (merged last,
wins on all collisions).

**Trust pipeline integration (§16 amendment):** Composition resolution MUST complete before
trust tiering. The composed manifest's trust tier is derived from the *composing* file's
signature, not from included sources. An included source with its own signature does not
elevate the composed result; it may produce a §7 advisory warning if the included source's
own tier is lower than the composing file's declared tier.

### 2.3 Discovery provenance enrichment (§4.18 amendment)

Two new OPTIONAL fields on the `discovery` block:

```yaml
discovery:
  verification_status: verified
  verified_at: "2026-05-01"
  verified_by: "ed25519:MCowBQYDK2VwAyEA..."   # key id or agent identity of the verifier
  evidence: "https://example.com/audits/kcp-2026-05.pdf"  # URL or path to verification artifact
  confidence: 0.95
```

- **`verified_by`**: OPTIONAL string. Key id (from `trust.content_integrity.signing.public_key`),
  agent identity, or opaque verifier handle. SHOULD be present when `verification_status: verified`.
  §7 warning if `verification_status: verified` and `verified_by` is absent.
- **`evidence`**: OPTIONAL string. URL or path to the artifact against which verification was
  performed. No format requirement; any stable reference is valid.

### 2.4 Integrity-pinned includes (composition extension)

OPTIONAL `integrity` sub-field on `composition.includes[]` entries:

```yaml
composition:
  includes:
    - source: ./base/knowledge.yaml
      integrity:
        manifest_hash: "sha256:a1b2c3d4e5f6..."
    - source: https://raw.githubusercontent.com/acme/kcps/main/platform/knowledge.yaml
      as: platform
      integrity:
        expected_signer: "ed25519:MCowBQYDK2VwAyEA..."
```

- **`manifest_hash`**: OPTIONAL string. SHA-256 hash (hex) of the included source file at
  authoring time. Computed over the raw file bytes, identical to `trust.content_integrity.manifest_hash`.
- **`expected_signer`**: OPTIONAL string. Public key id expected to have signed the included source.

**Resolution behaviour:** Both fields are advisory warnings, not hard failures (consistent with
`manifests[].version_pin` in §3.6). A mismatch MUST produce a §7 warning with the specific
field and expected vs. actual values. It does not cause a `failed` trust tier on the composed
result — that is determined by the composing file's own signature.

This is **pinned inclusion**, not transitive trust. The composing author records what they built
against; the resolver can detect drift. The trust tier of included sources does not propagate.

---

## 3. Normative Specification

### 3.1 `temporal` block

- OPTIONAL at manifest root and at unit level.
- All sub-fields OPTIONAL: `valid_from`, `valid_until`, `recorded_at`, `superseded_by`.
- `valid_from`, `valid_until`, `recorded_at`: ISO 8601 date (`YYYY-MM-DD`) or datetime.
- `superseded_by`: string, id of another unit in the same composed manifest.
- Root-level `temporal` provides defaults. Unit-level overrides field-by-field.
- Parsers MUST read and expose `temporal` fields.
- Parsers that do not implement temporal evaluation MUST treat all units as active.
- Bridges that implement temporal evaluation MUST filter at query time, applying:
  `valid_from <= today AND (valid_until IS NULL OR valid_until >= today)`.
- `superseded_by` cycles MUST be detected and reported as a manifest error (not a §7 warning).

**§7 advisory warnings (new):**
- `superseded_by` references a nonexistent unit id
- `valid_until` is in the past and no `superseded_by` is set (stale unit with no successor)
- `recorded_at` is later than `valid_from` (manifest authored after the fact it describes)

**Conformance:** Level 2. Temporal field exposure is Level 1; temporal filtering is Level 2.

### 3.2 `composition` block

- OPTIONAL at manifest root.
- Sub-fields: `includes` (list), `overrides` (list), `excludes` (list). All OPTIONAL.
- `includes[].source`: REQUIRED string. Relative path or URL.
- `includes[].as`: OPTIONAL string. Namespace prefix for included unit ids.
- `includes[].integrity`: OPTIONAL object. Sub-fields: `manifest_hash`, `expected_signer`.
- `overrides[].id`: REQUIRED string. May use `namespace:id` form for namespaced includes.
- `excludes[].id`: REQUIRED string. May use `namespace:id` form.
- Resolution order: includes (list order) → overrides → excludes → local `units[]`.
- Circular includes MUST be detected and reported as a manifest error.
- Override referencing nonexistent id: MUST produce §7 warning.
- Exclude referencing nonexistent id: MUST produce §7 warning.
- `includes[].integrity` mismatch: MUST produce §7 warning.
- Trust tier: derived from composing file's signature only (per §16).

**Conformance:** `includes`/`overrides`/`excludes` at Level 1; `as` prefix and integrity
pinning at Level 2; recursive composition (includes within includes) at Level 2.

### 3.3 `discovery` provenance fields

- `discovery.verified_by`: OPTIONAL string.
- `discovery.evidence`: OPTIONAL string.
- `discovery.verified_by` SHOULD be present when `discovery.verification_status: verified`.
- §7 warning: `verification_status: verified` without `verified_by`.

---

## 4. Interactions with Existing Sections

### §15 — Query vocabulary

Temporal filtering (when implemented) applies after `not_for` filtering and before the top-N
cut. Order: score → `not_for` filter → temporal filter → top-N cut. A unit that is outside
its valid window is excluded before counting toward the top-N budget.

### §16 — Trusted Render Pipeline

Composition resolution is a pre-render step. The render pipeline receives the fully resolved
composed manifest. Integrity-pin warnings are emitted during resolution, before the pipeline
runs. The trust tier is derived from the composing file's signature; included sources with
lower-tier signatures produce §7 warnings but do not lower the composed result's tier.

### §4.18 — `discovery.verification_status`

The epistemic ordering remains `rumored < declared < observed < verified`. `verified_by`
and `evidence` add attribution to the `verified` tier without changing the ordering semantics.

---

## 5. Deferred

The following are explicitly deferred from this RFC and flagged for v0.20 or later:

- **Temporal query extensions** (`as_of`, `include_all_temporal`): per RFC-0010's own
  staging plan. The schema and basic temporal filtering are shipped in v0.19 (§4.22, §15.12).
  Point-in-time query (`as_of`) and full bi-temporal traversal (`include_all_temporal`) require
  bridge implementation experience before standardising.

- **Transitive trust / federated trust chains**: the no-transitive-trust rule from §16
  remains unchanged. Pinned inclusion (§2.4) is not transitive trust — it is drift detection
  only. Explicit trust delegation requires a separate policy language design.

- **Schema merging semantics for overlapping `temporal` blocks**: if an included unit has
  a `temporal.valid_until` and the overlay sets `temporal.valid_from` later than that date,
  the resolution is undefined. This degenerate case is rare and can be addressed by bridge
  implementation guidance rather than normative spec text in v0.19.

---

## 6. Resolved Questions (v0.19)

The following questions were open during the Request for Comments phase. All three are
adopted normatively in SPEC.md as part of v0.19 promotion.

1. **`composition.includes[].integrity.manifest_hash` computation:** Hashed over the raw
   file bytes *before* composition resolution of the included file, matching
   `trust.content_integrity.manifest_hash` semantics (SPEC.md §3.11). **Adopted.**

2. **`superseded_by` crossing composition boundaries:** Yes — `superseded_by` MAY reference
   a unit id from an included manifest using the `namespace:id` form (SPEC.md §4.22).
   **Adopted.**

3. **`composition.overrides` adding a `temporal` block:** Yes — `composition.overrides` MAY
   add a `temporal` block to a unit that did not originally have one. This is the primary
   use case for time-gated policy rollouts via overlay manifests (SPEC.md §3.11). **Adopted.**

---

*Co-authored with Claude. The design and normative choices are mine; Claude helped draft
and structure the document.*
