---
name: kcp-author
description: Write or improve units in a KCP knowledge.yaml manifest — effective intents, triggers, relationships, audience targeting, negative-space (not_for), and temporal validity — and validate them. Use when editing a knowledge.yaml, when an agent keeps loading the wrong unit for a task, or when manifest queries return poor matches.
---

# Author effective KCP units

A manifest is only as good as its routing. The job is to make the right unit
win for the right question and never load for the wrong one.

## When to use

Someone is editing a `knowledge.yaml`, query results are off (wrong unit, or no
match), or a manifest needs richer metadata (relationships, negative space,
validity windows).

## What makes a unit route well

- **`intent` is the question, not the title.** "How do I authenticate a
  request?" beats "Auth docs". Agents match against this in natural language.
- **`triggers` are the words that question contains.** Include synonyms and the
  domain terms a user would actually type (`auth, login, oauth, token, sso`).
  Keep each ≤ 60 chars, ≤ 20 per unit.
- **`not_for` prevents misfires** (RFC-0015, §4.20). If a unit is tempting but
  wrong for a class of task, say so: `not_for: ["billing questions", "frontend routing"]`.
  Add `not_for_strict: true` for a hard exclusion rather than a soft demotion.
- **`scope` and `audience` narrow the field.** `audience: [agent]` for
  agent-only entry points; `[human]` for content agents should not surface.
- **`content_structure`** (RFC-0016, §4.19) lets RAG route before fetching:
  `primary: table | prose | code | …`, `density: sparse | normal | dense`.

## Relationships and ordering

- `depends_on: [id]` — load these first (reading order).
- A `relationships` block expresses typed edges: `enables`, `context`,
  `supersedes`, `contradicts`, `depends_on`, `governs`.

## Temporal validity (when knowledge changes over time)

Use a `temporal` block (RFC-0010, §4.22) for policies and runbooks that expire
or are future-dated:
```yaml
temporal:
  valid_from: "2026-04-01"      # activates on this date (future-dating is fine)
  valid_until: "2026-06-30"     # null = open-ended
  superseded_by: deploy-v2      # successor unit id; keeps an audit trail
```
`superseded_by` must not form a cycle, and an expired `valid_until` should name
a `superseded_by` — the validator warns otherwise.

## Always finish by validating

```bash
kcp validate           # fix errors; warnings are advisory
kcp query "<a real user question>"   # confirm routing
```

Iterate until each obvious question returns the unit you intended. See
`SPEC.md` for the full field set; `guides/` for worked examples.
