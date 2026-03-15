# RFC-0011: Org Federation — Enterprise Discovery and Progressive Access

**Status:** Request for Comments
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-03-15
**Discussion:** [#50 KCP Treasure Map Service](https://github.com/Cantara/knowledge-context-protocol/discussions/50)
**Related:** [RFC-0003 Federation](./RFC-0003-Federation.md) · [RFC-0009 Visibility and Authority](./RFC-0009-Visibility-and-Authority.md)
**Spec:** [SPEC.md](./SPEC.md) (current: v0.11)

---

## What This RFC Proposes

Four patterns that together answer the enterprise discovery problem Stig Lau named in Discussion #50:
**"How does an agent find its first manifest, get through the door, and then progressively learn what else exists in a large organisation?"**

1. **The Org Hub pattern** — a root manifest that serves as an enterprise knowledge landing page, with explicit `network.role: hub` topology and a `/.well-known/kcp.json` discovery endpoint.

2. **Progressive disclosure** — a formalised 3-tier access pattern (unauthenticated → authenticated → fully authorised) using existing `compliance.sensitivity` + RFC-0009 `visibility` + the v0.11 `network` block.

3. **Environment-aware manifest references** — a `context` field on `manifests[]` entries declaring which runtime environment each sub-manifest is valid for (`dev`, `test`, `staging`, `prod`), enabling agents to select the right manifest without fetching all of them.

4. **Agent identity hint** — a lightweight `agent_identity` field on `manifests[]` entries that declares what credential type the target manifest requires before it will expose restricted units. Not an auth protocol — a declaration layer so agents can plan before attempting access.

All additions are optional. Zero breaking changes. A solo developer's five-line `knowledge.yaml` does not need to change.

---

## The Problem

### The enterprise bootstrap is undefined

RFC-0003 (v0.9) solved cross-manifest federation: a manifest can declare `manifests[]` entries pointing to other manifests, with typed relationships (`foundation`, `peer`, `child`, `governs`, `archive`). An agent that already holds a manifest can traverse the graph.

What is undefined is the **first step**: a new agent, given only a company domain name, has no specified path to the first manifest. The v0.11 `/.well-known/kcp.json` discovery endpoint and `llms.txt` bootstrap (RFC-0008) address this partially — they tell an agent where to find the root manifest — but they say nothing about what the agent should expect to find there or how to behave if it arrives unauthenticated.

### The visibility problem at scale

A large enterprise cannot publish a single flat manifest listing all services. Some units (`internal` sensitivity) should only be visible to authenticated developers. Others (`confidential`) require specific team membership or compliance certification. The current spec has `compliance.sensitivity` values but no mechanism for the manifest itself to guide an unauthenticated agent through a progressive disclosure path.

### The environment collapse problem

In real enterprise deployments, the same service has different manifests for `dev`, `test`, and `prod`. Today a manifest author has two choices: (a) publish separate manifests per environment and manage three URLs, or (b) use RFC-0009 `visibility.conditions[]` to inline environment-conditional logic. Both work, but neither tells a remote agent, before it fetches anything, which environment a sub-manifest is valid for.

### The auth planning problem

An agent traversing a federation graph may encounter a sub-manifest that requires OAuth 2.1 with a specific issuer, a GitHub Personal Access Token, or a Confluence PAT. The current `auth.methods[]` block (§3.3) declares what auth the manifest requires once you have loaded it. There is no pre-fetch signal that tells a traversing agent "before you try to load this sub-manifest, get a GitHub PAT from the user."

---

## Proposed Additions

### 1. Org Hub Pattern

A root manifest serving as an enterprise knowledge landing page SHOULD:

- Declare `network.role: hub` in `/.well-known/kcp.json` (v0.11 §3.7).
- Include a root unit with `hints.load_strategy: eager` and `audience: [agent]` — the front door unit an unauthenticated agent loads first.
- Declare `compliance.sensitivity: public` on that front door unit — so agents know this unit is safe to load before authentication.
- Include a `manifests[]` block listing known sub-manifests with `relationship: child` or `relationship: foundation` as appropriate.

**Example:**

```yaml
kcp_version: "0.11"
project: companyX-knowledge-hub
version: 1.0.0
updated: "2026-03-15"
language: en

network:
  role: hub
  entry_point: "https://kcp.companyX.no/knowledge.yaml"
  registry_label: "CompanyX Knowledge Network"

units:
  - id: front-door
    path: README.md
    intent: "What is CompanyX, what services exist, and how does an agent get started?"
    scope: global
    audience: [agent, human, developer]
    hints:
      load_strategy: eager
      token_estimate: 800
    compliance:
      sensitivity: public
    requires_capabilities:
      - "tool:web_fetch"

  - id: auth-guide
    path: guides/agent-authentication.md
    intent: "How does an agent authenticate on behalf of a developer? PAT flows for GitHub and Confluence."
    scope: global
    audience: [agent, developer]
    compliance:
      sensitivity: public

manifests:
  - id: platform-engineering
    url: "https://git.companyX.no/platform/knowledge.yaml"
    label: "Platform Engineering Hub"
    relationship: foundation
    context: ["prod"]
    agent_identity:
      required: true
      credential_hint: "github_pat"
      docs_url: "https://kcp.companyX.no/guides/agent-authentication.md"

  - id: data-warehouse
    url: "https://git.companyX.no/data/knowledge.yaml"
    label: "Data Warehouse"
    relationship: peer
    context: ["prod"]
    agent_identity:
      required: true
      credential_hint: "oauth2"
      issuer_hint: "https://auth.companyX.no"

  - id: platform-engineering-dev
    url: "https://git.companyX.no/platform/knowledge-dev.yaml"
    label: "Platform Engineering — Dev"
    relationship: peer
    context: ["dev"]
    agent_identity:
      required: false
```

---

### 2. Progressive Disclosure Pattern

A KCP manifest at an org hub MAY use three sensitivity tiers to implement progressive disclosure:

| Tier | `compliance.sensitivity` | What it contains | Auth required |
|------|--------------------------|-----------------|---------------|
| T0 — Public | `public` | Service catalogue overview, auth guide, how to get started | None |
| T1 — Internal | `internal` | API surface, team structure, non-sensitive documentation | Authenticated developer |
| T2 — Confidential | `confidential` | Production data contracts, incident response, compliance mappings | Role-specific approval |

An unauthenticated agent loading the hub manifest SHOULD:
1. Load all `sensitivity: public` units first (`load_strategy: eager`).
2. Present the developer with the auth guide and pause for credential acquisition.
3. After authentication, reload the manifest — or request a higher-sensitivity view via `search_knowledge` with `sensitivity_max: internal`.

This pattern requires no new spec fields. It is a **usage convention** layered on existing `compliance.sensitivity` (§3.5), the RFC-0009 `visibility.conditions[]` block (for environment-conditional logic), and the RFC-0007 `search_knowledge` `sensitivity_max` filter (§3.7, RFC-0007).

---

### 3. Environment-Aware Manifest References (new field: `context`)

**Proposed addition to `manifests[]` entries (§3.6):**

```
context: [string]   OPTIONAL
```

A list of environment labels for which this sub-manifest reference is valid. If absent, the manifest is valid in all environments. Agents SHOULD select only the sub-manifest entries whose `context` list matches their current runtime environment.

**Vocabulary (non-normative):** `dev`, `test`, `staging`, `prod`. Implementations MAY extend this vocabulary.

**Example:**

```yaml
manifests:
  - id: payments-prod
    url: "https://git.companyX.no/payments/knowledge.yaml"
    relationship: child
    context: ["prod"]

  - id: payments-dev
    url: "https://git.companyX.no/payments/knowledge-dev.yaml"
    relationship: child
    context: ["dev", "test"]
```

An agent running in a `prod` context fetches `payments-prod`. An agent running in `dev` fetches `payments-dev`. No fetching both and reconciling.

---

### 4. Agent Identity Hint (new field: `agent_identity`)

**Proposed addition to `manifests[]` entries (§3.6):**

```
agent_identity:
  required: bool              OPTIONAL (default: false)
  credential_hint: string     OPTIONAL  — advisory: github_pat | oauth2 | confluence_pat | api_key | none
  issuer_hint: string         OPTIONAL  — for oauth2: the issuer URL to use
  docs_url: string            OPTIONAL  — where the developer finds instructions
```

`agent_identity` is a **declaration layer**, not an auth protocol. It tells a traversing agent what credential it will need before it attempts to load the sub-manifest. The agent can then:
1. Check whether it already holds a matching credential.
2. If not, present the developer with `docs_url` and pause for credential acquisition before fetching.
3. If `required: false`, attempt the fetch anyway — the sub-manifest's own `auth.methods[]` block handles auth enforcement.

**This is complementary to, not a replacement for, IndyKite's AgentControl runtime enforcement** (as noted by Lasse Andresen in the KCP community thread). `agent_identity` is the static pre-fetch declaration. AgentControl (or any equivalent runtime system) is the enforcement layer that validates the credential once the agent presents it.

---

## What This RFC Does NOT Cover

- Auth protocol implementation (credential acquisition, token exchange, PAT flows). Those belong in the application layer and are referenced via `docs_url`.
- Agent certificate issuance or PKI. Those are runtime enforcement concerns outside the spec's scope.
- Peer-to-peer cross-referencing without a hub. The DAG topology constraint from RFC-0003 §14.3 remains.
- Versioning of the network topology over time. Deferred.

---

## Relationship to Other RFCs

| RFC | Relationship |
|-----|-------------|
| RFC-0003 (Federation, v0.9 core) | This RFC builds on top of `manifests[]`. The `context` and `agent_identity` fields are extensions to the existing `manifests[]` entry schema. |
| RFC-0008 (Agent Readiness, v0.11) | `network.role: hub` in `/.well-known/kcp.json` is the discovery endpoint this RFC's hub pattern uses. `llms.txt` is the cold-discovery bootstrap. |
| RFC-0009 (Visibility and Authority) | The T1/T2 progressive disclosure tiers work naturally with RFC-0009's `visibility.conditions[]` for environment-conditional exposure of units. RFC-0011 is the topology and discovery layer; RFC-0009 is the access control layer. Together they solve Stig's full scenario. |

---

## Open Questions for Community Input

1. **`context` vocabulary**: Should the environment labels be normative (an enumerated set) or non-normative (free strings)? The `dev/test/staging/prod` set covers most cases but may not cover all.

2. **`credential_hint` vocabulary**: Is `github_pat | oauth2 | confluence_pat | api_key | none` a useful starting set, or should this be a free string from the start?

3. **Org Hub as a Level 4 conformance tier?** The current spec has three conformance levels (§8). Should an org-level hub manifest — with `network.role: hub`, a public front-door unit, and at least one `manifests[]` entry — constitute a Level 4?

4. **CompanyX scenarios**: The design above was driven by abstract requirements. Concrete scenarios from a real enterprise adoption (unauthenticated discovery → first auth → progressive disclosure → environment selection) would significantly sharpen the spec.

---

## Appendix A: Invitation for CompanyX Scenarios

This RFC was directly shaped by Discussion #50 ("KCP Treasure Map Service"). If you are implementing KCP in an enterprise context and would be willing to write up concrete scenarios covering:

- What an unauthenticated agent sees at the front door
- How the agent guides the developer through the first authentication step
- How environment selection (dev/test/prod) works in practice
- What a "data sensitivity contract" looks like for a service that cannot be touched by external LLMs

...please add them to Discussion #50 or open a PR adding an `examples/org-federation/` directory. These scenarios will be incorporated into this RFC as normative examples before it is promoted to the core spec.

---

## Status

**Request for Comments.** This RFC will be promoted to the core spec (target: v0.12) after:

1. At least one external implementer validates the `context` and `agent_identity` field designs against a real enterprise deployment.
2. The progressive disclosure pattern is demonstrated in a reference `examples/org-federation/` example.
3. RFC-0009 `visibility.conditions[]` is validated in at least one bridge — confirming the two RFCs compose correctly.
