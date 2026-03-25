# RFC-0008: Agent-Readiness

**Status:** Fully Accepted — manifest fields (`freshness_policy`, `requires_capabilities`, `/.well-known/kcp.json`) promoted in SPEC.md §§4,5,13 (v0.11–v0.12); query extensions (`has_capabilities`, `exclude_stale`, `federation_scope`, `source_manifest`) promoted in SPEC.md §15 (v0.14)
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-03-13
**Discussion:** [#50 KCP Treasure Map Service](https://github.com/Cantara/knowledge-context-protocol/discussions/50)
**Spec:** [SPEC.md](./SPEC.md) (current: v0.14)

---

## What This RFC Proposes

Four additions that together make KCP manifests *agent-ready* — giving agents the signals they
need to make quality pre-invocation decisions about what to load, what they can act on, and
whether content is still current:

1. **Enterprise Bootstrap via `llms.txt`** — cold discovery without a central registry
2. **Capability Declarations** — `requires_capabilities` on units + `has_capabilities` query filter
3. **Freshness Policy** — `freshness_policy` block on root and units + `exclude_stale` query filter
4. **Cross-Manifest Query** — `federation_scope: declared` extension to RFC-0007 query vocabulary

All additions are backward compatible. A v0.11 `knowledge.yaml` validates against v0.10 parsers
with warnings only. Implementations that do not support new query parameters MUST treat them as
absent and behave as if the default values were specified.

**Release staging.** This RFC covers two ship waves:

- **v0.11 (schema):** Items 1–3 (manifest fields + bootstrap guidance). Zero bridge changes
  required. Operators can declare `freshness_policy` and `requires_capabilities` immediately.
- **v0.12 (query):** Query extensions — `has_capabilities`, `exclude_stale`,
  `federation_scope`, `source_manifest`. Ships after all three bridges implement the RFC-0007
  query baseline (`audience`, `max_token_budget`, `sensitivity_max` filters).

---

## §1 Enterprise Bootstrap via `llms.txt`

### §1.1 The Cold-Discovery Problem

KCP manifests define how agents navigate knowledge once they have found a manifest. But how does
an agent find the manifest in the first place?

Today two mechanisms exist:

- **`/.well-known/kcp.json`** — works when the agent already knows KCP is deployed at a domain.
- **`manifests[]` federation** — works when the agent already has a hub manifest URL.

Neither helps an agent that knows only a domain name or organisation name. This is the *cold
discovery* problem: the agent has no entry point.

A central registry would solve this but introduces a trust propagation problem (whose registry?
what verification?) and a single point of failure. Discussion #50 ("KCP Treasure Map Service")
identified this gap and asked for a practical enterprise bootstrap.

### §1.2 `llms.txt` as the Entry Point

[`llms.txt`](https://llmstxt.org/) is an emerging convention: a plain Markdown file at a domain's
root (or `/.well-known/llms.txt`) that tells AI agents what a site offers and where to find
machine-readable resources. It is governed by the community at `llmstxt.org` and has no formal
versioning scheme. KCP treats it as a convenient bootstrap bridge, not a foundation — the
stable KCP-native discovery path remains `/.well-known/kcp.json` (see §1.4).

**Machine-readable KCP entries use `> knowledge:` metadata lines** — the same convention
already defined in SPEC.md §1.2 for single-manifest discovery, extended here to support
multiple manifests. This is unambiguous and trivially parseable; Markdown section headings
are informational only.

```
# Acme Corp
> Enterprise knowledge network for AI agents. Acme builds industrial control systems.
> knowledge: /knowledge.yaml
> knowledge: /security/knowledge.yaml
> knowledge: /api/knowledge.yaml
```

The human-readable section below the metadata is optional but RECOMMENDED for readability:

```markdown
# Acme Corp
> Enterprise knowledge network for AI agents. Acme builds industrial control systems.
> knowledge: /knowledge.yaml
> knowledge: /security/knowledge.yaml
> knowledge: /api/knowledge.yaml

## KCP Knowledge Manifests
- [Engineering Hub](/knowledge.yaml): Core capability discovery — APIs, services, ADRs
- [Security & Compliance](/security/knowledge.yaml): GDPR, NIS2, internal security controls
- [API Gateway](/api/knowledge.yaml): External-facing service endpoints and rate limits
```

**Precedence:** When both `> knowledge:` metadata lines and Markdown bullet links are present,
implementations MUST use the `> knowledge:` lines as the authoritative manifest list. The
section heading is informational.

**Example — public sector agency:**

```
# Norwegian Labour and Welfare Administration (NAV)
> Public services for employment, benefits, and welfare in Norway.
> knowledge: https://developer.nav.no/knowledge.yaml
> knowledge: https://policy.nav.no/knowledge.yaml
```

Multiple manifests are first-class. There is no limit on the number of `> knowledge:` lines.
Each manifest is independently addressable — an agent can load one without loading the others.

### §1.3 Full Bootstrap Chain

The complete discovery chain from a domain name to a queryable knowledge graph:

```
domain name
    │
    ▼
llms.txt                         ← cold discovery; lists KCP manifests
    │
    ▼
knowledge.yaml                   ← local manifest; may declare manifests[]
    │
    ▼
/.well-known/kcp.json            ← topology hint; role: hub | leaf | standalone
    │
    ▼
federation graph (manifests[])   ← remote manifests loaded on demand
    │
    ▼
cross-manifest query             ← RFC-0007 + federation_scope (§4 this RFC)
```

An agent that resolves the full chain has a complete, queryable view of the knowledge network
with zero prior knowledge beyond the domain name.

### §1.4 Network Topology Hints in `/.well-known/kcp.json`

The `/.well-known/kcp.json` discovery document (defined in SPEC.md §1.4) gains an optional
`network` field that describes the topology of the KCP deployment at this domain:

```json
{
  "kcp_version": "0.11",
  "manifest": "/knowledge.yaml",
  "title": "Engineering Knowledge Hub",
  "description": "Architecture decisions, API reference, and compliance guides.",
  "spec": "https://github.com/Cantara/knowledge-context-protocol",
  "network": {
    "role": "hub",
    "entry_point": "/knowledge.yaml",
    "registry_label": "Acme Engineering Knowledge Network"
  }
}
```

**`network` sub-fields:**

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `role` | OPTIONAL | string | `hub` — this manifest federates others. `leaf` — this is a federated sub-manifest. `standalone` — no federation. Default: `standalone`. |
| `entry_point` | OPTIONAL | string | Root-relative path or absolute URL to the hub manifest. Meaningful when `role: leaf` — points the agent back to the hub. |
| `registry_label` | OPTIONAL | string | Human-readable name for the network as a whole. |

Note: a `manifest_count` field was considered and rejected — it drifts immediately as
sub-manifests are added or removed. Agents that need a manifest count SHOULD parse the
`knowledge.yaml` and count `manifests[]` entries directly.

The `role: leaf` + `entry_point` combination solves the reverse-discovery case: an agent that
lands on a sub-manifest can immediately see "I am a sub-manifest of a larger network; the hub
is at this URL."

**No manifest schema change required.** The `network` field is on the `/.well-known/kcp.json`
JSON document only. Existing consumers that do not understand it MUST ignore it.

### §1.5 `kcp init` Integration

The `kcp init` CLI (introduced in v0.10) SHOULD be extended to:

1. Generate a `/.well-known/kcp.json` with `network.role` set based on whether the manifest
   contains a `manifests[]` block (`hub` if present, `standalone` otherwise).
2. Print a suggested `llms.txt` snippet that the operator can paste into their existing
   `llms.txt` file (or create a new one if absent).

`kcp init` MUST NOT overwrite an existing `llms.txt` automatically. It SHOULD print the
suggested content to stdout so the operator can review it.

---

## §2 Capability Declarations on Units

### §2.1 The Pre-Invocation Capability Gap

KCP gives agents pre-invocation signals about *what* knowledge is available and *who* it is for.
It does not yet tell agents *what capabilities the consuming agent must have* to act on a unit.

A unit describing a Kubernetes deployment runbook is useless to an agent with no `kubectl`
access. A unit describing database migrations is useless to an agent that cannot run `flyway`.
An agent that loads these units wastes context budget and produces low-quality output.

The capability signal belongs pre-invocation — before the agent commits tokens to loading
content it cannot act on.

### §2.2 `requires_capabilities` on Units

Units MAY declare `requires_capabilities`: an optional list of opaque strings naming
capabilities the consuming agent should possess to act on the unit usefully.

```yaml
units:
  - id: deployment-runbook
    path: ops/deploy.md
    intent: "How do I deploy a new release to production?"
    scope: project
    audience: [operator, agent]
    kind: policy
    requires_capabilities: [kubectl, helm, aws-cli]

  - id: database-migration-guide
    path: db/migrations.md
    intent: "How do I run database migrations safely?"
    scope: module
    audience: [developer, agent]
    requires_capabilities: [psql, flyway]

  - id: architecture-overview
    path: docs/architecture.md
    intent: "What is the high-level system architecture?"
    scope: global
    audience: [human, agent, architect]
    # No requires_capabilities — any agent can read this usefully
```

**Recommended prefix convention.** To reduce synonym fragmentation across independently
published manifests, operators SHOULD use the following prefixes:

| Prefix | Meaning | Examples |
|--------|---------|---------|
| `tool:` | CLI tool or SDK the agent must have available | `tool:kubectl`, `tool:helm`, `tool:psql`, `tool:aws-cli` |
| `permission:` | Authorization scope or RBAC role | `permission:gdpr-data-read`, `permission:deploy-prod` |
| `role:` | Organisational or agent role | `role:operator`, `role:security-reviewer` |

Bare strings (without prefix) remain valid. Agents performing `has_capabilities` matching
SHOULD normalize by treating `kubectl` and `tool:kubectl` as equivalent.

**Semantics:**

- Values follow the prefix convention above; bare strings are also accepted.
  Parsers MUST NOT reject manifests for unknown or unprefixed values.
- An absent or empty `requires_capabilities` means no specific capability is required.
- Agents SHOULD evaluate `requires_capabilities` before loading a unit.
- Agents SHOULD surface capability gaps to their operator rather than silently loading
  content they cannot act on.
- This field is advisory, consistent with KCP's declarative philosophy (SPEC.md §14.1).
  Parsers MUST NOT reject a manifest for unknown capability values.
- v0.10 parsers MUST silently ignore this field per forward-compatibility rules (SPEC.md §2).

### §2.3 `has_capabilities` Query Filter *(v0.12)*

> **Deferred to v0.12.** The `has_capabilities` query filter requires the RFC-0007 structured
> query baseline to be implemented in all three bridges first. The field definition is
> specified here for completeness; implementations MUST NOT ship it before the v0.12 wave.

The RFC-0007 query request object gains an optional `has_capabilities` field:

```yaml
terms: ["deployment"]
audience: agent
has_capabilities: [tool:kubectl, tool:helm]
```

When `has_capabilities` is set, the scorer SHOULD exclude units whose `requires_capabilities`
contains values not present in the `has_capabilities` list (after prefix normalization). An
agent declares its own capability set and receives only the units it can act on.

Units with no `requires_capabilities` are always included regardless of `has_capabilities`.

Implementations that do not support `has_capabilities` MUST ignore it and return all matching
units as if the filter were absent.

---

## §3 Freshness Policy

### §3.1 The Staleness Signal Gap

`validated` (a date stamp, RECOMMENDED per SPEC.md §4.5) tells an agent when a unit was last
confirmed accurate. `update_frequency` (OPTIONAL) tells an agent how often it changes.
Together they answer *"when was this confirmed?"* and *"how often does it change?"*

What they do not answer: *"when should I treat this as stale and what should I do then?"*

An agent acting on a `validated: 2025-06-01` compliance policy today has no manifest-level
signal that the policy author considers it stale after 90 days. The agent applies its own
heuristics or ignores freshness entirely. In regulated domains (GDPR, NIS2, financial
compliance) this is a meaningful risk — agents should not silently treat outdated policy as
authoritative.

### §3.2 `freshness_policy` Block

Units MAY declare a `freshness_policy` block. The root manifest MAY also declare a
`freshness_policy` that serves as the default for all units (overridable per unit):

```yaml
kcp_version: "0.11"
project: compliance-knowledge-base
freshness_policy:
  max_age_days: 180
  on_stale: warn
  review_contact: "knowledge@example.com"

units:
  - id: gdpr-compliance-guide
    path: compliance/gdpr.md
    intent: "What GDPR obligations apply to our data processing?"
    validated: "2026-01-15"
    update_frequency: monthly
    # Inherits root freshness_policy (180 days, warn)

  - id: security-incident-playbook
    path: security/incident-response.md
    intent: "How do I respond to a security incident?"
    validated: "2026-02-01"
    update_frequency: quarterly
    freshness_policy:
      max_age_days: 30
      on_stale: block
      review_contact: "security@example.com"

  - id: api-reference
    path: api/reference.md
    intent: "What endpoints does the API expose?"
    validated: "2026-03-01"
    update_frequency: weekly
    freshness_policy:
      max_age_days: 14
      on_stale: degrade
```

**`freshness_policy` sub-fields:**

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `max_age_days` | OPTIONAL | integer | Days since `validated` after which the unit is considered stale. Agents compute: `stale = (today − validated) > max_age_days`. |
| `on_stale` | OPTIONAL | string | Advisory action when stale. `warn` — proceed but surface a warning to the operator. `degrade` — agent MUST indicate output may be based on outdated information. `block` — agent SHOULD NOT act on this unit without fresh validation. Default: `warn`. |
| `review_contact` | OPTIONAL | string | Email address or URL for requesting re-validation. Agents SHOULD surface this to the operator when staleness triggers. |

**Staleness computation:** `stale = (today − validated_date) > max_age_days`. If `validated`
is absent, the unit has unknown freshness — agents MAY skip freshness evaluation and MUST NOT
treat absence of `validated` as stale.

**Override precedence:** Unit-level `freshness_policy` fully overrides the root default. There
is no field-level merge — if a unit declares `freshness_policy`, all sub-fields come from the
unit declaration only.

> **WARNING:** A partial unit-level `freshness_policy` replaces the root default entirely.
> Writing `freshness_policy: {max_age_days: 30}` on a unit does NOT inherit `on_stale` or
> `review_contact` from the root. Re-declare all sub-fields when tightening a specific unit.

**Safety-critical contexts.** Agents operating in incident response or safety-critical
workflows SHOULD treat `on_stale: block` as `on_stale: degrade` when no alternative
non-stale unit is available for the same intent. The purpose of `block` is to prevent agents
from silently trusting outdated information — not to make knowledge inaccessible during a
crisis. Agents SHOULD surface the `review_contact` and staleness details to their operator
in all cases where `on_stale: block` would otherwise halt action.

### §3.3 `exclude_stale` Query Filter *(v0.12)*

> **Deferred to v0.12.** Ships with the full RFC-0007 query baseline.

The RFC-0007 query request object gains an optional `exclude_stale` boolean:

```yaml
terms: ["deployment"]
audience: agent
exclude_stale: true
```

When `exclude_stale: true`, the scorer SHOULD exclude units that compute as stale (i.e., units
where `validated` is present, `freshness_policy.max_age_days` is present, and
`(today − validated) > max_age_days`). Units without `validated` or without
`freshness_policy.max_age_days` are not excluded.

Implementations that do not support `exclude_stale` MUST ignore it.

---

## §4 Cross-Manifest Query

### §4.1 The Federated Query Gap

RFC-0007 defined the query vocabulary for local manifests and explicitly deferred cross-manifest
queries:

> *"Cross-manifest queries (querying units from `manifests[]` entries) require fetching and
> parsing the remote manifest first. This RFC does not define cross-manifest query semantics."*

With v0.9 federation infrastructure in place (manifest fetching, resolution, caching) and
RFC-0007 local scoring in all three bridges, the gap is now only the query protocol extension.

### §4.2 `federation_scope` Query Parameter (RFC-0007 Extension)

The RFC-0007 query request object gains an optional `federation_scope` field:

```yaml
terms: ["authentication", "oauth2"]
audience: agent
max_token_budget: 8000
federation_scope: declared
```

| Value | Meaning | Version |
|-------|---------|---------|
| `local` | Query only the local manifest. **Default.** | v0.12 |
| `declared` | Query the local manifest and all manifests in its `manifests[]` block (one hop). | v0.12 |
| `recursive` | Query the local manifest and all transitively declared manifests up to the federation fetch limit (50 manifests). | **v0.13 — deferred.** Performance and amplification characteristics require empirical validation against real federation graphs before standardising. |

Implementations that do not support `federation_scope` MUST ignore it and behave as if `local`
was specified.

### §4.3 `source_manifest` in Query Response (RFC-0007 Extension)

The RFC-0007 result object gains one new field:

```yaml
results:
  - unit_id: auth-guide
    score: 13
    path: docs/api/authentication.md
    token_estimate: 4200
    summary_unit: auth-guide-tldr
    match_reason: [trigger, intent]
    source_manifest: null          # null = local manifest

  - unit_id: sso-integration-guide
    score: 11
    path: docs/sso.md
    token_estimate: 3100
    match_reason: [intent]
    source_manifest: identity-service   # manifests[].id in the hub manifest
```

When `source_manifest` is non-null, the agent knows the unit lives in a federated sub-manifest.
The agent MUST resolve the unit path relative to that sub-manifest's base URL, not the local
manifest's base URL.

`source_manifest: null` for local results is backward compatible — existing consumers that do
not understand the field will ignore it.

### §4.4 Interaction with the Bootstrap Chain

Cross-manifest query completes the bootstrap chain introduced in §1.3. An agent that has:

1. Discovered a hub via `llms.txt`
2. Loaded the hub `knowledge.yaml`
3. Read the federation graph from `manifests[]`

...can issue a query with `federation_scope: declared` and receive scored, ranked results
from the hub and all declared sub-manifests — with each result annotated with which
sub-manifest it came from. Recursive traversal across deeper federation graphs ships in v0.13
once performance characteristics are validated.

---

## §5 Backward Compatibility

| Addition | v0.10 parser behaviour | Risk |
|----------|----------------------|------|
| `freshness_policy` block on root/unit | Silently ignored per SPEC.md §2 | None |
| `requires_capabilities` list on unit | Silently ignored per SPEC.md §2 | None |
| `network` in `/.well-known/kcp.json` | Unknown field ignored by consumers | None |
| `federation_scope` query param | Ignored; treated as `local` | None |
| `has_capabilities` query filter | Ignored; no units excluded | None |
| `exclude_stale` query filter | Ignored; no units excluded | None |
| `source_manifest` response field | Additional field ignored by consumers | None |
| `kcp_version: "0.11"` | "Unknown version" warning per §6.1 | Warning only |

---

## §6 Schema Changes (Diff Against v0.10)

### Root manifest additions

```yaml
kcp_version: "0.11"          # enum gains "0.11"
# ... all existing v0.10 fields unchanged ...

freshness_policy:             # OPTIONAL; default for all units
  max_age_days: 180           # integer
  on_stale: warn              # enum: warn | degrade | block
  review_contact: "..."       # string; email or URL
```

### Unit additions

```yaml
units:
  - id: example
    # ... all existing v0.10 fields unchanged ...
    requires_capabilities: [kubectl, helm]   # OPTIONAL; list of strings
    freshness_policy:                        # OPTIONAL; overrides root default
      max_age_days: 30
      on_stale: block
      review_contact: "..."
```

### `/.well-known/kcp.json` additions *(v0.11)*

```json
{
  "network": {
    "role": "hub | leaf | standalone",
    "entry_point": "/knowledge.yaml",
    "registry_label": "..."
  }
}
```

### Query extensions *(v0.12 — deferred; no manifest schema change)*

```yaml
# Query request
federation_scope: local | declared   # recursive deferred to v0.13
has_capabilities: [tool:kubectl, permission:deploy-prod]
exclude_stale: true | false

# Query response
results:
  - # ... existing fields ...
    source_manifest: null | "<manifests[].id>"
```

---

## §7 Conformance Fixtures

The following fixtures MUST be added under `conformance/fixtures/`:

| File | Level | What it tests |
|------|-------|---------------|
| `level2/valid-with-freshness-policy.yaml` | 2 | Unit with `freshness_policy`; no root default |
| `level2/valid-with-requires-capabilities.yaml` | 2 | Unit with `requires_capabilities`; varied values |
| `level3/valid-with-freshness-policy-root-default.yaml` | 3 | Root default + unit override |
| `level3/valid-with-cross-manifest-query-declared.yaml` | 3 | `manifests[]` + declared scope query example |

---

## Open Questions

1. **`requires_capabilities` vocabulary normalization** — the `tool:` / `permission:` /
   `role:` prefix convention reduces fragmentation but does not eliminate it. A follow-on RFC
   should define a recommended vocabulary once adoption data exists. Community input welcome.

2. **Full peer-to-peer federation** — `llms.txt` bootstrap solves cold discovery without a
   central registry. True peer-to-peer (manifest A references manifest B without either being
   a declared hub) requires resolving the trust propagation model. Deferred to v0.12.

3. **`recursive` scope** — deferred to v0.13. Performance and SSRF amplification
   characteristics (up to 50 outbound HTTPS fetches per query) need empirical validation
   against real federated graphs before standardising. `federation_scope: declared` (one hop)
   covers the vast majority of real-world hub+leaf topologies and ships in v0.12.

4. **Trust-aware scoring in federated results** — RFC-0007 scoring treats all units equally
   regardless of which manifest they come from. Units from `relationship: archive`
   sub-manifests can outscore local current units. A v0.12 follow-on SHOULD add relationship-
   aware scoring modifiers (e.g. halve the score for `archive`, +3 for `governs`).
