# Federation Example

Demonstrates the hub-and-spoke federation model proposed in RFC-0003.

**Note:** The `manifests` block is defined in [RFC-0003](../../RFC-0003-Federation.md)
and has **not yet been promoted to the core specification**. This example shows the
proposed syntax for cross-manifest federation.

## How it works

A **hub manifest** aggregates knowledge from multiple **sub-manifests** using the
`manifests:` block. Each entry points to a remote `knowledge.yaml` with metadata
about what it contains.

```yaml
manifests:
  - url: https://docs.acme.com/platform/knowledge.yaml
    project: acme-platform
    description: "Core platform architecture, API reference, deployment guides."
    trust_level: internal
```

## Agent workflow

1. Agent discovers the hub manifest (e.g. at `https://hub.acme.com/knowledge.yaml`)
2. Agent reads hub-level units and the `manifests:` list
3. Based on task context, agent selectively fetches relevant sub-manifests
4. Agent merges unit metadata and navigates across the federated knowledge graph

## Hub-level units

The hub can also declare its own units for cross-cutting documentation that spans
multiple sub-projects (e.g. onboarding guides, architecture principles).

## Trust levels

The `trust_level` field is advisory:

| Value | Meaning |
|-------|---------|
| `public` | Openly accessible, no special trust assumptions |
| `internal` | Internal to the organisation, assumed trustworthy |

## Status

RFC-0003 — not yet promoted to core. Syntax may change based on implementation feedback.
