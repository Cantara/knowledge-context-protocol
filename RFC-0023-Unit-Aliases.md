# RFC-0023: Unit Aliases

**Status:** Accepted — promoted to SPEC.md §4.2a (unit `aliases`) in v0.26. Conformance: parse/resolve/uniqueness at Level 1, `matched_alias` surfacing at Level 2.
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-07-11
**Discussion:** [GitHub Discussions](https://github.com/Cantara/knowledge-context-protocol/discussions)
**Related:** [RFC-0014 Manifest Composition](./RFC-0014-Manifest-Composition.md) · [RFC-0010 Bi-Temporal Unit Validity](./RFC-0010-Bi-Temporal-Unit-Validity.md) · [RFC-0020 Temporal Composition](./RFC-0020-Temporal-Composition.md)
**Spec:** [SPEC.md](./SPEC.md) (current: v0.25)

---

## What This RFC Proposes

A new OPTIONAL `aliases` field on knowledge units that declares additional identifiers
resolving to the same content path. An alias is a first-class reference: any system that
looks up a unit by `id` MUST also match against declared aliases.

```yaml
units:
  - id: regulation-art-021
    path: articles/art-021.txt
    intent: "What are the security risk management measures required?"
    aliases:
      - regulation-art-21-2a
      - regulation-art-21-2b
      - regulation-art-21-2c
      - regulation-art-21-2d
      - regulation-art-21-2e
```

This lets consumers reference fine-grained logical subdivisions (sub-clauses, paragraphs,
appendix sections) while the knowledge repository stores content at the natural authoring
granularity (article, chapter, page).

---

## The Problem

### Content granularity ≠ reference granularity

Regulatory texts, standards, and legal documents are authored and maintained at one level
of granularity (articles, clauses, sections) but referenced at a finer level by consuming
systems (sub-clauses, paragraphs, individual requirements).

Example: A regulation defines Article 21 with sub-clauses (a) through (j). The
full article text exists as a single file because:

1. The sub-clauses are not independently meaningful — they form a coherent list
2. Maintaining 10 separate files for one article's enumerated list creates file sprawl
3. The authoritative source (official gazette, standards body) publishes them as one unit
4. Version tracking and diffing work better on complete articles

However, consuming systems (compliance platforms, obligation trackers, audit tools) map
individual requirements to specific sub-clauses: "Obligation X maps to Article 21(2)(d)".
These systems need a resolvable identifier for `regulation-art-21-2d`.

### Current KCP offers no resolution mechanism

Today, if a consumer holds a reference `regulation-art-21-2d`, there is no way to
resolve it through the manifest. The manifest only declares `id: regulation-art-021`
with `path: articles/art-021.txt`. The consumer must either:

1. **Maintain an external mapping table** — defeats the purpose of a self-describing manifest
2. **Split the file** — creates 10 near-empty files for one article's enumerated list
3. **Use string manipulation** — strip suffixes, try parent IDs (fragile, undeclared)
4. **Fail resolution** — the most common outcome

None of these approaches are declarative, portable, or manifest-driven.

### The file-per-sub-clause antipattern

Splitting content to match reference granularity creates real problems:

- **File sprawl**: A regulation with 40 articles and 200 sub-clauses produces 200 files,
  most containing 1-3 sentences with no standalone meaning
- **Context loss**: Sub-clause (d) only makes sense in the context of the preamble and
  clauses (a)-(c). Splitting destroys the reading context that agents need
- **Maintenance burden**: When the regulation is amended, updating 200 files instead of
  40 introduces drift, omissions, and versioning complexity
- **Contradicts authoring intent**: The knowledge author deliberately chose article-level
  granularity because that is the semantically coherent unit

### The pattern is common across domains

This is not limited to regulations. The same granularity mismatch appears in:

- **Standards** (ISO, NIST): Clause 6.1.2 referenced individually, published as Chapter 6
- **Contracts**: Section 4.3(b)(ii) referenced in disputes, filed as Section 4
- **API documentation**: Individual endpoint referenced, published as API group
- **Textbooks**: Theorem 3.2.1 cited, chapter stored as unit
- **Patents**: Claim 14 referenced, patent filed as one document

In all cases: the consumer needs a fine-grained identifier; the producer stores content
at a coarser, semantically coherent level.

---

## Design

### Unit-level `aliases` field

```yaml
units:
  - id: regulation-art-021
    path: articles/art-021.txt
    intent: "What security risk management measures are required?"
    aliases:
      - regulation-art-21-2a
      - regulation-art-21-2b
      - regulation-art-21-2c
      - regulation-art-21-2d
      - regulation-art-21-2e
      - regulation-art-21-2f
      - regulation-art-21-2g
      - regulation-art-21-2h
      - regulation-art-21-2i
      - regulation-art-21-2j
```

### Semantics

1. Each alias MUST follow the same character rules as `id` (§4.2): lowercase ASCII
   letters, digits, hyphens, and dots only.

2. An alias MUST be unique across **all** `id` values and **all** `aliases` values
   within the same manifest (after composition resolution).

3. A lookup by alias resolves to the **same unit** as a lookup by the unit's canonical
   `id`. The alias does not create a separate unit — it creates an alternative reference
   to the existing one.

4. The `id` field remains the canonical identifier. `aliases` are secondary references.
   In serialized output (search results, audit logs, provenance records), implementations
   SHOULD include both the matched alias and the canonical `id`.

5. Aliases do NOT inherit from included manifests' `overrides` — an override MUST target
   the canonical `id`. This keeps composition deterministic.

### Resolution algorithm

When a consumer requests a unit by identifier `X`:

```
1. Search units[].id for exact match → return unit
2. Search units[].aliases[] for exact match → return unit
3. If composition is active, apply the same search across composed units
4. Not found → resolution failure (existing behaviour)
```

Step 2 is new. Uniqueness is a §7 **warning**, not a hard rejection (see SPEC.md §4.2a), so
resolution is made deterministic by a defined tie-break rather than by assuming uniqueness holds: a
canonical `id` always wins over any alias, and among colliding aliases the first declared (units in
document order, then aliases in list order) wins.

### Bridge behavior

When `aliases` is declared, the `search_knowledge` tool MUST:

1. Accept alias values as valid `unit_id` parameters in lookup requests
2. Return the resolved unit with its canonical `id` in the response
3. Include the matched alias in the response metadata (e.g. `matched_alias: "regulation-art-21-2d"`)
   so the consumer knows which reference was resolved

The `list_knowledge` tool SHOULD:

1. Include `aliases` in the unit listing when present
2. Support filtering by alias in addition to `id`

### Relationship to `id`

The `id` remains:
- The canonical reference in `depends_on`, `supersedes`, `relationships`
- The key used in `overrides` and `excludes` (composition)
- The unique primary identifier in search results and audit trails

Aliases are:
- Valid in lookup/resolution contexts
- NOT valid as targets for `depends_on`, `supersedes`, `overrides`, or `excludes`
- Declared on the unit, not on the consuming reference

This distinction prevents alias proliferation from creating ambiguity in the
composition and governance layers.

---

## Examples

### Example 1: Regulatory article with sub-clause references

A regulation has Article 21 containing sub-clauses (2)(a) through (2)(j). A compliance
system maps obligations to individual sub-clauses. The knowledge repository stores the
full article as one file (the semantically coherent unit).

```yaml
- id: reg-art-021
  path: articles/art-021.txt
  intent: "What cybersecurity risk management measures must entities implement?"
  kind: policy
  scope: global
  audience: [agent, compliance-officer]
  aliases:
    - reg-art-21-2a    # policies on risk analysis and IS security
    - reg-art-21-2b    # incident handling
    - reg-art-21-2c    # business continuity and crisis management
    - reg-art-21-2d    # supply chain security
    - reg-art-21-2e    # network and information systems security
    - reg-art-21-2f    # vulnerability handling and disclosure
    - reg-art-21-2g    # effectiveness assessment procedures
    - reg-art-21-2h    # basic cyber hygiene and training
    - reg-art-21-2i    # cryptography and encryption
    - reg-art-21-2j    # human resources and access control
```

A consuming system holding reference `reg-art-21-2d` can resolve it through the manifest
without external mapping tables or string manipulation.

### Example 2: ISO standard clause with sub-sections

```yaml
- id: iso27001-clause-6.1
  path: clauses/clause-6.1.txt
  intent: "How should an organization address risks and opportunities?"
  aliases:
    - iso27001-clause-6.1.1    # general risk actions
    - iso27001-clause-6.1.2    # information security risk assessment
    - iso27001-clause-6.1.3    # information security risk treatment
```

### Example 3: API documentation grouped by resource

```yaml
- id: users-api
  path: api/users.md
  intent: "How do I manage user accounts via the API?"
  aliases:
    - api-get-users
    - api-create-user
    - api-update-user
    - api-delete-user
    - api-get-user-by-id
```

A system that has catalogued individual endpoints can resolve them to the correct
documentation file without maintaining a separate routing table.

### Example 4: Contract sections

```yaml
- id: contract-section-4
  path: contract/section-4-liability.md
  intent: "What are the liability terms and limitations?"
  aliases:
    - contract-4.1-general-liability
    - contract-4.2-limitation-of-liability
    - contract-4.3-indemnification
    - contract-4.3.a-ip-indemnity
    - contract-4.3.b-third-party-claims
```

---

## Why Not Alternatives?

### Alternative A: Split files to match reference granularity

Create one file per sub-clause (e.g. `art-021-2a.txt`, `art-021-2b.txt`).

**Problems:**
- Destroys context: sub-clause (d) is meaningless without the surrounding article
- File sprawl: 40 articles × 5 avg sub-clauses = 200 files vs 40
- Maintenance burden: regulation amendments touch 200 files instead of 40
- Contradicts authoring intent: the official source is article-level
- Degrades agent quality: agents reading a 2-line sub-clause file miss the article
  context they need for correct interpretation

### Alternative B: Consumer-side regex stripping

The consumer strips suffixes from the reference ID until a match is found:
`reg-art-21-2d` → `reg-art-21-2` → `reg-art-21` → `reg-art-021` (match).

**Problems:**
- Undeclared convention: nothing in the manifest says this is valid
- Fragile: different naming schemes break differently
- Non-portable: each consumer reimplements the same heuristic
- Violates KCP principle: the manifest should be the single source of truth for resolution
- Ambiguous: `reg-art-21-2` could be a legitimate separate unit

### Alternative C: `path` with fragment identifiers

Use URL-style fragments: `path: articles/art-021.txt#2d`.

**Problems:**
- Fragment semantics on plain text files are undefined
- Requires parsers to understand document internal structure
- Creates a content addressing layer that belongs in the extraction/RAG pipeline, not
  the manifest (manifest declares *what exists*, not *how to extract from it*)
- Breaks existing `path` semantics (§4.3: path is relative path to file)

### Alternative D: Separate `sub_units` block

```yaml
sub_units:
  - id: reg-art-21-2d
    parent: reg-art-021
    fragment: "2d"
```

**Problems:**
- Creates a second class of unit with unclear semantics (is it searchable? scorable?)
- Requires new resolution semantics for sub_units vs units
- Over-engineered: the fundamental need is just "this ID resolves to that file"
- Adds schema complexity disproportionate to the problem

### Why aliases win

Aliases solve the problem with minimal spec surface:
- One new OPTIONAL field on the existing unit schema
- Semantics are obvious: "these names also refer to this unit"
- No new entity types, no content addressing, no structural assumptions
- Backward compatible: parsers that don't understand aliases still work (they just
  won't resolve alias lookups)
- Declarative: the manifest states what IDs are valid — no inference needed

---

## Conformance

| Feature | Level | Notes |
|---------|-------|-------|
| Parse `aliases` field | Level 1 | MUST read and expose. |
| Alias uniqueness validation | Level 1 | MUST warn (§7) on duplicate aliases within manifest; resolution applies the deterministic tie-break (canonical id first, then first-declared alias). |
| Alias-based resolution | Level 1 | MUST resolve alias lookups to canonical unit. |
| `matched_alias` in response | Level 2 | RECOMMENDED in bridge responses. |
| Alias in `list_knowledge` | Level 2 | RECOMMENDED for discoverability. |

---

## Backward Compatibility

`aliases` is additive. Existing manifests without it continue to work unchanged.
A parser that does not implement aliases will:

1. Silently ignore the `aliases` field (per KCP's unknown-field-passthrough rule)
2. Fail to resolve alias-based lookups (existing behaviour for unknown IDs)
3. Continue resolving canonical `id` lookups normally

No existing behaviour is changed. The feature degrades gracefully.

---

## Schema Addition

```yaml
# In the unit schema (§4.1):
aliases:
  type: array
  items:
    type: string
    pattern: "^[a-z0-9][a-z0-9._-]*$"
  description: >
    Additional identifiers that resolve to this unit. Each alias MUST
    be unique across all unit IDs and aliases in the manifest.
```

---

## Interaction with Composition (RFC-0014)

When composition is active:

- Aliases from included manifests are merged into the composed namespace
- Namespace prefixes (`as`) apply to aliases: if a source is included with
  `as: upstream`, alias `foo` becomes `upstream/foo` in the composed result
- `overrides` target canonical `id` only — NOT aliases
- `excludes` target canonical `id` only — excluding a unit removes all its aliases

This keeps composition deterministic: aliases are properties of a unit, not independent
entities. You cannot override or exclude an alias independently of its parent unit.

---

## Interaction with Temporal Validity (RFC-0010, RFC-0020)

Aliases share the temporal validity of their parent unit. When a unit's `temporal.valid_until`
expires, its aliases expire with it. There is no mechanism for time-varying aliases —
if a sub-clause reference changes meaning across versions, this is modelled by `supersedes`
on the unit level, not by alias manipulation.

---

## Interaction with Content Integrity (RFC-0019, RFC-0022)

Since aliases resolve to the same `path`, the `content_hash` applies equally to all aliases.
An alias does not imply a different content payload — it is purely an identifier-level
indirection.

---

## Security Considerations

### Alias squatting

A malicious manifest could declare aliases that collide with well-known unit IDs from
other manifests in a federation graph. This is mitigated by:

1. Alias uniqueness is enforced per-manifest (same as `id` uniqueness)
2. In federated graphs, namespace collision is already handled by the `as` prefix
   mechanism (RFC-0014) and trust tiering (§16)
3. Consumers SHOULD resolve aliases within a single manifest scope unless federated
   resolution is explicitly configured

### Denial through alias explosion

A unit could declare thousands of aliases, inflating manifest size and resolution index.
Implementations MAY impose a reasonable upper bound (RECOMMENDED: 100 aliases per unit)
and SHOULD warn when alias counts exceed this threshold.

---

## Open Questions

1. **Wildcard aliases**: Should a unit be able to declare a pattern
   (e.g. `regulation-art-21-*`) rather than enumerating all sub-clauses? This would
   reduce manifest verbosity but introduces regex matching into the resolution path.
   Current proposal: enumerate explicitly. Patterns are deferred to a future RFC if
   demand materialises.

2. **Alias metadata**: Should an alias carry per-alias metadata (e.g. a `label` or
   `fragment_hint`)? This would let consumers display "Article 21(2)(d) — supply chain
   security" without fetching the content. Current proposal: defer. Aliases are pure
   identifiers. Metadata belongs on the unit or in the content.

3. **Cross-manifest alias references**: In a federated graph, should a remote manifest
   be able to declare an alias for a unit in another manifest? Current proposal: no.
   Aliases are owned by the declaring manifest. Cross-manifest references use `depends_on`
   with the canonical `id`.

---

*Knowledge Context Protocol — [eXOReaction AS](https://www.exoreaction.com), Oslo, Norway.*
