# KCP Adopters

Projects and sites using `knowledge.yaml` in production.

To add your project: [open an issue](https://github.com/Cantara/knowledge-context-protocol/issues/new?title=Add+adopter:+[your+project]&body=Project+name:%0ARepository+URL:%0ABrief+description:)

---

## Live Adopters

| Project | URL / Repo | Level | Notes |
|---------|-----------|-------|-------|
| wiki.totto.org | https://wiki.totto.org | Level 3 | Personal knowledge wiki — 77 posts, KCP v0.5, llms.txt + knowledge.yaml |
| wiki.cantara.no | https://wiki.cantara.no | Level 2 | Cantara open source community wiki — KCP v0.5 |
| knowledge-context-protocol | https://github.com/Cantara/knowledge-context-protocol | Level 3 | This repo — dogfooding the spec |
| eXOReaction repo estate | https://github.com/exoreaction | Level 1–2 | 28 repos (Synthesis, lib-pcb, Mycelium, Elprint services) — knowledge.yaml at root of each |
| Cantara repo estate | https://github.com/cantara | Level 1–2 | 40+ repos (Xorcery, Whydah, Valuereporter, ConfigService, kcp-commands) — knowledge.yaml at root of each |
| Quadim repo estate | https://github.com/quadim | Level 1–2 | 40+ repos (Platform, Overlord, Skill, Discussion, FileStore, 15+ microservices) — knowledge.yaml at root of each |

## PRs Open (pending merge)

| Project | PR | Benchmark |
|---------|-----|-----------|
| crewAIInc/crewAI | [#4658](https://github.com/crewAIInc/crewAI/pull/4658) | 76% fewer tool calls |
| microsoft/autogen | [#7329](https://github.com/microsoft/autogen/pull/7329) | 80% fewer tool calls |
| huggingface/smolagents | [#2026](https://github.com/huggingface/smolagents/pull/2026) | 73% fewer tool calls |
| anomalyco/opencode | [#15839](https://github.com/anomalyco/opencode/pull/15839) | via opencode-kcp-plugin |

## Community Tools

| Project | Repo | Description |
|---------|------|-------------|
| kcp-basis-oppsett | https://github.com/StigLau/kcp-basis-oppsett | Reference KCP setup framework by Stig Lau (Skatteetaten) — templates, transition guide, snapshot-awareness pattern, component registry |
| kcp-triage | https://github.com/StigLau/kcp-triage | Automated KCP artifact generator — crawls a website and produces knowledge.yaml (v0.10), CLAUDE.md, skills, and API docs via LLM pipeline |
