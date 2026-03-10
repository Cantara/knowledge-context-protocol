# KCP Examples

Reference implementations and runnable simulation scenarios — from the minimum viable configuration to adversarial multi-agent stress tests.

## [minimal/](./minimal/)

**Five minutes.**

The smallest valid `knowledge.yaml`. Start here if you are not sure whether you need KCP yet.

```yaml
kcp_version: "0.9"
project: my-project
version: 1.0.0
units:
  - id: overview
    path: README.md
    intent: "What is this project and how do I get started?"
    scope: global
    audience: [human, agent]
```

---

## [personal-site/](./personal-site/)

**~10 units. Audience differentiation. Freshness dating.**

A personal portfolio or wiki. Shows:
- Separating human-only content (reading lists, personal notes) from agent-accessible content
- `audience: [agent]` for llms.txt / llms-full.txt — the AI entry points
- `depends_on` for reading order
- Simple relationship graph connecting professional content

Live reference: [wiki.totto.org/knowledge.yaml](https://wiki.totto.org/knowledge.yaml)

---

## [open-source-wiki/](./open-source-wiki/)

**~16 units. Topology across architecture layers. Freshness distinction.**

A large community wiki with projects, a multi-section knowledge base, and community content. Shows:
- Dependency chains across layers: `enterprise-architecture → software-architecture → iam-architecture`
- Freshness distinction: archived content (`validated: 2022-12-01`) vs. actively maintained files (`validated: 2026-02-25`)
- Audience targeting by role: `architect`, `developer`, `operator`, `agent`
- `enables` vs `context` relationships to express prerequisite vs background knowledge

Live reference: [cantara.github.io/wiki/knowledge.yaml](https://cantara.github.io/wiki/knowledge.yaml)

---

## [a2a-agent-card/](./a2a-agent-card/)

**Level 4 — Multi-Agent Coordination (A2A + KCP)**

An A2A Agent Card paired with a KCP manifest for a clinical research agent. Shows:
- How `/.well-known/agent.json` (A2A) and `/.well-known/kcp.json` (KCP) compose as complementary layers
- Escalating access control: `public` -> `authenticated` -> `restricted` with PII sensitivity
- Root-level `auth`, `trust.audit`, and `delegation` blocks
- Per-unit delegation override with `human_in_the_loop: required` on patient data
- The `knowledgeManifest` field in the Agent Card linking to the KCP manifest

Blog post: [The Front Door and the Filing Cabinet](https://wiki.totto.org/blog/2026/03/08/the-front-door-and-the-filing-cabinet-a2a-agent-cards-meet-kcp/)

---

## [api-platform-rate-limits/](./api-platform-rate-limits/)

**`rate_limits` at root and unit level. Access tier escalation.**

An API documentation platform where different documentation tiers have different advisory rate limits. Shows:
- Root-level `rate_limits` as default for all units
- Unit-level overrides (from generous 120/min for public quickstart to tight 5/min for internal architecture)
- Correlation between `access`/`sensitivity` and rate limit tightness
- `depends_on` vs `enables` vs `context` relationship types
- Advisory semantics: agents self-throttle, KCP does not enforce

---

## [dependency-graph/](./dependency-graph/)

**5 of 6 relationship types. Platform migration scenario.**

A NovaPlatform v1-to-v2 migration with 8 units exercising five of the six relationship types (the sixth, `governs`, is demonstrated in the [federation example](./federation/)):
- `depends_on` — migration-guide depends on platform-overview
- `enables` — platform-overview enables migration-guide
- `supersedes` — api-v2-reference supersedes api-v1-reference
- `contradicts` — legacy-security-policy contradicts zero-trust-policy
- `context` — legacy-security-policy provides context for zero-trust-policy

---

---

## Simulation Scenarios

Five runnable Java simulators that stress-test the A2A + KCP composition model at increasing complexity. Each surfaces specific spec behaviours and gaps, contributing to the v0.9+ roadmap.

### [scenario1-energy-metering/](./scenario1-energy-metering/)

**Level 1 — Happy path + HITL**

A utility company's energy metering agent. 2 agents, 4 knowledge units escalating from `public` to `access: restricted` with `human_in_the_loop`. 36 tests.

Demonstrates: basic public/authenticated/restricted flow, W3C Trace Context audit entries, `approval_mechanism` spec gap (implementation-defined in v0.6).

### [scenario2-legal-delegation/](./scenario2-legal-delegation/)

**Level 2 — 3-hop delegation chain**

A law firm's document review pipeline with 3 agents in a chain. Shows `max_depth: 0` (absolute no-delegation), capability attenuation enforcement (delegated token must have narrower scope), and delegation depth limit rejection.

Spec gaps surfaced: capability attenuation is declarative not mechanical; depth counting has no normative definition; `max_depth: 0` semantics are ambiguous.

### [scenario3-financial-aml/](./scenario3-financial-aml/)

**Level 3 — Adversarial (5 agents, 8 phases)**

Anti-money-laundering compliance orchestration with a `RogueAgent` that attempts 4 distinct violations plus a rate limit advisory burst: delegation depth exceeded, scope elevation, `max_depth: 0` bypass, GDPR data residency block, and advisory `rate_limits` burst on `customer-profiles`.

Spec gaps surfaced: no cryptographic delegation chain integrity; `no_ai_training` restriction is unenforceable; batched HITL flow is undefined; `compliance` block promoted from RFC-0004 to core in v0.7; content integrity and attestation remain RFC.

### [scenario4-rate-limit-aware/](./scenario4-rate-limit-aware/)

**Level 4 — Rate Limit Advisory (2 agents, 4 units)**

Two agents — PoliteAgent (self-throttling) and GreedyAgent (burst) — access the same manifest with escalating advisory `rate_limits`. PoliteAgent checks `isWithinLimit()` before each request and waits when the budget is exhausted. GreedyAgent ignores limits entirely but logs every `ADVISORY VIOLATION`. Demonstrates that `rate_limits` is metadata, not enforcement.

### [scenario5-dependency-ordering/](./scenario5-dependency-ordering/)

**Level 5 — Dependency Ordering (topological sort, 5 of 6 relationship types)**

A knowledge ingestion agent that builds a dependency graph from `depends_on` fields and `type: depends_on` relationships, topologically sorts using Kahn's algorithm, and loads units in safe order. Handles `supersedes` (logs "prefer this version"), `contradicts` (flags warnings), cycle detection with `CycleException`, and cascading skip when a dependency fails. 8 units from a platform migration scenario.

---

## The adoption gradient

| Stage | What you have | When to use it |
|-------|--------------|----------------|
| **Minimal** | `id`, `path`, `intent`, `scope`, `audience` | Any project. Start here. |
| **Personal site** | + `validated`, `depends_on` | Personal wikis, portfolios, small docs sites |
| **Open source wiki** | + `triggers`, `relationships` | Multi-section knowledge bases, community docs |
| **Enterprise** | + full relationship graph, role-based audience, cross-repo units | Large orgs, multiple repositories, agent deployments |
| **Multi-agent** | + A2A Agent Card, `auth`, `delegation`, `trust.audit`, per-unit PII | Multi-agent systems, regulated domains, delegation chains |
| **Rate-limited** | + `rate_limits` at root and unit level, advisory self-throttling | High-traffic APIs, tiered documentation, agent ecosystems |
| **Dependency-aware** | + all 6 relationship types, topological ordering, cycle detection | Platform migrations, versioned docs, policy evolution |
