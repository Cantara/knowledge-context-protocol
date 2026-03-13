# Changelog

All notable changes to the Knowledge Context Protocol specification and reference implementations are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

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
