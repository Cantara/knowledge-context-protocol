# RFC-0021: Federation-Level Temporal Validity

**Status:** Accepted — promoted to SPEC.md §3.6 (`manifests[].temporal`) and §16.5 C18 (v0.21).
**Author:** Thor Henning Hetland (eXOReaction AS)
**Created:** 2026-06-13
**Target version:** v0.21
**Depends on:** RFC-0003 (Cross-Manifest Federation), RFC-0010 (Bi-Temporal Unit Validity)
**Related:** RFC-0020 (Temporal Composition), RFC-0014 (Manifest Composition)

---

## Summary

v0.19 gave units a temporal validity window (§4.22). v0.20 gave bridges temporal query
parameters (`as_of`, `include_all_temporal`). But federation (§3.6) has no temporal
dimension: a `manifests[]` entry is either present or absent, with no mechanism to declare
when a sub-manifest is relevant as a knowledge source. This RFC adds an optional `temporal`
block to `manifests[]` entries, enabling bridges to skip entire sub-manifests whose validity
window does not include the effective query date.

> **Design principle:** A hub manifest should be able to say when a federated source
> is relevant — without requiring changes to the federated source itself.

All additions are backward-compatible. Every new field is OPTIONAL. Existing manifests
require no changes.

---

## 1. Problem Statement

Unit-level `temporal` (§4.22) answers: "when is this piece of knowledge valid in the real
world?" But there is no way to ask the prior question: "when is this *source* relevant as
a knowledge provider?"

Consider a hub manifest federating three regulatory corpora:

```yaml
manifests:
  - id: gdpr-corpus-2018
    url: "https://legal.example.com/gdpr-2018/knowledge.yaml"
    label: "GDPR corpus — original 2018 implementation"
    relationship: governs

  - id: gdpr-corpus-2023
    url: "https://legal.example.com/gdpr-2023/knowledge.yaml"
    label: "GDPR corpus — updated with 2023 SCCs guidance"
    relationship: governs

  - id: nis2-directive
    url: "https://legal.example.com/nis2/knowledge.yaml"
    label: "NIS2 Directive — network and information security"
    relationship: governs
```

On 2026-01-15 an agent queries with `federation_scope: declared`. The bridge fetches all
three sub-manifests, loads their units, and applies unit-level temporal filtering. But
`gdpr-corpus-2018` contains 400+ units — many of which have no `valid_until` set because
the original authors did not anticipate being superseded. The bridge returns stale GDPR
guidance alongside current guidance, and the agent has no signal to prefer the 2023 corpus
over the 2018 one.

The hub manifest author *knows* the 2018 corpus was superseded on 2023-09-01. But there is
nowhere to declare this today. The only options are:

1. **Remove the entry.** Loses the audit trail. Point-in-time queries (`as_of: "2020-06-01"`)
   can never reconstruct what was in effect.
2. **Set `relationship: archive`.** A hint, but agents are not required to skip archive
   entries, and the semantics are not temporal — a manifest can be archived for reasons
   unrelated to time.
3. **Rely on unit-level `valid_until` in the sub-manifest.** Requires the sub-manifest
   author to have set validity windows on every unit. The hub author cannot compensate for
   missing temporal metadata in sources they do not control.

None of these is satisfactory. The gap is at the federation layer.

---

## 2. Proposal

### 2.1 `temporal` block on `manifests[]` entries

Add an OPTIONAL `temporal` block to each `manifests[]` entry (§3.6). The structure is
parallel to the unit-level `temporal` block (§4.22) but uses a reduced field set — only
the fields that make sense for source-level validity.

```yaml
manifests:
  - id: gdpr-corpus-2018
    url: "https://legal.example.com/gdpr-2018/knowledge.yaml"
    label: "GDPR corpus — original 2018 implementation"
    relationship: governs
    temporal:
      valid_from: "2018-05-25"
      valid_until: "2023-09-01"
      superseded_by: gdpr-corpus-2023   # references another manifests[].id

  - id: gdpr-corpus-2023
    url: "https://legal.example.com/gdpr-2023/knowledge.yaml"
    label: "GDPR corpus — updated with 2023 SCCs guidance"
    relationship: governs
    temporal:
      valid_from: "2023-09-01"

  - id: nis2-directive
    url: "https://legal.example.com/nis2/knowledge.yaml"
    label: "NIS2 Directive — network and information security"
    relationship: governs
    temporal:
      valid_from: "2023-01-16"
```

#### `manifests[].temporal` field reference

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `valid_from` | OPTIONAL | ISO 8601 date or datetime | When this sub-manifest became relevant as a knowledge source. If absent, the sub-manifest is treated as relevant from an unbounded past. |
| `valid_until` | OPTIONAL | ISO 8601 date or datetime | When this sub-manifest ceased to be relevant as a knowledge source. If absent, the sub-manifest is treated as relevant indefinitely. |
| `superseded_by` | OPTIONAL | string | The `id` of another entry in the same `manifests[]` block that replaces this one. Cycles MUST be detected and reported as a manifest error. |

**Omitted fields:** `recorded_at` is not included. It is a transaction-time concept tied
to unit authoring; sub-manifest entries are declared by the hub author, and the hub
manifest's own `recorded_at` (root-level `temporal`) already covers that concern.

**Semantics distinction:** Unit-level `temporal` answers "when is this content valid in the
real world?" Manifest-level `temporal` answers "when is this source relevant as a knowledge
provider?" They are independent assertions by different authors (the sub-manifest author
vs. the hub manifest author).

### 2.2 Bridge resolution behaviour

When a bridge resolves a query with `federation_scope: declared`, it MUST apply
manifest-level temporal filtering before fetching and evaluating sub-manifest units.

#### Effective date

The effective date for manifest-level filtering is determined identically to unit-level
temporal filtering (§15.13):

- If `as_of` is set: effective date = `as_of`.
- If `as_of` is absent: effective date = current date (today).

#### Filter rule

A sub-manifest is **temporally included** if and only if:

- The entry has no `temporal` block, **or**
- `valid_from` is null **or** `valid_from <= effective_date`, **and**
- `valid_until` is null **or** `valid_until >= effective_date`

Sub-manifests that fail this filter MUST NOT be fetched. The bridge skips them entirely —
no HTTP request, no unit loading, no unit-level temporal filtering.

#### `include_all_temporal` override

When `include_all_temporal: true`, manifest-level temporal filtering is also bypassed.
All sub-manifests are traversed regardless of their validity window. This is consistent
with the parameter's existing semantics: it disables all temporal filtering, not just
unit-level.

#### Two-layer composition

Manifest-level temporal and unit-level temporal compose as two independent filter stages:

1. **Manifest-level filter** (this RFC): skip entire sub-manifests outside the effective
   date window.
2. **Unit-level filter** (§4.22, §15.12, §15.13): within sub-manifests that pass step 1,
   filter individual units by their own `temporal` blocks.

A sub-manifest that passes the manifest-level filter may still have individual units
filtered out by unit-level temporal. The two layers are independent — the hub author
controls layer 1; the sub-manifest author controls layer 2.

#### Filter order (updated)

The complete filter order for federated queries becomes:

> **manifest-level temporal filter → fetch sub-manifest → score → `not_for` filter → unit-level temporal filter → top-N cut**

#### Overlapping validity windows

Multiple sub-manifests MAY be temporally active at the same effective date. This is
expected and correct — a hub may federate a 2018 corpus (no `valid_until`) alongside a
2023 corpus (valid from 2023-09-01), and both will be active after that date. Bridges
query all temporally-included sub-manifests and merge results identically to the existing
`federation_scope: declared` behaviour. The `source_manifest` field in each result
identifies the origin. Prioritising results from one sub-manifest over another is a
scoring decision, not a temporal one.

#### Response fields

The existing `source_manifest` field in search results (§15.7) already identifies which
sub-manifest a unit came from. No new response fields are required. Sub-manifests that
were skipped by manifest-level temporal filtering simply produce no results — they are
invisible to the caller, identical to how a sub-manifest with no matching units produces
no results.

### 2.3 Validation warnings and errors

#### §7 advisory warnings (new)

- `valid_until` is in the past and no `superseded_by` is set on a `manifests[]` entry
  (stale federation link with no successor).
- `superseded_by` references a nonexistent `manifests[].id` (dangling successor reference).

#### Manifest errors

- `superseded_by` cycles among `manifests[]` entries (A superseded_by B, B superseded_by A).
  This is a manifest ERROR, not a §7 advisory warning. Parsers MUST detect and report it.
  Consistent with the cycle detection rule for unit-level `superseded_by` (§4.22).

---

## 3. Design Notes

### 3.1 Why not unit-level temporal alone?

The two-layer argument rests on three observations:

1. **Authority boundary.** The hub author and the sub-manifest author are often different
   people or teams. The hub author knows when they adopted and retired a source; the
   sub-manifest author knows when individual facts became true. Forcing the hub author to
   reach into a sub-manifest and set `valid_until` on every unit conflates these authorities.

2. **Performance.** Fetching a 400-unit sub-manifest only to filter out every unit by
   `valid_until` is wasteful. Manifest-level temporal lets the bridge skip the HTTP request
   entirely — a significant saving for federated graphs with many archived sources.

3. **Completeness gap.** Sub-manifest authors may not have set `valid_until` on their units.
   The hub author cannot fix this without forking the sub-manifest. Manifest-level temporal
   lets the hub author declare retirement without modifying the source.

### 3.2 Why not `relationship: archive`?

The `archive` relationship value (§3.6) is a semantic hint, not a temporal one. A manifest
can be archived for reasons unrelated to time (e.g., consolidated into a larger manifest).
Conversely, a manifest can have a `valid_until` in the past without being an archive — it
was a primary source during its validity window. The two concepts are orthogonal.

### 3.3 Interaction with `version_pin`

`version_pin` (§3.6) constrains *which version* of a sub-manifest is acceptable.
`temporal` constrains *when* the sub-manifest is relevant. They compose without conflict:
a bridge evaluates `temporal` first (should we even consider this source?), then
`version_pin` (is the fetched version acceptable?).

---

## 4. Backward Compatibility

All new fields are OPTIONAL. Existing `manifests[]` entries without a `temporal` block are
treated as "always relevant" — identical to the current behaviour. No existing manifests
require changes.

Bridges that do not implement manifest-level temporal evaluation MUST treat all sub-manifests
as active (safe, backward-compatible default). This mirrors the unit-level rule in §4.22:
parsers that do not implement temporal evaluation MUST treat all units as active.

---

## 5. Conformance

| Feature | Level | Notes |
|---------|-------|-------|
| Parse and expose `manifests[].temporal` fields | Level 1 | Parsers MUST read and expose. |
| Manifest-level temporal filtering at resolution time | Level 2 | Bridges MUST skip sub-manifests outside the effective date window. |
| `superseded_by` cycle detection on `manifests[]` entries | Level 1 | Parsers MUST detect and report as manifest error. |
| `include_all_temporal` bypasses manifest-level filtering | Level 2 | Consistent with existing `include_all_temporal` semantics (§15.13). |

---

*Co-authored with Claude. The design and normative choices are mine; Claude helped draft
and structure the document.*
