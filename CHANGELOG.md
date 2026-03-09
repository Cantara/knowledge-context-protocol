# Changelog

All notable changes to the Knowledge Context Protocol specification and reference implementations are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

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
