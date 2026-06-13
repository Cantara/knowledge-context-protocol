---
name: kcp-adopt
description: Add a Knowledge Context Protocol (KCP) knowledge.yaml manifest to a codebase or documentation set so AI agents navigate it by intent instead of exploring blindly. Use when someone wants to make a repository "agent-ready", cut an agent's tool calls and context spend, adopt KCP, or upgrade an llms.txt to structured knowledge metadata.
---

# Adopt KCP in a project

Produce a valid `knowledge.yaml` at the project root so an agent finds the right
files by task, not by guessing. Validated benchmark: 53–80% fewer agent tool
calls versus unguided exploration.

## When to use

The user wants their repo or docs site to be navigable by agents, is asking
"how do I adopt KCP", wants to reduce the context an agent burns exploring, or
has an `llms.txt` they want to make structural.

## Steps

1. **Survey the project.** List the top-level structure and identify the
   distinct *knowledge units* — a unit is a file or directory that answers one
   recurring question (the overview, the API reference, the deploy runbook, the
   contributing guide, the architecture doc). Aim for ~5–20 units, not one per
   file. Group related files into a directory-path unit.

2. **Scaffold.** Install the `kcp` developer CLI once (via
   [kcp-commands](https://github.com/Cantara/kcp-commands)), then:
   ```bash
   kcp init        # writes knowledge.yaml
   ```
   If the CLI is unavailable, hand-write the file — see the minimal shape below.

3. **Author each unit.** Five fields are enough to start (Level 1):
   ```yaml
   - id: deploy                       # lowercase, hyphens/dots
     path: ops/deploy.md              # file or directory, repo-relative
     intent: "How do I deploy a release to production?"   # the question it answers
     scope: project                   # global | project | module
     audience: [operator, agent]      # human | agent | developer | operator | ...
     triggers: [deploy, release, production]   # keywords an agent would search
   ```
   Write `intent` as the natural-language question a user would ask. Make
   `triggers` the words that question would contain. Add `depends_on: [other-id]`
   for reading order and `not_for: ["what this unit does NOT answer"]` to stop
   an agent loading it for the wrong task.

4. **Validate** and fix every error (warnings are advisory):
   ```bash
   kcp validate
   ```

5. **Prove it.** Simulate an agent query:
   ```bash
   kcp query "how do I deploy?"
   ```
   The best-matching unit should come back. Iterate `intent`/`triggers` until
   the obvious questions route to the right units.

## Adoption levels (stop wherever the value is)

- **L1** — the five fields above. Five minutes; this is most of the benefit.
- **L2** — add `relationships`, `depends_on`, `not_for`, `content_structure`.
- **L3** — federation (`manifests[]`), trust/signing, `temporal` validity.
- **L4** — full agent orchestration (render pipeline, observability).

Do not over-engineer. The format allows complexity but never demands it.

## Reference

- Spec: `SPEC.md` · Guide: `guides/adopting-kcp-in-existing-projects.md`
- The companion skills `kcp-author` (write better units) and `kcp-navigate`
  (use a manifest) go deeper.
