# API Platform Rate Limits

Demonstrates `rate_limits` at root and unit level, as specified in [SPEC.md section 4.15](../../SPEC.md).

## Scenario

TechCorp publishes API documentation through a KCP manifest. The documentation spans five tiers, from a freely accessible quickstart guide to tightly controlled internal architecture docs. Each tier declares its own advisory rate limits so that consuming agents can self-throttle without waiting for `429 Too Many Requests` responses.

## How `rate_limits` works

### Root vs unit level

The manifest declares a **root-level default**:

```yaml
rate_limits:
  default:
    requests_per_minute: 120
    requests_per_day: 10000
```

This applies to every unit that does not declare its own `rate_limits` block. When a unit **does** declare one, the unit-level block **replaces** the root default entirely for that unit.

### Escalation pattern

| Unit | Access | Sensitivity | req/min | req/day | Source |
|------|--------|-------------|---------|---------|--------|
| `api-quickstart` | public | public | 120 | 10,000 | inherited from root |
| `api-reference` | public | public | 60 | 5,000 | unit override |
| `sdk-guide` | authenticated | internal | 30 | 2,000 | unit override |
| `partner-integration-guide` | restricted | confidential | 10 | 200 | unit override |
| `internal-architecture` | restricted | restricted | 5 | 50 | unit override |

The pattern is deliberate: higher-sensitivity content gets tighter rate limits. This is not a requirement of the spec, but a sensible convention. Public quickstart content is generous (inherit root defaults); internal architecture docs are aggressively throttled.

### Advisory semantics

**`rate_limits` is advisory, not enforced by KCP itself.** The spec states:

> The `rate_limits` block declares the maximum request rate an agent should observe [...] It is advisory metadata that allows agents to self-throttle.

This means:
- A well-behaved agent reads the `rate_limits` block and throttles accordingly.
- A misbehaving agent can ignore it entirely -- the manifest has no enforcement mechanism.
- The server hosting the content may independently enforce rate limits via HTTP 429 responses, but that is outside KCP's scope.

## Relationship types used

This example uses two relationship types:

### `type: enables`

The `from` unit enables or unlocks the `to` unit. In this scenario, the quickstart *enables* the API reference -- reading the quickstart first makes the reference meaningful.

```yaml
- from: api-quickstart
  to: api-reference
  type: enables
```

### `type: depends_on`

The `from` unit depends on the `to` unit. This mirrors the inline `depends_on` list field but lives in the `relationships` section for richer graph semantics.

```yaml
- from: api-reference
  to: internal-architecture
  type: depends_on
```

### `type: context`

The `from` unit provides background context for interpreting the `to` unit, without being a hard prerequisite.

```yaml
- from: partner-integration-guide
  to: internal-architecture
  type: context
```

### `enables` vs `depends_on`

Both express ordering, but from opposite perspectives:
- **`enables`**: "A enables B" -- A is a prerequisite that unlocks B. Direction: A -> B.
- **`depends_on`**: "A depends_on B" -- A requires B to be loaded first. Direction: A -> B means A needs B.

In the `relationships` section, `from: A, to: B, type: enables` means "A enables B" (A should be read before B). `from: A, to: B, type: depends_on` means "A depends on B" (B should be read before A).

## Running the validator

```bash
python -m kcp examples/api-platform-rate-limits/knowledge.yaml
```
