# RFC-0014: Manifest Composition

**Status:** Request for Comments
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-03-25
**Discussion:** [GitHub Discussions](https://github.com/Cantara/knowledge-context-protocol/discussions)
**Related:** [RFC-0003 Federation](./RFC-0003-Federation.md) · [RFC-0011 Org Federation](./RFC-0011-Org-Federation.md) · [RFC-0013 Catalog](./RFC-0013-Cartridge-Catalog-Distribution.md)
**Spec:** [SPEC.md](./SPEC.md) (current: v0.14)

---

## What This RFC Proposes

Three composition primitives that let a `knowledge.yaml` manifest be built from other
manifests rather than declared entirely from scratch:

- **`includes`** — pull all units from another manifest into this one
- **`overrides`** — replace or extend specific units from an included manifest
- **`excludes`** — suppress specific units from an included manifest

Together they enable layered manifests: a base manifest declares stable, reusable units;
overlay manifests adapt them for a team, product, or environment — without forking.

---

## The Problem

### Large manifests resist reuse

A manifest for a large platform may declare hundreds of units. Teams that need 80% of those
units plus a handful of project-specific units today have two choices:

1. Copy the upstream manifest and diverge (fork drift, stale copies)
2. Include the upstream via `manifests[]` federation — but federation pulls everything
   at runtime and provides no way to suppress or adapt units

Neither option gives the author of a derived manifest control over what units are visible
to the consuming agent.

### No layered adaptation without forking

Compliance overlays, environment-specific routing, tenant-specific capability sets — all
of these require adapting a base manifest without owning its source. KCP currently has no
mechanism for this.

---

## Design

### Root-level `composition` block

```yaml
kcp_version: "0.14"

composition:
  includes:
    - source: ./base/knowledge.yaml
    - source: https://raw.githubusercontent.com/acme/kcps/main/platform/knowledge.yaml
      as: platform

  overrides:
    - id: submit-expense-report
      title: "Submit Expense Report (EU)"
      triggers:
        - "expense report"
        - "speserapport"
      audience: [agent, human]

  excludes:
    - id: legacy-sso-login
    - id: deprecated-batch-upload

units:
  - id: my-local-unit
    title: "My Local Unit"
    # ... local units declared as normal
```

### `includes`

The `includes` list identifies other `knowledge.yaml` files whose `units[]` are merged
into this manifest at load time. Sources use the same grammar as CATALOG-SPEC.md §5
(local paths, raw HTTPS, git references).

The optional `as` key assigns a namespace prefix. When set, the included units' `id`
values are rewritten to `<namespace>/<original-id>` in the composed result. When absent,
unit IDs are merged flat — the implementer is responsible for avoiding collisions.

```yaml
includes:
  - source: ./platform/knowledge.yaml         # flat merge
  - source: ./auth/knowledge.yaml
    as: auth                                   # units become auth/<id>
```

### `overrides`

An override entry matches a unit by `id` (after namespace expansion, if any) and
replaces the fields listed. Only the fields present in the override entry are replaced;
all other fields are inherited from the source unit.

```yaml
overrides:
  - id: submit-expense-report
    title: "Submit Expense Report (EU)"
    triggers:
      - "expense report"
      - "speserapport"
```

An override that references an `id` not present in any included manifest MUST be reported
as a warning. Implementations MUST NOT silently drop the override entry.

### `excludes`

An exclude entry suppresses the named unit from the composed result. The unit is not
present in the manifest served to agents.

```yaml
excludes:
  - id: legacy-sso-login
  - id: platform/deprecated-batch-upload    # namespace-qualified
```

An exclude that references an `id` not present in any included manifest MUST be reported
as a warning.

### Local `units[]`

A manifest MAY declare its own `units[]` in addition to a `composition` block. Local
units are merged last, after includes, overrides, and excludes are resolved. A local unit
with the same `id` as an included unit MUST be treated as an implicit override (local wins).

### Resolution order

1. Fetch and merge all `includes` sources in order. Later includes win on collision (flat
   merge). Namespaced includes do not collide.
2. Apply all `overrides` in order.
3. Apply all `excludes`.
4. Merge local `units[]` (local wins on collision).

### Recursion and cycles

An included manifest MAY itself declare a `composition` block. Resolution is applied
recursively before the result is merged into the parent.

Circular includes (A includes B includes A) MUST be detected and reported as an error.
Implementations MUST NOT silently produce an infinite loop.

---

## Relationship to Federation

Federation (`manifests[]`, RFC-0003, RFC-0011) is a **runtime read path**: a running agent
traverses live servers to discover additional manifests.

Composition (`composition`, this RFC) is an **authoring-time write path**: a manifest
author assembles a derived manifest from source files before any agent loads it.

The two mechanisms compose without conflict. A composed manifest MAY declare `manifests[]`
federation links. Federation extends discovery from the composed result; it does not replace
composition.

---

## Relationship to the Catalog (RFC-0013)

A `catalog.yaml` entry references a single `knowledge.yaml` file by path or URL. If that
file declares a `composition` block, the catalog consumer resolves composition as part of
fetching the manifest.

The catalog does not need to be aware of composition; composition is transparent to the
distribution layer.

---

## Conformance

| Feature | Level | Notes |
|---------|-------|-------|
| `includes` (local path) | Level 1 | MUST be supported. |
| `includes` (raw HTTPS) | Level 1 | MUST be supported. |
| `excludes` | Level 1 | MUST be supported. |
| `overrides` | Level 1 | MUST be supported. |
| `as` namespace prefix | Level 2 | RECOMMENDED. |
| `includes` (git source) | Level 2 | RECOMMENDED for production use. |
| Recursive composition | Level 2 | RECOMMENDED. MUST detect cycles if implemented. |

---

## Open Questions

1. **Schema merging**: when two included manifests declare different root-level
   `freshness_policy` blocks, which wins? (Proposal: last include wins; local declaration
   wins over all includes.)

2. **Version pinning in `includes`**: should an `includes` entry support a `version`
   constraint (as in the catalog format)? This would enable composition to participate
   in staleness detection.

3. **Conflict reporting**: should field-level collisions in flat merges produce a warning
   or an error? Current proposal: warning, with last-include-wins semantics.

---

## Backward Compatibility

`composition` is a new optional root-level block. Existing manifests that do not declare
it are unaffected. A parser that does not implement composition MUST silently ignore the
`composition` block and serve only the `units[]` declared directly in the file.

---

*Knowledge Context Protocol — [eXOReaction AS](https://www.exoreaction.com), Oslo, Norway.*
