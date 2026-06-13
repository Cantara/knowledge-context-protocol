# temporal-validity/

**Bi-temporal knowledge: validity windows, future-dated rollout, supersession, and point-in-time queries.**

Demonstrates the `temporal` block (RFC-0010, promoted to SPEC §4.22 in v0.19, with `as_of` queries in §15.13, v0.20) — the features that let a manifest say *when* its knowledge is true, not just *what* it says.

## What this shows

| Pattern | Units | Field usage |
|---------|-------|-------------|
| **Future-dated rollout** | `mfa-policy-2026` | `valid_from` in the future — pre-load the new policy; it activates on its date with no manifest edit |
| **Supersession** | `mfa-policy-legacy → mfa-policy-2026` | `valid_until` + `superseded_by` — the old policy retires cleanly, no "stale" warning, audit trail preserved |
| **Expiring runbook** | `db-migration-runbook` | `valid_until` bounded to the migration window; `superseded_by` the post-migration runbook |
| **Two clocks** | all units | valid-time (`valid_from`/`valid_until`) vs transaction-time (`recorded_at`) — root `temporal.recorded_at` sets the manifest-wide default |
| **Verified provenance** | `gdpr-retention` | `discovery.verification_status: verified` with `verified_by` (RFC-0012/§4.18) |

## Evaluation (today = 2026-06-13)

A default query returns the units active **now**:

- `overview` — always valid (no `temporal` block)
- `mfa-policy-2026` — active since 2026-04-01
- `db-migration-runbook` — active through 2026-06-30
- `gdpr-retention` — active since 2024

…and excludes `mfa-policy-legacy` (retired 2026-03-31) and `db-runbook-postmigration` (not active until 2026-07-01).

## Point-in-time queries (§15.13)

```bash
# What MFA policy was in effect on 2026-02-15?  ->  mfa-policy-legacy
kcp query "mfa policy" --as-of 2026-02-15

# Full audit view: every version with its temporal metadata
kcp query "mfa policy" --include-all-temporal
```

`as_of` and `include_all_temporal` are mutually exclusive; a bridge that implements §15.13 returns `temporal_query_conflict` if both are set. Bridges without temporal evaluation safely return all currently-active units.

## Validate

```bash
kcp validate examples/temporal-validity/knowledge.yaml
```

The validator checks `superseded_by` for cycles (a manifest error) and warns on dangling successors, empty windows (`valid_until` before `valid_from`), and stale units (`valid_until` in the past with no successor). This manifest is clean on all of them.
