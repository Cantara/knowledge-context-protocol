# Federation Example

Demonstrates the DAG federation model from KCP v0.9 (SPEC.md section 3.6).

## How it works

A manifest aggregates knowledge from multiple **sub-manifests** using the
`manifests:` block. Each entry points to a remote `knowledge.yaml` with an `id`
(used for cross-references), `url`, and optional metadata.

```yaml
manifests:
  - id: platform
    url: "https://docs.acme.com/platform/knowledge.yaml"
    label: "Core Platform"
    relationship: foundation
    update_frequency: weekly
```

## Topology

The federation topology is a **DAG with local authority** -- not hub-and-spoke.
Any manifest MAY declare sub-manifests. Cycle detection uses a visited URL set
per resolution session.

## Manifest relationships

| Value | Meaning |
|-------|---------|
| `child` | Sub-project owned by this manifest's team |
| `foundation` | Foundational dependency -- this manifest builds on it |
| `governs` | Governance/policy manifest that constrains this one |
| `peer` | Sibling at the same level |
| `archive` | Historical/archived manifest |

## Cross-manifest dependencies

Units can declare dependencies on units in other manifests using
`external_depends_on`:

```yaml
units:
  - id: onboarding
    external_depends_on:
      - manifest: platform
        unit: quickstart
        on_failure: warn
```

The `on_failure` field controls what happens when the remote unit cannot be
resolved: `skip` (default, silently ignore), `warn` (log warning), or
`degrade` (unit functions with reduced capability).

## Cross-manifest relationships

The `external_relationships` block expresses relationships between units in
different manifests using the same shared vocabulary as intra-manifest
relationships (`enables`, `context`, `supersedes`, `contradicts`, `depends_on`,
`governs`).

```yaml
external_relationships:
  - from_unit: architecture-principles
    to_manifest: security
    to_unit: security-standards
    type: governs
```

## Air-gapped / offline support

Use `local_mirror` to provide a local copy for air-gapped environments:

```yaml
manifests:
  - id: oss-lib
    url: "https://github.com/acme/lib/raw/main/knowledge.yaml"
    local_mirror: "./mirrors/oss-lib.yaml"
```

Resolution order: local file first (if it exists), then fetch URL, then apply
`on_failure`.

## Agent workflow

1. Agent discovers the hub manifest
2. Agent reads hub-level units and the `manifests:` list
3. Based on task context, agent selectively fetches relevant sub-manifests
4. Agent merges unit metadata and navigates across the federated knowledge graph
5. Cross-manifest relationships enable the agent to understand governance,
   dependency, and context boundaries across teams

## Status

Core specification -- KCP v0.9, section 3.6.
