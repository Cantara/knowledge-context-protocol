# Dependency Graph — 5 of 6 Relationship Types

Demonstrates every relationship type defined in [SPEC.md section 5](../../SPEC.md): `depends_on`, `enables`, `supersedes`, `contradicts`, and `context`.

## Scenario

NovaPlatform is migrating from v1 to v2. The documentation set includes the old architecture, migration guides, deprecated and current API references, evolving security policies, deployment instructions, and troubleshooting. These naturally exercise five of the six relationship types (the sixth, `governs`, is demonstrated in the [federation example](../federation/)).

## Units

| # | Unit | Access | Description |
|---|------|--------|-------------|
| 1 | `platform-overview` | public | Current v1 architecture overview |
| 2 | `migration-guide` | public | Step-by-step v1 to v2 migration |
| 3 | `api-v1-reference` | public | Deprecated v1 API docs |
| 4 | `api-v2-reference` | public | New v2 API docs (supersedes v1) |
| 5 | `deployment-guide` | authenticated | v2 deployment instructions |
| 6 | `legacy-security-policy` | authenticated | Old perimeter-based security (deprecated) |
| 7 | `zero-trust-policy` | authenticated | New zero-trust security (supersedes legacy) |
| 8 | `troubleshooting` | public | Post-deployment diagnostics |

## Relationship types explained

| Type | When to use | Example in this scenario |
|------|-------------|--------------------------|
| `depends_on` | The `from` unit requires the `to` unit to be loaded first. Hard prerequisite. | `migration-guide` depends_on `platform-overview` -- you must understand the current architecture before migrating. |
| `enables` | The `from` unit enables or unlocks the `to` unit. Soft prerequisite from the provider's perspective. | `platform-overview` enables `migration-guide` -- the overview makes the migration guide actionable. |
| `supersedes` | The `from` unit replaces the `to` unit. Agents should prefer the newer version. | `api-v2-reference` supersedes `api-v1-reference` -- v2 docs replace v1 docs. |
| `contradicts` | The `from` unit contains information that conflicts with the `to` unit. Agents should flag the conflict. | `legacy-security-policy` contradicts `zero-trust-policy` -- perimeter trust assumptions conflict with zero-trust principles. |
| `context` | The `from` unit provides background context for interpreting the `to` unit, without being a hard prerequisite. | `legacy-security-policy` provides context for `zero-trust-policy` -- understanding the old model helps explain why the new model exists. |

## `depends_on` vs `enables`

Both express ordering, but from different perspectives:

- **`depends_on`** is consumer-oriented: "I need X before I can be understood."
- **`enables`** is provider-oriented: "I unlock X for the reader."

A single pair of units can have both: `platform-overview` *enables* `migration-guide` (provider view), and `migration-guide` *depends_on* `platform-overview` (consumer view). In practice, use `depends_on` for the inline unit field and either type in the `relationships` section depending on which direction reads more naturally.

## `supersedes` vs `contradicts`

Both express conflict, but with different resolution semantics:

- **`supersedes`**: Clear winner. The newer unit replaces the older one. Agents should prefer the superseding unit and treat the other as deprecated.
- **`contradicts`**: No clear winner. Both units contain valid but conflicting information. Agents should flag the conflict and let the user decide.

In this scenario, `zero-trust-policy` *supersedes* `legacy-security-policy` (use the new one), but `legacy-security-policy` also *contradicts* `zero-trust-policy` (the old model's assumptions directly conflict with the new model's principles -- agents should warn about this).

## Dependency graph

```
platform-overview
  |
  +--[enables/depends_on]--> migration-guide
  |                            |
  |                            +--[depends_on]--> api-v2-reference --[supersedes]--> api-v1-reference
  |                                                |
  |                                                +--[depends_on]--> deployment-guide
  |                                                                     |
  |                                                                     +--[enables/depends_on]--> troubleshooting
  |
legacy-security-policy --[contradicts/context]--> zero-trust-policy
                       <--[supersedes]------------|
```

## Running the validator

```bash
python -m kcp examples/dependency-graph/knowledge.yaml
```
