# KCP 0.9.0 Implementation Plan — Federation Release

**Status:** Ready to implement
**Created:** 2026-03-10
**Semver:** First release using `MAJOR.MINOR.PATCH` — tag as `v0.9.0`, `kcp_version: "0.9"` in manifests

---

## Decisions Locked

| # | Decision |
|---|----------|
| Q1 | **DAG with local authority** — not hub-and-spoke. Any manifest MAY declare `manifests` block. |
| Q2 | **Any manifest can declare sub-manifests** — no root-only restriction. |
| Q3 | **Version pinning deferred to 0.10** — noted as known limitation in spec. |
| Q4 | **`on_failure: skip/warn/degrade`** + **`local_mirror`** + **`update_frequency` on manifests entries** + **10s fetch timeout** |
| Q5 | **6 relationship types**: `enables`, `context`, `supersedes`, `contradicts`, `depends_on`, `governs` — shared vocabulary, both intra and cross-manifest. `governance` renamed to `governs` everywhere. |

---

## New Fields Summary

### Root-level additions to `knowledge.yaml`
```yaml
manifests:                              # NEW — federation declarations
  - id: platform                        # REQUIRED — local identifier
    url: "https://..."                  # REQUIRED — HTTPS only
    label: "Platform Team"             # RECOMMENDED
    relationship: foundation            # RECOMMENDED: child|foundation|governs|peer|archive
    auth: { ... }                       # OPTIONAL — per RFC-0002
    update_frequency: weekly            # OPTIONAL — reuse existing vocab
    local_mirror: "./mirrors/p.yaml"   # OPTIONAL — air-gapped path

external_relationships:                # NEW — cross-manifest relationships
  - from_manifest: platform            # OPTIONAL — omit = this manifest
    from_unit: deployment-guide        # REQUIRED
    to_manifest: security              # OPTIONAL — omit = this manifest
    to_unit: gdpr-policy               # REQUIRED
    type: governs                      # REQUIRED — shared vocab with relationships §5
```

### Unit-level additions
```yaml
external_depends_on:                   # NEW — cross-manifest dependencies
  - manifest: security                 # REQUIRED — references manifests[].id
    unit: gdpr-policy                  # REQUIRED — unit id in remote manifest
    on_failure: degrade                # OPTIONAL: skip(default)|warn|degrade
```

### Relationship type vocabulary update
Add `governs` to §5 vocabulary. Replace `governance` with `governs` in `manifests[].relationship` vocab.

### Cycle/fetch rules
- Visited URL set per resolution session (handles cycles AND diamonds)
- Max 50 unique manifests per session (RECOMMENDED default, warning on breach)
- Max manifest size: 1 MB (SHOULD reject)
- Max units per remote manifest: 10,000 (SHOULD reject)
- Fetch timeout: 10 seconds (RECOMMENDED default)
- `local_mirror` resolution order: local first (if file exists) → fetch URL → apply `on_failure`

---

## Files to Create or Modify

### 1. SPEC.md
- Bump version `0.7` → `0.9` in header (note: changelog says 0.8 but spec header may lag)
- Remove §1.3 "multiple manifests out of scope" statement
- Add **§3.6 Federation** — full normative text for `manifests` block, `external_depends_on`, `external_relationships`, cycle detection, fetch behaviour, `local_mirror`, `on_failure`, `update_frequency` on manifest entries
- Update **§5 Relationships** — add `governs` to type table, rename `governance` → `governs`, note shared vocabulary with `external_relationships`
- Update **§8 Conformance Levels** — add Level 3 entries: `manifests` block, `external_depends_on`, `external_relationships`, `local_mirror`
- Update **§14.3 Remote Content** — add federation-specific security constraints (max depth/count, visited set, SSRF check after DNS resolution, no transitive trust escalation)
- Update `kcp_version` current value throughout: `"0.8"` → `"0.9"`
- Add non-normative notes: diagnostic logging, caching guidance, offline mode SHOULD guidance

### 2. CHANGELOG.md
Add `## [0.9.0] — 2026-03-10 — Federation Release` entry covering all additions.

### 3. RFC-0003-Federation.md
Update header:
```
**Status:** Promoted to core — see SPEC.md §3.6 (v0.9.0)
```
Add "Promotion History" section noting which proposals were promoted and what was deferred (version pinning → 0.10, peer-to-peer → future).

### 4. schema/knowledge-schema.json
- Add `manifests` array to root schema with full field definitions
- Add `external_relationships` array to root schema
- Add `external_depends_on` array to unit schema
- Add `governs` to relationship `type` enum
- Add `governs` to `manifests[].relationship` enum (replace `governance`)
- Add `on_failure` enum (`skip`, `warn`, `degrade`) to `external_depends_on` entries
- Add `local_mirror` string to `manifests` entries
- Add `update_frequency` to `manifests` entries (reuse existing enum)

### 5. parsers/java/
**New model classes:**
- `ManifestRef.java` — `id`, `url`, `label`, `relationship`, `auth` (Auth), `updateFrequency`, `localMirror`
- `ExternalDependency.java` — `manifest`, `unit`, `onFailure` (enum: SKIP/WARN/DEGRADE, default SKIP)
- `ExternalRelationship.java` — `fromManifest` (nullable), `fromUnit`, `toManifest` (nullable), `toUnit`, `type`

**Modified model classes:**
- `KnowledgeManifest.java` — add `List<ManifestRef> manifests`, `List<ExternalRelationship> externalRelationships`
- `KnowledgeUnit.java` — add `List<ExternalDependency> externalDependsOn`
- `Relationship.java` — add `governs` to relationship type enum/constant

**Modified parser:**
- `KcpParser.java` — parse `manifests`, `external_relationships`, `external_depends_on`, `on_failure`

**Modified validator:**
- `KcpValidator.java` — validate `manifests[].id` pattern `^[a-z0-9.\-]+$`, validate `manifests[].url` HTTPS-only, warn on `external_depends_on` referencing unknown manifest id, warn on `external_relationships` with unknown manifest ids

**Modified tests:**
- `KcpParserTest.java` — tests for all new fields, round-trip parse of federation manifest

### 6. parsers/python/
**Modified model:**
- `model.py` — add `ManifestRef` dataclass, `ExternalDependency` dataclass, `ExternalRelationship` dataclass; update `KnowledgeManifest` and `KnowledgeUnit`

**Modified parser:**
- `parser.py` — parse new fields

**Modified validator:**
- `validator.py` — validate manifest id pattern, HTTPS-only URL, warn on unknown manifest references

**Modified tests:**
- `tests/test_kcp.py` — federation parse tests, validation tests

### 7. bridge/typescript/src/ (PARITY RULE)
- `model.ts` — add `ManifestRef`, `ExternalDependency`, `ExternalRelationship` types
- `parser.ts` — parse new fields
- `mapper.ts` — expose `manifests` block in resource/tool output
- `server.ts` — add `list_manifests` MCP tool (lists declared sub-manifests with id, url, label, relationship)
- Tests — federation parse tests, `list_manifests` tool test

### 8. bridge/java/ (PARITY RULE — must match TypeScript exactly)
- `KcpMapper.java` — expose manifests block
- `KcpServer.java` — add `list_manifests` MCP tool
- Tests — matching coverage

### 9. bridge/python/ (PARITY RULE)
- Same `list_manifests` tool and manifests exposure
- Tests

### 10. conformance/fixtures/

**New level3 fixtures:**
- `level3/valid-federation-basic/` — hub declaring two sub-manifests, `external_depends_on` with `on_failure: warn`
- `level3/valid-federation-local-mirror/` — manifest with `local_mirror` and `update_frequency`
- `level3/valid-federation-external-relationships/` — `external_relationships` with `governs` type
- `level3/valid-with-governs/` — intra-manifest `governs` relationship

**New edge-case fixtures:**
- `edge-cases/federation-cycle/` — manifest A references B, B references A (validator should warn, not error)
- `edge-cases/federation-diamond/` — A references B and C, both B and C reference D (D should appear once)
- `edge-cases/federation-on-failure-degrade/` — `on_failure: degrade` on required governance dependency

Each fixture: `knowledge.yaml` + `expected.json` with `{valid: true/false, errors: [], warnings: [], unit_count: N}`

### 11. examples/
- `examples/federation/` — complete enterprise example (hub + platform + security + product-alpha) matching the complete YAML from the Opus analysis

### 12. docs/ (GH Pages — REQUIRED on every spec release)
- `docs/index.html` — update version reference `0.8` → `0.9`
- `docs/knowledge.yaml` — update `kcp_version: "0.9"`, add federation units/examples

### 13. Root files
- `README.md` — update current version badge/reference to 0.9.0
- `CONTRIBUTING.md` — checklist already references "update docs/index.html + docs/knowledge.yaml on every spec release" — verify this step is in the PR checklist
- Version strings: search-replace `kcp_version: "0.8"` → `kcp_version: "0.9"` in all examples and fixtures

---

## `list_manifests` MCP Tool Spec (all three bridges — PARITY)

```
Tool name: list_manifests
Description: List the sub-manifests declared in this knowledge.yaml federation block.
Input: {} (no parameters)
Output: JSON array of { id, url, label, relationship, has_local_mirror, update_frequency }
Returns empty array if no manifests block declared.
```

---

## Semver Adoption

This is the first release using full semver:
- Git tag: `v0.9.0` (not `v0.9`)
- `kcp_version` in manifests: `"0.9"` (consumers use MAJOR.MINOR; patch is tooling-only)
- All version strings in code, pom.xml, pyproject.toml, package.json: `0.9.0`

---

## §3.6 Federation — Full Normative Text (for SPEC.md)

```markdown
## 3.6 Federation

The optional `manifests` block declares external KCP manifests that this manifest has
a relationship with. This enables cross-manifest dependency tracking and federated
knowledge graphs.

### `manifests` block

```yaml
manifests:
  - id: platform
    url: "https://platform-team.example.com/knowledge.yaml"
    label: "Platform Engineering"
    relationship: foundation
    update_frequency: weekly
    local_mirror: "./mirrors/platform-knowledge.yaml"
```

#### `manifests` entry fields

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `id` | REQUIRED | string | Local identifier. MUST match `^[a-z0-9.\-]+$`. MUST be unique within this manifest's `manifests` block. |
| `url` | REQUIRED | string | HTTPS URL of the remote `knowledge.yaml`. MUST use HTTPS. MUST NOT resolve to private address ranges (§14.3). |
| `label` | RECOMMENDED | string | Human-readable description. |
| `relationship` | RECOMMENDED | string | How this sub-manifest relates to the declaring manifest. See values below. |
| `auth` | OPTIONAL | object | Auth block (per §3.3) for fetching this specific manifest. Overrides root `auth` block for this fetch. |
| `update_frequency` | OPTIONAL | string | How often this remote manifest typically changes. Uses the §4.6b vocabulary: `daily`, `weekly`, `monthly`, `rarely`, `never`. Agents MAY use this for cache freshness decisions. |
| `local_mirror` | OPTIONAL | string | Relative path (forward slashes, relative to this manifest) to a local copy of the remote manifest. When present and the file exists, parsers MUST load from that path instead of fetching `url`. |

#### `manifests[].relationship` values

| Value | Meaning |
|-------|---------|
| `child` | Sub-manifest depends on the declaring manifest's context. |
| `foundation` | Sub-manifest provides foundational knowledge the declaring manifest builds on. |
| `governs` | Sub-manifest contains authoritative policies that govern the declaring manifest. |
| `peer` | Sub-manifest is at the same level; the relationship is symmetric. |
| `archive` | Sub-manifest is historical. Agents MAY skip unless specifically requested. |

Unknown `relationship` values MUST be silently ignored.

### Transitive resolution

A manifest declared in a `manifests` block MAY itself contain a `manifests` block.
Parsers MUST resolve the full transitive graph of `manifests` declarations.

**Topology:** DAG with local authority. Each manifest is authoritative only over the
sub-manifests it directly declares. Trust does not propagate transitively.

**Cycle detection:** Parsers MUST maintain a visited set of resolved manifest URLs
across the entire resolution session. A manifest URL already in the visited set MUST
NOT be fetched again. This handles both cycles (A → B → A) and diamonds
(A → B, A → C, B → D, C → D) correctly — D is fetched once.

**Fetch limits:**
- Parsers MUST enforce a maximum of unique manifests per session. RECOMMENDED default: 50.
- Parsers SHOULD emit a warning when this limit is reached.
- Remote manifests larger than 1 MB SHOULD be rejected with a warning.
- Remote manifests containing more than 10,000 units SHOULD be rejected with a warning.

**Fetch timeout:** Parsers MUST enforce a timeout on remote manifest fetches.
RECOMMENDED default: 10 seconds. A timed-out fetch is treated as a network error.

**Local mirror resolution order:**
1. If `local_mirror` is present and the referenced file exists, the parser MUST load
   from that path. `url` is NOT fetched.
2. If `local_mirror` is absent or the file does not exist, the parser SHOULD fetch `url`.
3. If the URL fetch fails, apply `on_failure` behaviour from `external_depends_on` entries
   that reference this manifest.

### `external_depends_on` (unit-level)

A unit may declare cross-manifest dependencies:

```yaml
units:
  - id: data-handling
    path: compliance/data.md
    intent: "How does this service handle personal data?"
    external_depends_on:
      - manifest: security        # references manifests[].id
        unit: gdpr-policy         # unit id in the remote manifest
        on_failure: degrade       # skip | warn | degrade (default: skip)
```

#### `external_depends_on` entry fields

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `manifest` | REQUIRED | string | The `id` of an entry in this manifest's `manifests` block. Unknown IDs MUST produce a validation warning. |
| `unit` | REQUIRED | string | The `id` of a unit in the referenced manifest. Advisory at parse time — existence cannot be verified without fetching. |
| `on_failure` | OPTIONAL | string | Agent behaviour when the external unit cannot be resolved. `skip` (silently ignore, default), `warn` (emit a warning to the operator), `degrade` (agent MUST indicate output is operating with incomplete dependencies). Unknown values MUST be treated as `skip`. |

### `external_relationships` (root-level)

Explicit typed relationships between units across manifest boundaries:

```yaml
external_relationships:
  - from_manifest: security       # OPTIONAL — omit = this manifest
    from_unit: gdpr-policy        # REQUIRED
    to_unit: data-handling        # REQUIRED
    type: governs                 # same vocabulary as §5
```

#### `external_relationships` entry fields

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `from_manifest` | OPTIONAL | string | Source manifest `id`. Omit = this manifest. |
| `from_unit` | REQUIRED | string | Source unit `id`. |
| `to_manifest` | OPTIONAL | string | Target manifest `id`. Omit = this manifest. |
| `to_unit` | REQUIRED | string | Target unit `id`. |
| `type` | REQUIRED | string | Relationship type. Same vocabulary as §5. Unknown types MUST be silently ignored. |

At least one of `from_manifest` or `to_manifest` SHOULD be present (otherwise the
relationship belongs in `relationships`, not `external_relationships`).

### Authority model

Each manifest is authoritative only over the sub-manifests it directly declares.
A manifest does NOT inherit authority over sub-manifests declared by its transitive
dependencies.

Trust propagation across transitive boundaries is an agent-level policy decision,
outside the scope of this specification.

### Self-contained manifests

A manifest with a `manifests` block MUST remain valid and parseable when loaded in
isolation without fetching any remote manifests. The `manifests` block is metadata
for federation-capable tools; tools that do not support federation MUST silently
ignore it (per §2, forward compatibility).

### Known limitations (0.9.0)

- **Version pinning**: Remote manifests are fetched at their current version. Pinning
  to a specific version is not supported. Planned for 0.10.
- **Peer-to-peer without a declaring manifest**: Any manifest can declare sub-manifests,
  but a manifest cannot reference another without one of them declaring the relationship.
  Arbitrary undeclared cross-referencing is not supported.
```

---

## §5 Relationship Types — Updated Table

```markdown
| Type | Meaning | Agent navigation implication |
|------|---------|------------------------------|
| `enables` | `from` enables or unlocks `to` | Load `from` first when user is not yet ready for `to` |
| `context` | `from` provides useful background for `to` (advisory) | Load `from` for deeper understanding; `to` works alone |
| `supersedes` | `from` replaces `to` | Prefer `from`; skip `to` unless historical content requested |
| `contradicts` | `from` conflicts with `to` | Surface both as known conflict; do not treat as simultaneously authoritative |
| `depends_on` | `from` depends on `to` | Load `to` before `from`; `from` may be incomplete without `to` |
| `governs` | `from` declares authoritative policy/standards that `to` must comply with | When compliance/standards questions arise about `to`, load `from` as authoritative source; `from` takes precedence on conflict |

The relationship type vocabulary is shared between `relationships` (§5) and
`external_relationships` (§3.6). All types are valid in both sections.

Unknown relationship types MUST be silently ignored.
```

---

## Execution Order

Run in this order to avoid conflicts:

1. **SPEC.md** — spec text is the source of truth; do this first
2. **schema/knowledge-schema.json** — derive from spec
3. **parsers/java/** — models first, then parser, then validator, then tests
4. **parsers/python/** — models, parser, validator, tests
5. **bridge/typescript/src/** — model, parser, mapper, server, tests
6. **bridge/java/** — same (PARITY)
7. **bridge/python/** — same (PARITY)
8. **conformance/fixtures/** — after parsers pass tests
9. **examples/federation/** — after fixtures
10. **RFC-0003-Federation.md** — update status
11. **CHANGELOG.md** — write entry
12. **docs/** — GH Pages update (REQUIRED)
13. **README.md + version strings** — final sweep

---

## Definition of Done

- [ ] SPEC.md updated with §3.6, §5 `governs`, §8 conformance, §14.3 security
- [ ] `kcp_version` value updated to `"0.9"` throughout spec, examples, fixtures
- [ ] JSON schema covers all new fields
- [ ] Java parser: ManifestRef, ExternalDependency, ExternalRelationship model + parse + validate + tests
- [ ] Python parser: same
- [ ] TypeScript bridge: `list_manifests` tool + federation in model/parser/mapper + tests
- [ ] Java bridge: same (parity)
- [ ] Python bridge: same (parity)
- [ ] Conformance fixtures: 4 level3 + 3 edge-cases
- [ ] RFC-0003 status updated to "Promoted to core"
- [ ] CHANGELOG 0.9.0 entry complete
- [ ] GH Pages docs/ updated
- [ ] All existing tests still pass
- [ ] Git tag: `v0.9.0`
