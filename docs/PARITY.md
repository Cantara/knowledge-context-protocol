# Bridge Parity

All three bridges (TypeScript, Java, Python) are required to stay at feature parity on **MCP tools and prompts**.
Static generation CLI flags (Tier 2) are currently TS + Java only — Python support is planned.

**Current version:** 0.26.0 (all three bridges — aligned with KCP spec version). Spec is at v0.26.1 (2026-07-22, `kind: skill` procedural plane) — see **Known gaps** below, this has not yet reached bridge parity.

> Scope note: the `kcp` developer CLI (`cli/` — init, validate, query, stats, and as of
> spec v0.16 `render`) versions independently of the bridges and is outside this parity
> contract. Bridges surface knowledge over MCP; the render pipeline (SPEC.md §16) is a
> consumer-side CLI concern. If bridges later gain render-aware behaviour (e.g. serving
> rendered artifacts), that feature becomes parity-required and belongs in Tier 1.

---

## Rule

> Never ship a version where one bridge has MCP tools or prompts the others lack.

When adding any MCP capability:
1. Implement in TypeScript first (`bridge/typescript/src/`)
2. Implement in Java immediately after (`bridge/java/src/main/java/no/cantara/kcp/mcp/`)
3. Implement in Python (`bridge/python/kcp_mcp/`)
4. Add tests in all three (vitest for TS, JUnit for Java, pytest for Python)
5. Bump all three to the same version and update this file

---

## Tier 1: MCP Tools & Prompts (parity-required — all three bridges)

| Feature | TypeScript | Java | Python | Notes |
|---------|-----------|------|--------|-------|
| MCP Resources (list + read) | ✅ | ✅ | ✅ | |
| `search_knowledge` tool | ✅ | ✅ | ✅ | scoring: trigger=5, intent=3, id/path=1, top-5 |
| `search_knowledge`: `sensitivity_max` filter | ✅ | ✅ | ✅ | public < internal < confidential < restricted |
| `search_knowledge`: `exclude_deprecated` filter | ✅ | ✅ | ✅ | default true |
| `search_knowledge`: `match_reason` field | ✅ | ✅ | ✅ | which scoring rules fired |
| `search_knowledge`: `token_estimate` field | ✅ | ✅ | ✅ | from hints.token_estimate |
| `search_knowledge`: `summary_unit` field | ✅ | ✅ | ✅ | from hints.summary_unit |
| `search_knowledge`: `not_for` filter (§15.11) | ✅ | ✅ | ✅ | strict exclusion + soft demotion with `caution` field |
| `search_knowledge`: temporal query `as_of` / `include_all_temporal` (§15.13) | ✅ | ✅ | ✅ | point-in-time filtering, conflict error on mutual exclusion |
| Federated temporal filtering, all retrieval paths (§3.6 / C18) | ✅ | ✅ | ✅ | `manifests[].temporal` enforced in search **and** `get_unit` / resources; UTC effective date; supersession-aware; `as_of` validated; `include_all_temporal` marks `caution` (issue #98) |
| Attestation gating (§3.2 / C20) | ✅ | ✅ | ✅ | restricted-unit content refused on every retrieval path unless an `attestation` argument is presented; `get_unit` → `attestation_required`; `search` marks `requires_attestation`; bridge never calls `attestation_url` (v0.22) |
| `get_unit` tool | ✅ | ✅ | ✅ | fetch unit file content by id; refuses temporally-excluded units |
| `list_manifests` tool | ✅ | ✅ | ✅ | lists declared sub-manifests (federation §3.6); optional `as_of`, supersession-aware `temporally_active` |
| `get_command_syntax` tool | ✅ | ✅ | ✅ | requires `--commands-dir` |
| `sdd-review` prompt | ✅ | ✅ | ✅ | |
| `kcp-explore` prompt | ✅ | ✅ | ✅ | |

---

## Tier 2: CLI / Static Generation (TypeScript + Java only — Python deferred)

| Feature | TypeScript | Java | Python | Notes |
|---------|-----------|------|--------|-------|
| `--generate-instructions` → stdout | ✅ | ✅ | — | |
| `--audience <value>` | ✅ | ✅ | — | filter by audience field |
| `--output-format full\|compact\|agent` | ✅ | ✅ | — | default: full |
| `--output-dir <path>` | ✅ | ✅ | — | triggers split mode |
| `--split-by directory\|scope\|unit\|none` | ✅ | ✅ | — | generates `applyTo` .instructions.md files |
| `--generate-agent` | ✅ | ✅ | — | writes `.agent.md` frontmatter to stdout |
| `--max-chars <n>` | ✅ | ✅ | — | truncates agent file intelligently |
| `--generate-all` | ✅ | ✅ | — | writes all three tiers to `.github/` |
| `--commands-dir <path>` | ✅ | ✅ | — | loads kcp-commands manifests |

---

## Tier 2 (shared): Runtime flags (all three bridges)

| Feature | TypeScript | Java | Python | Notes |
|---------|-----------|------|--------|-------|
| `--sub-manifests <glob>` | ✅ | ✅ | ✅ | merges additional manifests |
| `--agent-only` | ✅ | ✅ | ✅ | expose only agent-audience units |
| `--no-warnings` | ✅ | ✅ | ✅ | suppress validation warnings |
| `--transport stdio\|http` | ✅ | ✅ | ✅ | |
| `--port <n>` | ✅ | ✅ | ✅ | HTTP transport port |

---

## Test counts (v0.20.0)

| Bridge | Tests |
|--------|------:|
| TypeScript (vitest) | 163 |
| Java (JUnit) | 147 |
| Python (pytest) | 148 |
| **Total** | **458** |

---

## Known gaps (all bridges) — v0.26.1 `kind: skill`

Spec v0.26.1 (2026-07-22, #134) added the procedural plane: `kind: skill` units and the
`action_scope` field (§4.3a — the tools/paths/capabilities a skill procedure may touch).
Verified 2026-07-22:

- **TypeScript** — `bridge/typescript/src/model.ts` and `parser.ts` are symlinks to
  `shared/src/`, which was updated by #134, so parsing/validation of `kind: skill` and
  `action_scope` is already current. However, `mapper.ts`'s manifest-entry builder
  (~line 268) does not include `action_scope` in the fields it copies onto the exposed
  unit entry — it is parsed but not surfaced. Needs a one-line fix + a mapper test.
- **Java** (`bridge/java/`) and **Python** (`bridge/python/`) — not symlinked, real
  separate source trees. Zero references to `action_scope` or `"skill"` found in either.
  Full Tier 1 port required (see **Rule** above) before the next version bump.

This is a real parity gap, not yet closed — tracked here rather than silently left off
this table. Do not claim "full parity" again until all three items above are done.

---

## Version history

| Version | Date | Changes |
|---------|------|---------|
| 0.5.0 | 2026-02 | MCP Resources only |
| 0.6.0 | 2026-03-06 | MCP tools, prompts, `--generate-instructions`, Java parity |
| 0.10.0 | 2026-03-06 | Three-tier static integration, `--generate-all`, full parity |
| 0.14.0 | 2026-03-15 | RFC-0007 query baseline: `sensitivity_max`, `exclude_deprecated`, `match_reason`, `token_estimate`, `summary_unit` — all three bridges. Python bridge added to parity tracking. |
| 0.15.0 | 2026-06-12 | §15.11 `not_for` filtering: strict exclusion + soft score halving with `caution` annotation — all three bridges. |
| 0.20.0 | 2026-06-12 | §15.13 temporal query: `as_of` + `include_all_temporal` parameters, `temporal_query_conflict` error — all three bridges. Bridge versions now aligned with KCP spec version. |
| 0.20.1 | 2026-06-13 | Python bridge: added `sdd-review` and `kcp-explore` MCP prompts — closes last Tier 1 parity gaps. |
| 0.21.0 | 2026-06-13 | Spec v0.21: parsers expose `manifests[].temporal` (RFC-0021) and `discovery.verified_by`/`evidence`; temporal validation (`superseded_by` cycle detection, validity-window §7 warnings) implemented in all four parser/validator implementations. Bridge skip-before-fetch federation temporal filtering deferred. |
| 0.21.1 | 2026-06-13 | C18 hardening (issue #98): federated temporal filtering now enforced on **every** retrieval path (`get_unit`, `read_resource`, `list_resources`), not just `search_knowledge`; effective date pinned to UTC; supersession-aware exclusion (`superseded_by` + active successor → drop); `as_of` ISO-8601 validation; `local_mirror` association canonicalised (symlink-safe) with a warning on no match; `include_all_temporal` results carry a `caution`; `list_manifests` gains optional `as_of`. All three bridges. |
| 0.22.0 | 2026-07-04 | Trust & Attestation (RFC-0004/0002): `trust.agent_requirements` + extended `auth.methods` types (`bearer_token`, `spiffe`, `did`, `http_signature`) parsed/exposed; C20 attestation gating — restricted-unit content refused unless an `attestation` argument is presented, on every retrieval path; `search` marks `requires_attestation`; new `attestation` argument on `get_unit` + `search_knowledge`. Bridge never calls `attestation_url`. All three bridges. |
| 0.23.0 | 2026-07-04 | Trust & Auth Completion (RFC-0002/0004): parsers expose per-unit `auth` override, `trust.provenance.publisher_did`, `trust.audit.provides_access_receipts`/`receipt_format`, and `require_delegation_proof` (closed a spec-vs-parser drift). Validators warn on non-DID `publisher_did` and receipts-without-format. Declaration-level — no new MCP tool behaviour. All four implementations. |
| 0.24.0 | 2026-07-04 | Org-Federation (RFC-0011): parsers expose `manifests[].context` (environment-aware references) and `manifests[].agent_identity` (pre-fetch credential hint) — surfaced by the `kcp render` pipeline. Validators warn on empty `context`, `agent_identity.required` without `credential_hint`, and `issuer_hint` used off `oauth2`. Declaration-level and advisory — no new MCP tool behaviour. All four implementations. |
| 0.25.0 | 2026-07-04 | Economic Metadata (RFC-0005): parsers expose the structured `payment` block (`methods[]` with `x402`/`meter`/`subscription`, `billing_contact`) and `rate_limits` per-tier/`tokens`/`headers`/`backoff` — previously `payment` was an opaque passthrough. The `kcp render` pipeline surfaces both as data (never dereferenced). Validators warn on malformed x402, unknown method type, paid-tier-with-only-free-method, and bad backoff. Advisory — no new MCP tool behaviour. All four implementations. |
| 0.25.1 | 2026-07-04 | Interop Clarifications (kcp-agent cross-test, #114/#115): §4.11 `access` is the auth axis only (anonymous-paid = `public` + `payment`); §4.22 inclusive temporal boundaries + supersession precedence over overlap. New §7 warning: protected units with an auth block declaring only method `none`. Spec text + validators — no new MCP tool behaviour. All four implementations. |
| 0.26.0 | 2026-07-14 | Serving Endpoint Binding + Unit Aliases (RFC-0024 + RFC-0023): parsers expose `unit.aliases` and the `serving` block (validators warn on alias char-rule/collision/>100, error on non-HTTPS serving entry). **Bridge alias resolution** — `get_unit` resolves a declared alias to its canonical unit and leads with `{ matched_alias, canonical_id }`; `search_knowledge` matches alias terms and reports `matched_alias`. The `kcp render` pipeline surfaces `aliases` + `serving` and enforces C22 (trusted→known demotion on a `serving.manifest` retrieval-URL mismatch) via `--retrieved-from`. All four implementations. |

> **Note:** v0.7.0--v0.9.0 were internal development milestones that shipped combined as v0.10.0.
> v0.11.0--v0.13.0 were bridge feature additions that culminated in v0.14.0.
