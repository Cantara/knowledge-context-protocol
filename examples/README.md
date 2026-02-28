# KCP Examples

Three reference implementations showing the adoption gradient — from the minimum viable three-field configuration to a full enterprise knowledge graph.

## [minimal/](./minimal/)

**Five minutes.**

The smallest valid `knowledge.yaml`. Start here if you are not sure whether you need KCP yet.

```yaml
kcp_version: "0.3"
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

## The adoption gradient

| Stage | What you have | When to use it |
|-------|--------------|----------------|
| **Minimal** | `id`, `path`, `intent`, `scope`, `audience` | Any project. Start here. |
| **Personal site** | + `validated`, `depends_on` | Personal wikis, portfolios, small docs sites |
| **Open source wiki** | + `triggers`, `relationships` | Multi-section knowledge bases, community docs |
| **Enterprise** | + full relationship graph, role-based audience, cross-repo units | Large orgs, multiple repositories, agent deployments |
