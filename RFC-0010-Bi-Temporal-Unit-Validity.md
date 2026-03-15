# RFC-0010: Bi-Temporal Unit Validity

**Status:** Request for Comments
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-03-15
**Discussion:** [#50 KCP Treasure Map Service](https://github.com/Cantara/knowledge-context-protocol/discussions/50)
**Related:** [André Lindenberg — "The Memory Problem No One Talks About: Why AI Agents Need Two Clocks"](https://www.linkedin.com/pulse/memory-problem-one-talks-why-ai-agents-need-two-andr%C3%A9-lindenberg-spjvf/)
**Spec:** [SPEC.md](./SPEC.md) (current: v0.10)

---

## What This RFC Proposes

A `temporal` block on KCP units that tracks two independent timelines for every piece of
declared knowledge:

1. **Valid time** — when the knowledge was true in the real world (`valid_from`, `valid_until`)
2. **Transaction time** — when the manifest recorded this version (`recorded_at`, `superseded_by`)

This is the bi-temporal model from database engineering (SQL:2011), applied to knowledge
manifests. It enables agents and serving layers to answer:

- *"Is this constraint currently active?"* (valid time, present)
- *"What did this manifest declare on the date of the audit?"* (transaction time, past)
- *"When does this policy come into effect?"* (valid time, future)

All additions are backward compatible. All new fields are optional. Existing manifests
require no changes.

**Release staging:**

- **v0.12 (schema):** The `temporal` block as manifest and unit fields. Zero bridge query
  changes required. Authors can immediately declare validity windows and recorded-at dates.
- **v0.13 (query):** Query extensions — `as_of` (point-in-time) and `include_all_temporal`
  (audit mode) filters. Ships after all three bridges implement the RFC-0008 query baseline.

---

## The Problem

### Static manifests lose temporal context

KCP units today express current state. A unit declaring `authority.execute: requires_approval`
is correct today — but if that policy changed last week, there is no record of what it said
before. If an agent acted on the old policy, there is no way to reconstruct what the manifest
declared at that moment.

This matters in three distinct scenarios:

**Compliance audit:** A regulator asks: *"What security policy was in effect when this
production deployment ran on February 15th?"* The manifest cannot answer — it only knows
what is true now. The answer that was correct on February 15th has been silently overwritten.

**Future-dated policy:** A security team wants to declare a new policy effective March 1st
without disrupting agents running today. There is no way to write this in a manifest: the
change either takes effect immediately (with a manual calendar reminder to update the file)
or requires maintaining two separate manifests.

**Knowledge expiry:** A migration runbook is only relevant until the migration completes on
April 30th. After that date, agents loading the manifest will find and load a unit that no
longer describes reality. Without a validity window, stale knowledge silently survives.

### The two clocks problem

As André Lindenberg describes: standard memory answers "what do we currently know?" but
agents in regulated domains need to answer "what did we know *when a decision was made*?"

These are two different questions requiring two different timestamps per fact:

- **Valid time** (`valid_from` / `valid_until`): when the fact was true in the real world.
  An agent should not act on a unit whose valid window does not include today. An agent
  reconstructing past state uses valid time to filter.

- **Transaction time** (`recorded_at` / `superseded_by`): when the manifest recorded this
  version of the fact. Independent of whether the fact was actually true at that point.
  Critical for audit: "what did the manifest say, regardless of what the world looked like?"

The two clocks diverge in exactly the cases that matter most:

> A security policy was effective February 15th, but the manifest author did not update
> `knowledge.yaml` until March 1st. The policy file was correct; the manifest was stale
> for two weeks. An agent acting in that window acted on outdated declared knowledge.

Without bi-temporal timestamps, there is no machine-readable record that this gap existed.

### Concrete scenarios

**Scenario A — Stig Lau, Skatteetaten (Norwegian Tax Authority):**
A KCP federation manifest serves units to agents across 30+ teams. A compliance unit
describing GDPR data retention rules changes due to a new regulation effective April 1st.
The manifest author needs to pre-load the new unit with `valid_from: "2026-04-01"` while
keeping the old unit active until that date — and preserving the old unit for audit queries
about what policy was in effect during Q1. Today there is no way to do this in a single
manifest.

**Scenario B — Mynder compliance platform:**
Mynder serves AI agents that automate GDPR and NIS2 compliance checks. A supervisory
authority audit demands: "Show us what policy your AI system was operating under on
March 15th." Without transaction time on manifest units, Mynder cannot produce a
machine-readable answer — only a prose statement that the policy "was probably" a certain
value on that date.

**Scenario C — ExoCortex developer tooling:**
A deployment runbook is valid until a platform migration completes on June 30th. After
that date, a different runbook applies. Instead of remembering to delete the old unit on
June 30th, the author sets `valid_until: "2026-06-30"` and `superseded_by: deploy-v2`.
Agents automatically load the correct runbook; the old one remains in the manifest for
anyone querying the audit history of Q2 deployments.

---

## Design

### `temporal` block

The `temporal` block on a unit declares its two-clock validity. All four fields are optional.
When absent, the defaults preserve current behaviour: the unit is always valid, and no
transaction time is tracked.

```yaml
units:
  - id: deploy-to-production
    path: ops/deploy.md
    intent: "How do I deploy a new release to production?"
    scope: project
    audience: [operator, agent]

    temporal:
      valid_from: "2026-02-01"      # policy effective from Feb 1
      valid_until: null              # still valid — open-ended
      recorded_at: "2026-02-03"    # manifest updated Feb 3 (two days after effective date)
      superseded_by: null            # not yet replaced
```

**`temporal` block fields:**

| Field | Required | Type | Default | Description |
|-------|----------|------|---------|-------------|
| `temporal.valid_from` | OPTIONAL | ISO 8601 date | null (beginning of time) | The date from which this unit's content is valid in the real world. An agent SHOULD NOT load this unit before this date. |
| `temporal.valid_until` | OPTIONAL | ISO 8601 date | null (open-ended) | The date after which this unit's content is no longer valid. An agent SHOULD NOT load this unit after this date. A null value means the unit is valid indefinitely. |
| `temporal.recorded_at` | OPTIONAL | ISO 8601 date | null | When this version of the unit was added to the manifest. Informational — used by serving layers and audit tools. MAY be set by manifest authors or by tooling. |
| `temporal.superseded_by` | OPTIONAL | string | null | The `id` of a unit within this manifest that replaces this one. Agents MAY follow the reference to find the current version. |

**Date format:** ISO 8601 date string (`YYYY-MM-DD`). Full datetime (`YYYY-MM-DDTHH:MM:SSZ`)
is also valid for sub-day precision. Implementations MUST accept both formats.

---

### Evaluation semantics

**Default behaviour (no `as_of` query parameter):**

When loading a manifest without a temporal query parameter, an agent evaluates each unit's
`temporal` block against the current date (`today`):

```
unit is active  ⟺
    (valid_from is null OR valid_from <= today)
    AND
    (valid_until is null OR valid_until >= today)
```

Units that fail this test SHOULD be excluded from the agent's loaded context. They remain
in the manifest file for historical reference.

**Example: three units, one query, today = 2026-03-15**

```yaml
units:
  - id: deploy-v1
    temporal:
      valid_from: "2026-01-01"
      valid_until: "2026-02-28"       # expired — not loaded
      superseded_by: deploy-v2

  - id: deploy-v2
    temporal:
      valid_from: "2026-03-01"        # active — loaded
      valid_until: null

  - id: incident-response
    temporal:
      valid_from: "2026-04-01"        # future — not yet loaded
```

Result: only `deploy-v2` is loaded. `deploy-v1` is expired; `incident-response` is not yet
active.

**Safe defaults:**

An agent that does not implement temporal evaluation MUST treat all units as active
(same as current behaviour). This ensures backward compatibility: older agents loading a
manifest with `temporal` blocks will load all units rather than silently dropping valid ones.

Serving layers that implement temporal evaluation MUST filter at query time, not at
manifest parse time, so that `as_of` queries (§Query Extensions) can reconstruct past state.

---

### `superseded_by` chain

A unit with `superseded_by` points to its replacement. This enables soft-versioning of
units without breaking the manifest structure:

```yaml
units:
  - id: security-policy-v1
    path: policies/security-2025.md
    intent: "What are the organisation's security constraints for AI agents?"
    temporal:
      valid_from: "2025-01-01"
      valid_until: "2026-01-31"
      recorded_at: "2025-01-01"
      superseded_by: security-policy-v2

  - id: security-policy-v2
    path: policies/security-2026.md
    intent: "What are the organisation's security constraints for AI agents?"
    temporal:
      valid_from: "2026-02-01"
      recorded_at: "2026-01-28"
      superseded_by: null
```

**Chain semantics:**

- Agents loading for present use: follow `superseded_by` to find the current active unit.
- Agents querying with `as_of: "2025-06-01"`: `security-policy-v1` is the active unit
  (valid window covers June 2025). Do not follow `superseded_by`.
- Agents in audit mode (`include_all_temporal: true`): return both units with their
  full temporal metadata.

Cycles in `superseded_by` chains MUST be detected and treated as a manifest error. The
serving layer SHOULD report the cycle and return only units not part of the cycle.

---

### Root-level `temporal` defaults

A root-level `temporal` block MAY declare manifest-wide defaults:

```yaml
kcp_version: "0.12"
project: regulated-knowledge-base

temporal:
  recorded_at: "2026-03-15"   # all units in this manifest version recorded today
```

Unit-level `temporal` blocks override root defaults field-by-field. If a unit declares
`temporal.valid_until` but not `temporal.recorded_at`, the root `recorded_at` applies.
This is the only RFC where field-level inheritance (rather than block-level override)
is used, because temporal metadata is most commonly set manifest-wide by tooling.

---

## Query Extensions (v0.13 — deferred)

> **Deferred to v0.13.** The query extensions require the RFC-0008 structured query baseline
> to be implemented in all three bridges first.

The RFC-0007 query request object gains two optional temporal parameters:

```yaml
terms: ["deployment", "runbook"]
audience: agent
as_of: "2026-02-15"            # point-in-time: return units valid on Feb 15
include_all_temporal: false    # audit mode: return all units regardless of validity
```

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `as_of` | ISO 8601 date | current date | Return only units where `valid_from <= as_of` AND (`valid_until is null` OR `valid_until >= as_of`). Enables point-in-time reconstruction. |
| `include_all_temporal` | boolean | false | If true, return all units regardless of validity window. `superseded_by` metadata is included. For audit and compliance tooling. |

**`as_of` and `include_all_temporal` are mutually exclusive.** If both are provided, the
serving layer MUST return an error: `temporal_query_conflict`.

Implementations that do not support these filters MUST ignore them and return all currently
active units (same as current behaviour). This is safe: the agent gets more units than it
asked for, not fewer.

---

## Interaction with Existing Fields

### `temporal` and RFC-0008 `freshness_policy`

These solve different problems:

| Field | Question answered |
|-------|------------------|
| `freshness_policy.max_age` (RFC-0008) | How recently was the *file content* fetched? Should the agent re-fetch? |
| `temporal.valid_until` (this RFC) | For how long is this knowledge *valid in the world*? |

A deployment runbook may be fetched daily (`freshness_policy.max_age: 1d`) but only valid
until the platform migration completes (`valid_until: "2026-06-30"`). The two operate
independently. Both may be declared on the same unit.

### `temporal` and RFC-0009 `visibility`

`visibility.conditions` can be combined with `temporal` to express context-dependent validity:

```yaml
units:
  - id: incident-response-v2
    temporal:
      valid_from: "2026-04-01"
    visibility:
      default: confidential
      conditions:
        - when:
            environment: [production]
          then:
            sensitivity: restricted
            requires_auth: true
```

This unit becomes active April 1st, but its visibility rules apply from the moment it
becomes active. `temporal` gates whether the unit exists; `visibility` gates who sees it
once it does.

### `temporal` and `compliance.sensitivity`

The `compliance.sensitivity` field (§4.12) describes the sensitivity of content *now*.
`temporal` adds *when* that description is valid. An auditor querying `as_of` a past date
sees the sensitivity declaration that was in effect then, not the current one — which may
differ if a policy change occurred between the two dates.

---

## Complete Examples

### Example 1: Future-dated security policy rollout

```yaml
kcp_version: "0.12"
project: platform-security

temporal:
  recorded_at: "2026-03-15"

units:
  - id: mfa-policy-legacy
    path: security/mfa-2025.md
    intent: "What MFA requirements apply to agent authentication?"
    scope: project
    audience: [developer, operator, agent]
    temporal:
      valid_from: "2025-01-01"
      valid_until: "2026-03-31"
      superseded_by: mfa-policy-2026

  - id: mfa-policy-2026
    path: security/mfa-2026.md
    intent: "What MFA requirements apply to agent authentication?"
    scope: project
    audience: [developer, operator, agent]
    temporal:
      valid_from: "2026-04-01"    # future-dated: pre-loaded, activates April 1
      valid_until: null
```

**What this declares:**
- Today (March 15): agents load `mfa-policy-legacy` — `mfa-policy-2026` is future-dated.
- April 1 onwards: agents load `mfa-policy-2026` automatically — no manual manifest update needed.
- Audit query `as_of: "2026-01-15"`: returns `mfa-policy-legacy` — the policy in effect in January.

### Example 2: Post-migration runbook cleanup

```yaml
units:
  - id: deploy-kubernetes-v1
    path: ops/deploy-k8s-v1.md
    intent: "How do I deploy to the current Kubernetes cluster?"
    scope: project
    audience: [operator, agent]
    temporal:
      valid_from: "2025-06-01"
      valid_until: "2026-06-30"    # platform migration completes June 30
      recorded_at: "2025-06-01"
      superseded_by: deploy-kubernetes-v2

  - id: deploy-kubernetes-v2
    path: ops/deploy-k8s-v2.md
    intent: "How do I deploy to the current Kubernetes cluster?"
    scope: project
    audience: [operator, agent]
    temporal:
      valid_from: "2026-07-01"    # new runbook becomes active post-migration
      recorded_at: "2026-03-15"   # pre-authored, future-dated
```

### Example 3: Compliance audit trail — full temporal manifest

```yaml
kcp_version: "0.12"
project: gdpr-compliance

temporal:
  recorded_at: "2026-03-01"

units:
  - id: gdpr-retention-2024
    path: compliance/gdpr-retention-2024.md
    intent: "What are the GDPR data retention rules for this organisation?"
    scope: project
    audience: [operator, agent, security_auditor]
    temporal:
      valid_from: "2024-01-01"
      valid_until: "2025-12-31"
      recorded_at: "2024-01-01"
      superseded_by: gdpr-retention-2025

  - id: gdpr-retention-2025
    path: compliance/gdpr-retention-2025.md
    intent: "What are the GDPR data retention rules for this organisation?"
    scope: project
    audience: [operator, agent, security_auditor]
    temporal:
      valid_from: "2026-01-01"
      valid_until: null
      recorded_at: "2025-12-15"
      superseded_by: null
    authority:
      read: initiative
      summarize: initiative
      modify: denied
      share_externally: requires_approval
      execute: denied
```

**Audit query `as_of: "2025-06-01"` returns:** `gdpr-retention-2024` — what the retention
policy was in mid-2025.
**Present query (default):** `gdpr-retention-2025` — the current policy.
**`include_all_temporal: true`:** both units with full temporal metadata — full audit view.

---

## Conformance

| Feature | Level | Notes |
|---------|-------|-------|
| `temporal.valid_from` / `valid_until` on unit | Level 2 | Agent filters units by validity window |
| `temporal.recorded_at` on unit | Level 2 | Informational; no evaluation required |
| `temporal.superseded_by` chain | Level 2 | Agent follows chain to current unit |
| Root-level `temporal` defaults | Level 2 | Field-level inheritance to units |
| `as_of` query parameter | Level 3 (v0.13) | Deferred — requires bridge query baseline |
| `include_all_temporal` query parameter | Level 3 (v0.13) | Deferred — audit mode |

A unit that adds only `valid_from` and `valid_until` meets Level 2. Point-in-time and audit
queries are Level 3 serving-layer capabilities.

---

## Backward Compatibility

| Addition | v0.10 parser behaviour | Risk |
|----------|------------------------|------|
| `temporal` block on unit | Silently ignored per SPEC.md §2 | None |
| `temporal` block at root | Silently ignored per SPEC.md §2 | None |
| `kcp_version: "0.12"` | "Unknown version" warning per §6.1 | Warning only |
| `as_of` / `include_all_temporal` query params | Ignored; all units returned | None — safe default |

Existing manifests with no `temporal` blocks remain fully valid. A unit with no `temporal`
block is treated as always-valid by all implementations. This RFC does not deprecate any
existing field.

---

## Open Questions

**1. Sub-day precision for `valid_from` / `valid_until`**

ISO 8601 date (`YYYY-MM-DD`) is sufficient for policy and compliance use cases. Are there
agent tooling scenarios where hour-level precision matters — e.g. a constraint active only
during a maintenance window? If so, should datetime precision be required or optional?

**2. `recorded_at` — author-set vs tooling-set**

`recorded_at` is most accurate when set by the tooling that writes the manifest (e.g.
`synthesis export --format kcp` could inject the export timestamp automatically). Should
the spec RECOMMEND that tooling set `recorded_at` and authors only set `valid_from` /
`valid_until`? Or should authors always set it manually?

**3. `superseded_by` across manifests**

Currently `superseded_by` references a unit `id` within the same manifest. Should it
support cross-manifest references (e.g. a unit in a federated sub-manifest superseding
a unit in the hub manifest)? Cross-manifest `superseded_by` would require URL-qualified
references and creates federation-level complexity.

**4. Validity window conflicts in federated queries**

If a hub manifest and a sub-manifest both declare units with overlapping valid windows for
the same concept, which takes precedence? The current proposal is that each manifest's units
stand alone — federation does not merge or resolve overlapping validity windows. Is this
sufficient, or do federated deployments need a temporal resolution policy?

**5. Serving layer responsibility for transaction time**

The `recorded_at` and `superseded_by` fields represent transaction time — when the manifest
*recorded* a fact, not when the fact became true. Full bi-temporal capability requires the
*serving layer* to maintain an immutable append-only history of manifest versions, not just
the manifest file itself. Should the spec define a RECOMMENDED serving layer history
interface, or leave this as an implementation concern?

**6. Time zone handling**

ISO 8601 date strings are date-only (`2026-04-01`). Should these be interpreted as UTC
midnight, or as "start of the manifest author's local day"? For global federations where
hub and sub-manifests are authored in different timezones, this matters. Recommendation:
treat all date-only values as UTC midnight unless a timezone offset is explicitly appended.

---

## Relationship to Other RFCs

- **RFC-0008 (Agent Readiness):** `freshness_policy` (RFC-0008 §3) and `temporal` (this RFC)
  are complementary freshness signals. `freshness_policy` governs file-fetch recency;
  `temporal` governs real-world validity. Both may be declared on the same unit.
- **RFC-0009 (Visibility and Authority):** `temporal` gates *whether* a unit exists for the
  agent; `visibility` gates *who* sees it once it does. `visibility.conditions` MAY be
  combined with `temporal` for context-dependent future policy rollouts.
- **RFC-0004 (Trust and Compliance):** `compliance.sensitivity` declares the sensitivity of
  content *now*. `temporal` + `as_of` queries allow audit tooling to retrieve the
  sensitivity declaration that was in effect at any past date.
- **RFC-0007 (Query Vocabulary):** The v0.13 query extensions (`as_of`,
  `include_all_temporal`) are additive parameters on the RFC-0007 query shape.

---

*Knowledge Context Protocol — [eXOReaction AS](https://www.exoreaction.com), Oslo, Norway.*
