# RFC-0003: Cross-Manifest Federation

**Status:** Request for Comments
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-02-28
**Supersedes:** Proposal M in [RFC-0001](./RFC-0001-KCP-Extended.md)
**Issue:** [#12](https://github.com/Cantara/knowledge-context-protocol/issues/12)
**Discussion:** [GitHub Issues](https://github.com/Cantara/knowledge-context-protocol/issues/12)
**Spec:** [SPEC.md](./SPEC.md) (current: v0.3)
**Depends on:** [RFC-0002](./RFC-0002-Auth-and-Delegation.md) (auth for remote manifests)

---

## What This RFC Proposes

A mechanism for composing multiple `knowledge.yaml` manifests into a navigable knowledge graph — while preserving the "each manifest is self-contained and requires no server" guarantee that makes KCP simple to adopt.

Three additions:

1. **Root-level `manifests` block** — a hub manifest declares the sub-manifests it federates, with labels and relationship types.
2. **Unit-level `external_depends_on`** — a unit may declare that it depends on a unit in a declared sub-manifest.
3. **Root-level `external_relationships`** — explicit directional relationships between units across manifests.

The model is **hub-and-spoke only** for this RFC. Arbitrary peer-to-peer cross-referencing is deferred — it creates cycles and trust problems that require a separate design.

---

## Why Federation Was Deferred Until Now

SPEC.md §1.3 states: *"Cross-manifest relationships are out of scope for this version."* That decision was correct for v0.1–v0.3:

| Concern | Status |
|---------|--------|
| Circular reference risk | Mitigated — cycle detection already in spec §4.7; §12.3 extends it to remote fetches |
| Authentication complexity | Addressed — RFC-0002 defines `auth` blocks; per-manifest auth is now expressible |
| Caching complexity | Mitigated — `update_frequency` (v0.3 core) gives freshness signals; §12.3 recommends max fetch depth |
| Implementation burden | Acceptable — hub-and-spoke is significantly simpler than arbitrary peer-to-peer |

The original three-field minimal spec did not need federation. Enterprise deployments do. The real-world use case that motivated KCP — Synthesis indexing 8,934 files across three workspaces — is inherently cross-manifest, and every large organisation hits this boundary quickly.

---

## The Problem in Concrete Terms

Consider a medium-sized engineering organisation:

```
platform-team/knowledge.yaml       — infrastructure, deployment, observability
security-team/knowledge.yaml       — compliance, threat models, GDPR guides
product-alpha/knowledge.yaml       — feature docs, API guides, runbooks
product-beta/knowledge.yaml        — feature docs, API guides, runbooks
shared-standards/knowledge.yaml    — org-wide conventions, ADRs, style guides
```

An agent helping a developer on product-alpha needs to:
- Load `product-alpha/knowledge.yaml` for local context
- Know that `product-alpha`'s deployment guide **depends on** `platform-team`'s deployment architecture
- Know that `product-alpha`'s GDPR handling **is governed by** `security-team`'s compliance policy
- Know that `product-alpha`'s API design **should follow** `shared-standards`'s conventions

None of these cross-manifest relationships are expressible in v0.3. Each manifest is an island. Agents must discover and load all five manifests independently, with no way to understand the dependency ordering or relationship types between them.

---

## Proposal 1: Root-level `manifests` block

A hub manifest declares which sub-manifests it federates:

```yaml
kcp_version: "0.3"
project: enterprise-knowledge-hub
version: 1.0.0
updated: "2026-02-28"

manifests:
  - id: platform
    url: "https://platform-team.example.com/knowledge.yaml"
    label: "Platform Team — infrastructure, deployment, observability"
    relationship: foundation        # this sub-manifest is foundational to the hub
    auth:                           # optional; overrides root auth block for this manifest
      methods:
        - type: oauth2
          flow: client_credentials
          token_endpoint: "https://platform-team.example.com/auth/token"
          scopes: ["read:knowledge"]

  - id: security
    url: "https://security-team.example.com/knowledge.yaml"
    label: "Security Team — compliance, GDPR, threat models"
    relationship: governance        # this sub-manifest governs content in the hub

  - id: shared-standards
    url: "https://standards.example.com/knowledge.yaml"
    label: "Shared Engineering Standards — ADRs, style guides, conventions"
    relationship: foundation

  - id: product-alpha
    url: "https://product-alpha.example.com/knowledge.yaml"
    label: "Product Alpha — feature docs, API guides, runbooks"
    relationship: child             # this sub-manifest depends on hub-level context
```

### `manifests` entry fields

| Field | Required | Description |
|-------|----------|-------------|
| `id` | REQUIRED | Local identifier used in `external_depends_on` references. Must satisfy the same pattern as unit IDs: `^[a-z0-9.\-]+$`. |
| `url` | REQUIRED | HTTPS URL of the remote `knowledge.yaml`. MUST use HTTPS. MUST NOT resolve to private address ranges (see §12.3). |
| `label` | RECOMMENDED | Human-readable description of this sub-manifest. |
| `relationship` | RECOMMENDED | How this sub-manifest relates to the hub. See table below. |
| `auth` | OPTIONAL | Auth block (per RFC-0002) for accessing this specific remote manifest. Overrides the root `auth` block for this fetch. |
| `version_pin` | OPTIONAL | Pin to a specific manifest `version` value. See Open Question 3. |

### `relationship` values for manifests

| Value | Meaning |
|-------|---------|
| `child` | Sub-manifest depends on hub context. Its units inherit hub-level context. |
| `foundation` | Sub-manifest provides foundational knowledge that hub units build on. |
| `governance` | Sub-manifest contains authoritative policies that govern hub units. |
| `peer` | Sub-manifest is at the same level. Relationships between them are symmetric. |
| `archive` | Sub-manifest is historical. Agents MAY skip it unless specifically requested. |

Unknown `relationship` values MUST be silently ignored.

---

## Proposal 2: Unit-level `external_depends_on`

A unit may declare that it depends on a unit in a sub-manifest declared in the `manifests` block:

```yaml
units:
  - id: product-deployment-guide
    path: ops/deployment.md
    intent: "How do I deploy product-alpha to production?"
    scope: project
    audience: [operator, developer, agent]
    depends_on: [product-architecture]     # local dependency (existing §4.7)
    external_depends_on:                   # cross-manifest dependency (new)
      - manifest: platform                 # references manifests[].id
        unit: deployment-architecture      # references a unit.id in that manifest
        required: false                    # advisory; agent continues if unresolvable

  - id: gdpr-data-handling
    path: compliance/data-handling.md
    intent: "How does product-alpha handle personal data under GDPR?"
    scope: project
    audience: [developer, operator, agent]
    external_depends_on:
      - manifest: security
        unit: gdpr-policy
        required: true                     # agent SHOULD surface failure if unresolvable
      - manifest: shared-standards
        unit: data-classification-guide
        required: false
```

### `external_depends_on` entry fields

| Field | Required | Description |
|-------|----------|-------------|
| `manifest` | REQUIRED | The `id` of an entry in the root `manifests` block. |
| `unit` | REQUIRED | The `id` of a unit in the referenced manifest. |
| `required` | OPTIONAL | If `true`, agents SHOULD surface a warning if the external unit cannot be resolved. Default: `false`. |

### Resolution rules

1. The `manifest` value MUST reference an `id` declared in the root `manifests` block. Unknown manifest IDs MUST produce a validation warning and be silently ignored at runtime.
2. The `unit` value is advisory at parse time — the referenced unit's existence cannot be verified without fetching the remote manifest.
3. If the remote manifest cannot be fetched (network error, auth failure), and `required: false`, the dependency is silently skipped. If `required: true`, the agent SHOULD emit a warning.
4. Cycle detection applies across manifest boundaries — see §Cycle Detection below.

---

## Proposal 3: Root-level `external_relationships`

Explicit typed relationships between units across manifests. Extends the existing `relationships` section (SPEC.md §5):

```yaml
external_relationships:
  - from_manifest: product-alpha          # manifests[].id; omit = this manifest
    from_unit: deployment-guide
    to_manifest: platform                 # manifests[].id
    to_unit: platform-deployment-architecture
    type: depends_on                      # enables | context | depends_on | supersedes | contradicts

  - from_manifest: security
    from_unit: gdpr-policy
    to_unit: gdpr-data-handling           # omitting to_manifest = this manifest
    type: governance                      # new relationship type for cross-manifest use

  - from_unit: api-design-guide           # omitting from_manifest = this manifest
    to_manifest: shared-standards
    to_unit: rest-api-conventions
    type: context
```

### `external_relationships` entry fields

| Field | Required | Description |
|-------|----------|-------------|
| `from_manifest` | OPTIONAL | Source manifest `id`. Omit = this manifest. |
| `from_unit` | REQUIRED | Source unit `id`. |
| `to_manifest` | OPTIONAL | Target manifest `id`. Omit = this manifest. |
| `to_unit` | REQUIRED | Target unit `id`. |
| `type` | REQUIRED | Relationship type. Existing types (`enables`, `context`, `supersedes`, `contradicts`) plus `depends_on` and `governance`. Unknown types MUST be silently ignored. |

---

## Cycle Detection

Cross-manifest cycle detection is an extension of the existing rule in SPEC.md §4.7.

**Within a manifest** (existing rule): parsers detect cycles in `depends_on` graphs using DFS. The edge closing the cycle is silently ignored.

**Across manifests** (new rule): parsers MUST maintain a visited set of manifest URLs across the fetch chain. A manifest URL that appears in its own transitive fetch chain MUST be silently ignored (the fetch is not performed). This prevents infinite loops in hub-and-spoke hierarchies where a sub-manifest also declares a `manifests` block pointing back to the hub.

**Maximum depth**: parsers MUST enforce a maximum federation depth. The RECOMMENDED default is **3** (hub → sub-manifest → sub-sub-manifest → stop). This is separate from the `max_fetch_depth` for remote content in SPEC.md §12.3 (which defaults to 5 for individual URLs).

```
depth 0: enterprise-knowledge-hub/knowledge.yaml     (the hub)
depth 1: platform-team/knowledge.yaml                (declared in hub.manifests)
depth 2: platform-infra/knowledge.yaml               (declared in platform.manifests)
depth 3: STOP — do not fetch further sub-manifests
```

---

## Security Constraints

All security constraints from SPEC.md §12.3 (Remote Content) apply to federated manifest fetches:

- Remote manifest URLs MUST use HTTPS.
- Parsers MUST NOT resolve URLs targeting private address ranges (RFC 1918), link-local, or loopback addresses. This check MUST be performed after DNS resolution (guards against DNS rebinding).
- The YAML safety requirements of §12.2 apply to all remote manifests.

Additional constraints for federation:

- **Manifest size limit**: Parsers SHOULD enforce a maximum size for remote manifests (RECOMMENDED: 1 MB). A manifest larger than this SHOULD be rejected with a warning.
- **Unit count limit**: Parsers SHOULD enforce a maximum number of units per remote manifest (RECOMMENDED: 10,000).
- **No transitive trust escalation**: A remote manifest MUST NOT grant more access permissions than the hub manifest. If a remote manifest's `auth` block declares broader access than the hub, the narrower constraint applies.

---

## Auth for Remote Manifests

Each entry in `manifests` MAY include an `auth` block (per RFC-0002) describing how to authenticate when fetching that specific manifest:

```yaml
manifests:
  - id: platform
    url: "https://platform-team.example.com/knowledge.yaml"
    auth:
      methods:
        - type: oauth2
          flow: client_credentials
          token_endpoint: "https://platform-team.example.com/auth/token"
          scopes: ["read:knowledge"]
```

If no per-manifest `auth` block is provided, the root `auth` block (if present) is used for all manifest fetches. If neither is present, the fetch is attempted without credentials.

---

## The `x-external-ref` Interim Convention

Issue #12 proposed an `x-external-ref` convention for use before federation is formalised. This convention is still valid for projects that want cross-manifest navigation today without waiting for a spec update:

```yaml
units:
  - id: deployment-guide
    path: ops/deployment.md
    intent: "How do I deploy this service?"
    x-external-ref:
      - manifest: "https://platform-team.example.com/knowledge.yaml"
        unit_id: deployment-architecture
        relationship: depends_on
```

Tools that understand `x-external-ref` can use it. Tools that do not MUST silently ignore it (per SPEC.md §7). When RFC-0003 is promoted to core, `x-external-ref` entries can be migrated to `external_depends_on` mechanically.

---

## Complete Example

A hub manifest for a multi-team engineering organisation, with Synthesis as the knowledge server:

```yaml
kcp_version: "0.3"
project: acme-engineering-knowledge
version: 1.0.0
updated: "2026-02-28"
language: "en"
license: "proprietary"
indexing: "metadata-only"

auth:
  methods:
    - type: oauth2
      flow: client_credentials
      token_endpoint: "https://auth.acme.com/token"
      scopes: ["read:knowledge"]

manifests:
  - id: platform
    url: "https://knowledge.platform.acme.com/knowledge.yaml"
    label: "Platform Engineering — infrastructure, CI/CD, observability"
    relationship: foundation

  - id: security
    url: "https://knowledge.security.acme.com/knowledge.yaml"
    label: "Security & Compliance — GDPR, NIS2, threat models"
    relationship: governance

  - id: shared-standards
    url: "https://knowledge.standards.acme.com/knowledge.yaml"
    label: "Engineering Standards — ADRs, API conventions, style guides"
    relationship: foundation

units:
  - id: hub-overview
    path: README.md
    intent: "What does the ACME engineering knowledge base cover and how is it organised?"
    scope: global
    audience: [human, agent]

  - id: onboarding-guide
    path: docs/onboarding.md
    intent: "What does a new engineer need to read to become productive at ACME?"
    scope: global
    audience: [developer, agent]
    validated: "2026-02-28"
    depends_on: [hub-overview]
    external_depends_on:
      - manifest: platform
        unit: getting-started
        required: false
      - manifest: shared-standards
        unit: engineering-handbook
        required: false

  - id: incident-response
    path: ops/incident-response.md
    intent: "How do we respond to a production incident?"
    scope: global
    audience: [operator, agent]
    access: restricted
    auth_scope: on-call-team
    external_depends_on:
      - manifest: platform
        unit: observability-stack
        required: true

external_relationships:
  - from_unit: incident-response
    to_manifest: platform
    to_unit: alerting-runbook
    type: depends_on

  - from_manifest: security
    from_unit: gdpr-policy
    to_unit: onboarding-guide
    type: governance
```

---

## Relationship to Synthesis

`synthesis export --format kcp` generates a `knowledge.yaml` for a single workspace. For Synthesis multi-workspace installations — the scenario that originally motivated KCP — the natural extension is a `synthesis federate` command that:

1. Generates per-workspace `knowledge.yaml` files
2. Generates a root hub manifest with a `manifests` block pointing to each workspace manifest
3. Infers `external_depends_on` entries from cross-workspace dependency data already in the Synthesis index

This would make RFC-0003 immediately useful for all Synthesis multi-workspace users without requiring manual manifest authoring.

---

## Conformance Level Implications

This RFC proposes the following additions to SPEC.md §8 when promoted to core:

- **Level 3**: `manifests` block (hub-and-spoke federation declaration)
- **Level 3**: `external_depends_on` on units
- **Level 3**: `external_relationships` section

Federation is an optional Level 3 feature. A manifest without a `manifests` block is fully conformant at any level.

---

## Open Questions

**1. Hub-and-spoke only, or also peer-to-peer?**
This RFC restricts federation to hub-and-spoke: a hub declares its sub-manifests; cross-manifest references are only valid within that declared hierarchy. Should peer-to-peer references (manifest A references manifest B without a hub) be supported in the same version, or deferred?

The argument for hub-and-spoke only: it prevents arbitrary cross-referencing, makes cycle detection tractable, and gives a clear trust boundary (the hub author controls the federation graph). The argument for peer-to-peer: it's more flexible and matches how real cross-team dependencies actually work.

**2. Should sub-manifests be able to declare their own `manifests` blocks?**
In the hub-and-spoke model, the hub declares all sub-manifests. If sub-manifests can also declare `manifests` blocks (pointing to further sub-manifests), the topology becomes a tree. Should this be allowed, or should only the root hub be permitted to declare sub-manifests?

**3. Version pinning**
Should `manifests` entries support a `version_pin` field that locks to a specific `version` value in the remote manifest? This prevents a remote manifest update from silently breaking a hub's cross-manifest relationships. But it requires manifest authors to update version pins when sub-manifests change, adding operational overhead.

**4. `synthesis federate` implementation**
What would Synthesis need to change to generate a federated root manifest from a multi-workspace index? Is the cross-workspace dependency data currently in the Synthesis index sufficient to infer `external_depends_on` entries automatically, or does it require manual annotation?

**5. Offline / air-gapped deployments**
How should parsers behave when remote manifests are not reachable? The current proposal: `required: false` dependencies are silently skipped; `required: true` emit a warning. Is this sufficient, or should there be a `cache_remote_manifests` option that stores fetched manifests locally for offline access?

**6. Should `external_relationships` support relationship types beyond the existing set?**
The proposal adds `depends_on` and `governance` to the relationship type vocabulary. Are there other cross-manifest relationship types that commonly appear in practice?

---

## References

- [KCP §1.3: Multiple Manifests](./SPEC.md#13-multiple-manifests) — current "out of scope" decision
- [KCP §12.3: Remote Content](./SPEC.md#123-remote-content) — security constraints for remote fetches
- [RFC-0002: Auth and Delegation](./RFC-0002-Auth-and-Delegation.md) — auth for remote manifests
- [Synthesis multi-workspace support](https://github.com/exoreaction/Synthesis)
- [JSON-LD Remote Contexts](https://www.w3.org/TR/json-ld11/#remote-contexts) — precedent for federated document graphs
- [IETF RFC 8288: Web Linking](https://datatracker.ietf.org/doc/html/rfc8288) — typed links between resources
- [IETF RFC 7807: Problem Details](https://datatracker.ietf.org/doc/html/rfc7807) — structured error reporting for remote fetch failures
- [OpenAPI `$ref` remote references](https://spec.openapis.org/oas/v3.1.0#reference-object) — precedent for cross-document references in API specs

---

## How to Participate

- **Comment on [Issue #12](https://github.com/Cantara/knowledge-context-protocol/issues/12)** — especially on the open questions above
- **Open a new issue** if you have a use case not covered by the hub-and-spoke model
- **Submit a PR** to this document if you have concrete improvements to the field definitions

This RFC will inform v0.4 or later. The auth RFC (RFC-0002) should stabilise first, since federation depends on it for remote manifest authentication.

---

*Knowledge Context Protocol — proposed by [eXOReaction AS](https://www.exoreaction.com), Oslo, Norway.*
