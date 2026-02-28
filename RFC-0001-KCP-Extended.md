# RFC-0001: Knowledge Context Protocol — Extended Capabilities

**Status:** Request for Comments
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-02-26
**Discussion:** [GitHub Discussions](https://github.com/Cantara/knowledge-context-protocol/discussions)
**Spec:** [SPEC.md](./SPEC.md)

---

## What Is This Document?

This is a **Request for Comments** — an open invitation for the Cantara community to shape the direction of KCP before we commit to spec changes.

We have been running KCP against the emerging agentic infrastructure landscape and found 14 areas where the spec could be extended. We are not proposing to add all of these at once. We are asking: **which of these matter to you, and how should they work?**

Read what interests you. Skip what does not. Comment on what you disagree with. Propose alternatives. That is the process.

---

## Quick Recap: What KCP Is

KCP is a `knowledge.yaml` file you drop at the root of a project. It tells AI agents what knowledge exists, what each piece answers, how it depends on other pieces, and how fresh it is.

```yaml
project: my-project
units:
  - id: overview
    path: README.md
    intent: "What is this project and how do I get started?"
    audience: [human, agent]

  - id: architecture
    path: docs/architecture.md
    intent: "How is the system structured?"
    depends_on: [overview]
    triggers: [architecture, components, design]
```

That is the whole idea. Three fields minimum, five minutes to add, immediately useful.

**KCP is to knowledge what MCP is to tools.**

---

## Why We Are Extending It

As we deployed KCP against real production scenarios — and as the agentic infrastructure ecosystem evolved rapidly through 2025–2026 — we hit limits that the current v0.1 spec cannot address:

- An agent discovers a knowledge unit but cannot tell if it requires authentication
- An agent loads a unit but does not know it is GDPR-regulated and cannot leave the EU
- An agent caches a unit indefinitely because there is no TTL signal
- A knowledge base in three languages has no way to declare which version is authoritative
- A manifest covers 8,934 files but has no way to link to units in another repository

These are not edge cases. They are the normal conditions of production enterprise knowledge bases.

We also had a sharp observation from the community (h/t @StigLau):

> *"Where is the boundary between a knowledge source (KCP) and an actual app description? Because I immediately think I want to use it to describe apps, services, etc."*

That question opens a design space we need to discuss openly.

---

## The Proposals

We have organised the proposals into six themes. Each links to a detailed GitHub Issue with YAML examples and open questions.

---

### Theme 1: Access and Identity

**The problem:** Agents discover a knowledge unit but have no signal about whether it requires credentials, what kind, or how deep a delegation chain is permitted.

#### Proposal A — Auth metadata ([Issue #1](https://github.com/Cantara/knowledge-context-protocol/issues/1))

> **Superseded by [RFC-0002](./RFC-0002-Auth-and-Delegation.md).** The full proposal is now maintained there. See RFC-0002 for field definitions, examples, and open questions.

A per-unit `access` field and a root-level `auth` block describing how to authenticate to the knowledge source.

```yaml
auth:
  methods:
    - type: oauth2
      token_endpoint: "https://auth.example.com/token"
      scopes: ["read:knowledge"]
    - type: api_key
      header: "X-API-Key"

units:
  - id: public-overview
    path: README.md
    intent: "What is this project?"
    # access defaults to public

  - id: internal-runbook
    path: ops/runbook.md
    intent: "How do I handle a production incident?"
    access: restricted
    auth_scope: ops-team
```

Supported auth types: `none`, `oauth2`, `api_key`, `bearer_token`, `spiffe`, `did`, `http_signature`.

#### Proposal B — Delegation constraints ([Issue #3](https://github.com/Cantara/knowledge-context-protocol/issues/3))

> **Superseded by [RFC-0002](./RFC-0002-Auth-and-Delegation.md).** The full proposal is now maintained there alongside the auth metadata proposal.

Agents operate in chains: human → orchestrator → sub-agent → tool. How many hops away from a human may this knowledge be accessed?

```yaml
delegation:
  max_depth: 2                    # at most 2 hops from a human decision
  require_capability_attenuation: true
  human_in_the_loop:
    required: true
    approval_mechanism: oauth_consent
```

**Question for the community:** Is `max_depth` the right mental model? Or should this be expressed as "requires human approval" vs "autonomous access permitted"?

---

### Theme 2: Economics

**The problem:** No discovery standard describes what a knowledge resource costs to access or how much of it an agent can consume.

#### Proposal C — Payment metadata ([Issue #2](https://github.com/Cantara/knowledge-context-protocol/issues/2))

> **Superseded by [RFC-0005](./RFC-0005-Payment-and-Rate-Limits.md)** — The `payment` block (methods, x402, subscription, meter, unit-level overrides) is defined there.

The emerging agentic payments ecosystem (x402, Stripe SPTs, Google AP2) enables agents to pay autonomously for resources. KCP should describe payment requirements so agents can plan before attempting access.

```yaml
payment:
  methods:
    - type: free
    - type: x402                  # HTTP 402, stablecoin micropayments
      currency: "USDC"
      price_per_request: "0.001"
    - type: subscription
      plans_url: "https://example.com/pricing"
      free_tier: true
      free_requests_per_day: 100
```

#### Proposal D — Rate limit metadata ([Issue #4](https://github.com/Cantara/knowledge-context-protocol/issues/4))

> **Superseded by [RFC-0005](./RFC-0005-Payment-and-Rate-Limits.md)** — The `rate_limits` block (per-tier limits, token limits, header mapping, backoff) is defined there.

No standard exists for communicating rate limits to agents *before* they attempt access. Today agents discover limits reactively (they hit a 429). KCP can make this proactive.

```yaml
rate_limits:
  anonymous:
    requests_per_minute: 10
  authenticated:
    requests_per_minute: 100
  headers:
    remaining: "X-RateLimit-Remaining"
    reset: "X-RateLimit-Reset"
```

**Open concern (raised in [Issue #4 comments](https://github.com/Cantara/knowledge-context-protocol/issues/4)):** Root-level rate limits break down when a manifest mixes free public units and paid premium units. Unit-level overrides are needed. How should this be designed?

---

### Theme 3: Trust and Governance

**The problem:** Agents have no machine-readable signal for who published a knowledge unit, whether it has been tampered with, whether they are permitted to use it for training, or whether it is regulated data that cannot leave a jurisdiction.

#### Proposal E — Trust and audit metadata ([Issue #5](https://github.com/Cantara/knowledge-context-protocol/issues/5))

> **Superseded by [RFC-0004](./RFC-0004-Trust-and-Compliance.md)** — The `trust` block (provenance, content_integrity, audit, agent_requirements) is defined there.

```yaml
trust:
  provenance:
    publisher: "eXOReaction AS"
    publisher_did: "did:web:exoreaction.com"
  content_signing:
    enabled: true
    method: jws
  audit:
    require_trace_context: true   # agent must include W3C Trace Context headers
```

#### Proposal F — License and usage rights ([Issue #8](https://github.com/Cantara/knowledge-context-protocol/issues/8))

What may an agent do with a knowledge unit once it has loaded it? Can it reproduce content in a response? Use it for training? Pass it to a sub-agent?

```yaml
units:
  - id: case-study
    path: articles/case-study.md
    intent: "What did the lib-pcb project achieve?"
    license:
      spdx: "CC-BY-4.0"
      attribution_required: true
      restrictions: [train]       # freely readable, but not for training

  - id: proprietary-spec
    path: internal/spec.md
    intent: "What is our internal architecture specification?"
    license:
      spdx: "LicenseRef-Proprietary"
      permissions: [read]
      restrictions: [reproduce, redistribute, train, share-externally]
```

#### Proposal G — Data residency and compliance ([Issue #11](https://github.com/Cantara/knowledge-context-protocol/issues/11))

> **Superseded by [RFC-0004](./RFC-0004-Trust-and-Compliance.md)** — The `compliance` block (data_residency, regulations, sensitivity, restrictions) is defined there.

For enterprise and regulated industries: where may this knowledge be processed?

```yaml
units:
  - id: customer-data-schema
    path: data/schema.md
    intent: "How is customer data structured?"
    compliance:
      data_residency:
        regions: [EEA]
        hard_requirement: true    # agents MUST NOT process outside EEA
      regulations: [GDPR]
      sensitivity: confidential
      restrictions:
        - no-external-llm         # must not be sent to external AI APIs
        - audit-required
```

**Why this matters:** Without compliance metadata, regulated industries (finance, healthcare, public sector) cannot safely adopt KCP. This is a first-class enterprise requirement, not an optional extra.

#### Proposal H — AI crawling permissions ([Issue #13](https://github.com/Cantara/knowledge-context-protocol/issues/13))

More precise than `robots.txt` — at the knowledge unit level, for specific operation types.

```yaml
units:
  - id: public-overview
    path: README.md
    indexing: open                # shorthand: all operations permitted

  - id: proprietary-methodology
    path: methodology/internal.md
    indexing:
      allow: [read]
      deny: [index, reproduce-in-response, train]
```

---

### Theme 4: Enriched Navigation

**The problem:** Agents need richer signals to navigate large manifests efficiently — what format is the content, how expensive is it to load, when should it be re-fetched, and which language is it in?

#### Proposal I — Content format ([Issue #7](https://github.com/Cantara/knowledge-context-protocol/issues/7))

KCP currently assumes all content is Markdown. It is not.

```yaml
units:
  - id: api-reference
    path: reference/openapi.yaml
    intent: "What are the API endpoints and schemas?"
    format: openapi

  - id: architecture-diagram
    path: docs/architecture.pdf
    intent: "What is the high-level system architecture?"
    format: pdf
    audience: [architect, human]
```

#### Proposal J — Context window hints ([Issue #9](https://github.com/Cantara/knowledge-context-protocol/issues/9))

> **Superseded by [RFC-0006](./RFC-0006-Context-Window-Hints.md)** — The `hints` block (token estimates, load strategy, priority, density, summary relationships, chunking) is defined there.

Agents with limited context budgets need cost estimates before loading.

```yaml
units:
  - id: full-specification
    path: SPEC.md
    intent: "What is the complete technical specification?"
    hints:
      token_estimate: 42000
      load_time: medium
      summary_available: true

  - id: spec-summary
    path: SPEC-tldr.md
    intent: "What are the key points of the spec in 500 words?"
    hints:
      token_estimate: 600
      summary_of: full-specification   # this is a summary of that unit
```

#### Proposal K — Caching and update frequency ([Issue #10](https://github.com/Cantara/knowledge-context-protocol/issues/10))

When should an agent re-fetch? `validated` tells you when a human last confirmed accuracy. `cache` tells agents how long to keep a unit before checking again.

```yaml
units:
  - id: changelog
    path: CHANGELOG.md
    intent: "What changed in recent releases?"
    cache:
      ttl: 3600                   # re-fetch every hour
      update_frequency: daily

  - id: architecture-overview
    path: docs/architecture.md
    intent: "What is the high-level architecture?"
    cache:
      ttl: 604800                 # re-fetch weekly
      update_frequency: monthly
```

#### Proposal L — Multilingual and localisation ([Issue #14](https://github.com/Cantara/knowledge-context-protocol/issues/14))

```yaml
units:
  - id: methodology-en
    path: methodology/overview-en.md
    intent: "What development methodology is used?"
    language: en
    locale:
      canonical: true

  - id: methodology-no
    path: methodology/overview-no.md
    intent: "Hvilken utviklingsmetodologi brukes?"
    language: no
    locale:
      translated_from: methodology-en
      translation_validated: 2026-01-10
```

---

### Theme 5: Scale

**The problem:** Enterprise knowledge spans multiple repositories and teams. A single `knowledge.yaml` per repo cannot express cross-repo dependencies.

#### Proposal M — Cross-manifest federation ([Issue #12](https://github.com/Cantara/knowledge-context-protocol/issues/12))

> **Superseded by [RFC-0003](./RFC-0003-Federation.md).** The full proposal — hub-and-spoke `manifests` block, `external_depends_on`, `external_relationships`, cycle detection, and security constraints — is now maintained there.

The current spec explicitly excludes cross-manifest relationships (§1.3). That was the right decision for v0.1 minimalism. But it limits enterprise adoption.

**Interim approach (works today, no spec change):**

```yaml
units:
  - id: platform-deployment-guide
    path: infra/deployment.md
    intent: "How do I deploy a new service to our platform?"
    x-external-ref:
      - manifest: "https://platform.example.com/knowledge.yaml"
        unit_id: platform-security-requirements
        relationship: depends_on
```

**v0.2 proposal — hub-and-spoke federation:**

```yaml
kcp_version: "0.2"
project: enterprise-knowledge-base
manifests:
  - id: platform
    url: "https://platform.example.com/knowledge.yaml"
    relationship: child
  - id: security
    url: "https://security.example.com/knowledge.yaml"
    relationship: child
```

**Question for the community:** Is the `x-external-ref` interim convention good enough for v0.1? Or is this urgent enough to fast-track into the spec?

---

### Theme 6: Beyond Knowledge

**The problem (raised by @StigLau):** The boundary between "a knowledge unit describing a service" and "a service catalog entry" is blurry. Stig's instinct — "I want to use this for apps and services too" — reveals a real gap in the ecosystem.

#### The boundary as we see it

```
KCP covers this ──────────────────── KCP does not cover this
     │                     │                      │
  README.md           API docs               Running API
  (knowledge)    (knowledge about           (the service
                  a service) ✓               itself) ✗
```

Knowledge *about* a service is still knowledge. KCP can describe it. The service's runtime interface (endpoints, schemas, invocation) belongs to OpenAPI, A2A Agent Cards, or MCP.

#### Proposal N — Service description extension ([Issue #7, service boundary](https://github.com/Cantara/knowledge-context-protocol/issues/7))

Using the existing `x-` extension mechanism, a conventional pattern for enriching knowledge units with service metadata:

```yaml
units:
  - id: payment-service
    path: services/payment/README.md
    intent: "What does the payment service do and how do I interact with it?"
    triggers: [payment, billing, stripe]
    x-service-endpoint: https://api.example.com/v2/payments
    x-service-lifecycle: production
    x-service-owner: team-billing
    x-service-api-spec: services/payment/openapi.yaml
```

This works **today** with no spec change. Agents that do not understand `x-service-*` fields ignore them gracefully (§11 of the spec already requires this).

**Longer term:** If adoption shows demand, a formal `kind` field (`knowledge` | `service` | `api`) could make this first-class in v0.2 or v0.3.

---

## The Ecosystem Picture

To ground these proposals: here is where KCP sits in the agentic infrastructure stack as of February 2026.

```
Layer 5: PAYMENTS      x402 · Stripe/OpenAI ACP · Google AP2 · Visa TAP
Layer 4: AUTHORIZATION OAuth 2.1 · GNAP · UMA · RAR · XAA (Okta/MCP)
Layer 3: IDENTITY      SPIFFE · OIDC-A · DID/VC · SCIM extensions
Layer 2: COMMUNICATION MCP (16K+ servers) · A2A (Linux Foundation) · ANP
Layer 1: DISCOVERY     A2A Agent Cards · MCP Registry · llms.txt · AGENTS.md
◆ KCP                  Knowledge resource description — the gap none of these fill
Layer 0: INFRA         AgentGateway (Linux Foundation) · HTTP/SSE/gRPC
```

KCP fills a specific gap: **nobody describes knowledge resources with full metadata** — auth, payment, freshness, trust, relationships, compliance. llms.txt is too simple (just URLs). AGENTS.md is code-repo-specific. A2A Agent Cards describe agents, not knowledge. MCP Registry describes tools, not knowledge.

That gap is real, it is growing, and the window to establish a standard is open now.

---

## What We Are Asking

We are **not** asking you to approve all 14 proposals. We are asking for:

### 1. Priority signal

Which of these proposals would unblock a real use case you have?

Pick your top 3. Comment on the relevant GitHub issue with your use case. If 10 people say compliance metadata is a blocker, that moves to v0.2. If nobody mentions multilingual support, we defer it indefinitely.

### 2. Design feedback

Several proposals have open questions that need community input:

- **Rate limits** (Issue #4): Should unit-level overrides merge with or replace root-level defaults?
- **Service boundary** (Issue #7): Is the `x-service-*` convention sufficient, or do you need a formal `kind` field sooner?
- **Cross-manifest federation** (Issue #12): Is the `x-external-ref` interim approach good enough for v0.1?
- **License** (Issue #8): Should there be a simple `no_train: true` boolean, or is the full permissions/restrictions model worth it?

### 3. Use cases we have not thought of

What would you use KCP for that we have not considered? Open a new issue or add a comment to the discussion.

---

## Governance and Process

KCP is proposed by [eXOReaction AS](https://www.exoreaction.com) and hosted under the [Cantara](https://github.com/Cantara) open source community.

We follow this process for spec changes:

| Stage | What happens |
|-------|-------------|
| **RFC** (now) | Open discussion, use case collection, design feedback |
| **Draft** | Selected proposals written into draft spec text |
| **Review** | Two-week community review period |
| **Stable** | Merged into SPEC.md, version bumped |

Nothing in this document is committed to the spec until it completes the full process.

### Planned milestones

- **Now → March 31:** RFC discussion period — comment on issues, propose alternatives
- **April 2026:** Respond to [NIST NCCoE RFI](https://www.nccoe.nist.gov/projects/software-and-ai-agent-identity-and-authorization) (deadline April 2) — KCP's chance to appear in NIST's AI agent standards work
- **April–May 2026:** Draft spec text for highest-priority proposals
- **June 2026:** Target for KCP v0.2 spec release

---

## How to Participate

**Comment on a specific proposal:** Go to the relevant GitHub issue linked above.

**Share a use case:** Open a [new issue](https://github.com/Cantara/knowledge-context-protocol/issues/new) describing your scenario. We want real use cases, not theoretical ones.

**Join the discussion:** GitHub Discussions thread: [RFC-0001 Discussion](https://github.com/Cantara/knowledge-context-protocol/discussions/15)

**Try it today:** Add a `knowledge.yaml` to your project. The current spec works with three fields. Use `x-` prefix fields for anything in this RFC that you need now — they are forward-compatible.

```yaml
# Minimum viable KCP — works today
project: my-project
units:
  - id: overview
    path: README.md
    intent: "What is this project and how do I get started?"
```

---

## Summary Table

| # | Proposal | Theme | Status |
|---|----------|-------|--------|
| A | Auth metadata (`access` + `auth` block) | Access | → [RFC-0002](./RFC-0002-Auth-and-Delegation.md) |
| B | Delegation constraints | Access | → [RFC-0002](./RFC-0002-Auth-and-Delegation.md) |
| C | Payment metadata | Economics | → [RFC-0005](./RFC-0005-Payment-and-Rate-Limits.md) (`payment` block) |
| D | Rate limit metadata | Economics | → [RFC-0005](./RFC-0005-Payment-and-Rate-Limits.md) (`rate_limits` block) |
| E | Trust and audit metadata | Governance | → [RFC-0004](./RFC-0004-Trust-and-Compliance.md) (`trust` block) |
| F | License and usage rights | Governance | ✅ Promoted to core in v0.3 (`license` field) |
| G | Data residency and compliance | Governance | → [RFC-0004](./RFC-0004-Trust-and-Compliance.md) (`compliance` block) |
| H | AI crawling permissions | Governance | ✅ Promoted to core in v0.3 (`indexing` field) |
| I | Content format metadata | Navigation | ✅ Promoted to core in v0.3 (`format` + `content_type`) |
| J | Context window hints | Navigation | → [RFC-0006](./RFC-0006-Context-Window-Hints.md) (`hints` block) |
| K | Caching and update frequency | Navigation | ✅ Promoted to core in v0.3 (`update_frequency` field) |
| L | Multilingual and localisation | Navigation | ✅ Promoted to core in v0.3 (`language` field) |
| M | Cross-manifest federation | Scale | → [RFC-0003](./RFC-0003-Federation.md) |
| N | Service description boundary (`kind` field + `x-service-*`) | Scope | ✅ Promoted to core in v0.3 (`kind` field) |

---

*Knowledge Context Protocol — proposed by [eXOReaction AS](https://www.exoreaction.com), Oslo, Norway.*
*Spec: [github.com/Cantara/knowledge-context-protocol](https://github.com/Cantara/knowledge-context-protocol)*
