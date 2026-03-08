# KCP Examples

Four reference implementations showing the adoption gradient — from the minimum viable configuration to multi-agent coordination with A2A.

## [minimal/](./minimal/)

**Five minutes.**

The smallest valid `knowledge.yaml`. Start here if you are not sure whether you need KCP yet.

```yaml
kcp_version: "0.6"
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

## The adoption gradient

| Stage | What you have | When to use it |
|-------|--------------|----------------|
| **Minimal** | `id`, `path`, `intent`, `scope`, `audience` | Any project. Start here. |
| **Personal site** | + `validated`, `depends_on` | Personal wikis, portfolios, small docs sites |
| **Open source wiki** | + `triggers`, `relationships` | Multi-section knowledge bases, community docs |
| **Enterprise** | + full relationship graph, role-based audience, cross-repo units | Large orgs, multiple repositories, agent deployments |
| **Multi-agent** | + A2A Agent Card, `auth`, `delegation`, `trust.audit`, per-unit PII | Multi-agent systems, regulated domains, delegation chains |
