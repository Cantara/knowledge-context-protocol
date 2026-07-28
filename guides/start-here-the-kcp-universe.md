# Start here: the KCP universe

A map for someone arriving cold. What the protocol is, the three kinds of unit that
matter, the tools around it, and how the RFC process actually works.

**Time:** ~15 minutes reading, ~10 running the example. **Prerequisites:** Node 20+.

Every command below was run against `@cantara.no/kcp@0.29` and shows its real output.

---

## 1. The one idea

An agent pointed at your repository can read everything and knows nothing about what
matters. `llms.txt` helps by listing files; it does not say what they are *for*, who may
read them, whether they are still true, or what an agent is permitted to *do* with them.

KCP is a `knowledge.yaml` at your repository root that answers those questions in a form
a machine can act on. It is a **file format**, not a service — no server, no database, no
running process. A static site can be fully conformant.

The unit of description is a **unit**: a pointer to a file, plus the intent it answers,
who it is for, and how far an agent may go with it.

## 2. Three kinds, and the question each one answers

`kind` is what separates a document from something that acts. Seven values exist —
`knowledge`, `schema`, `service`, `policy`, `executable`, `skill`, `playbook` ([§4.3a]) —
and three carry the weight for most authors. They form a progression.

| kind | The question | What an agent does with it |
|---|---|---|
| `knowledge` | *What is true?* | reads it |
| `skill` | *How do I do this one thing?* | enacts it, bounded by an `action_scope` |
| `playbook` | *How do we do this whole thing?* | enacts it **step by step**, each with its own ceiling |

The default is `knowledge`, and most units are that. The other two are newer — `skill`
arrived in v0.26, `playbook` in v0.29 — and they are where the governance lives.

### `kind: knowledge` — a document

```yaml
- id: release-process
  path: docs/release-process.md
  intent: "How does our release process work?"
  scope: project
  audience: [agent, human]
```

Nothing here can act. An agent selects it by intent and reads it.

### `kind: skill` — one governed procedure

```yaml
- id: run-tests
  path: skills/run-tests.md
  intent: "How do I run the test suite and read the result?"
  kind: skill
  load_eligible: true          # the grant. Without it, this is inert.
  action_scope:
    tools: [bash]
    paths: ["test/**"]
```

Two things changed. `action_scope` bounds what the procedure may touch — a firewall rule,
not a description. And **a skill fails closed**: absent an explicit eligibility grant it
renders as a pointer an agent may read but not run.

That default is the point. A procedure that acts should require someone to have said yes.

### `kind: playbook` — a composition, governed per step

A release spans several levels of risk: read the build status, open a request, wait for a
human, then publish. A skill declares **one** `action_scope` for the whole artifact, so an
author must either over-grant the reading steps or block the publishing one.

A playbook makes the **step** the unit of governance:

```yaml
- id: cut-release
  kind: playbook
  load_eligible: true
  authority_level: commit        # ceiling over every step
  steps:
    - id: verify
      uses: run-tests            # ← a reference to another unit, not prose
      authority_level: observe
      success_condition: "the suite reports zero failures"
      on_failure: abort
    - id: publish
      uses: publish-package
      depends_on: [verify]
      authority_level: commit
      escalation: requires_approval
      on_failure: escalate
```

Note `uses`. A step names **another unit**, which is the whole reason `playbook` exists as
a separate kind: a checker can resolve the reference, confirm the target is a skill, and
compare what the step claims against what that skill is actually allowed to touch. Prose
steps cannot be checked.

Three rules worth knowing before you write one ([§4.3b]):

- **A playbook can never raise authority.** Effective authority is the *minimum* across
  five sources — the step, the playbook, the task-type ceiling, the tenant ceiling, and
  the agent's own grant. Composing units cannot grant what neither the units nor the
  grants allow, which is what makes a playbook safe to select automatically.
- **`success_condition` is prose the protocol never evaluates.** It is checked by the
  enacting agent against the world, not by a parser against a string.
- **`on_failure: continue` continues the run, not the dependents.** A step is never
  enacted unless everything it depends on succeeded.

### Which one am I writing?

> If your steps all sit inside one procedure's `action_scope`, they are prose, and you
> want a **skill**. If they span units with different authority requirements — read, then
> propose, then wait for a human, then commit — you want a **playbook**.

## 3. Run it

Every output below is real.

```bash
mkdir acme-release && cd acme-release
mkdir -p docs skills playbooks
echo "# Release process" > docs/release-process.md
echo "# Run tests"       > skills/run-tests.md
echo "# Publish"         > skills/publish-package.md
echo "# Cut release"     > playbooks/cut-release.md
```

Write `knowledge.yaml` with the three units from §2 plus a `publish-package` skill, then:

```bash
npx @cantara.no/kcp@0.29 validate knowledge.yaml
```

```
✓ Valid — no errors or warnings
```

Now break it deliberately — point a step at a unit you never declared:

```yaml
    - id: publish
      uses: publish-package-typo
```

```
✗ 1 error:
  ● Unit 'cut-release' step 'publish': 'uses' names unit
    'publish-package-typo', which is not declared in this manifest (§4.3b)
```

**That error is the point of the kind.** A prose step saying "publish the package" cannot
be wrong in a way a machine detects. A `uses` reference can.

One more — make the steps depend on each other in a circle:

```yaml
    - id: verify
      uses: run-tests
      depends_on: [publish]      # publish already depends on verify
```

```
✗ 1 error:
  ● Unit 'cut-release': 'depends_on' graph contains a cycle:
    verify -> publish -> verify (§4.3b)
```

## 4. The tooling universe

The spec repo holds the specification, the JSON schema, three reference parsers
(TypeScript, Python, Java) and the `kcp` CLI. Everything else is a separate repository
that consumes it.

**Start with these three:**

| | What it is | Install |
|---|---|---|
| [`kcp`][cli] | The CLI: `init`, `validate`, `sign`, `render`, `query` | `npx @cantara.no/kcp@0.29` |
| [kcp-agent] | A deterministic planner — given a task, decides which units an agent may load, through a 14-gate cascade. No LLM. | `npm i kcp-agent` |
| [kcp-harness] | An MCP compliance proxy between an agent and its tools. Fail-closed: what it cannot verify, the agent does not get. | `npm i kcp-harness` |

**Then, as you need them:**

| | What it is |
|---|---|
| [kcp-skill] | Authoring profile, linter and conformance vectors for `kind: skill`, plus a curated library |
| [kcp-memory] | Episodic memory — indexes session transcripts and tool-call events |
| [kcp-commands] | Claude Code hook: injects compact CLI guidance, filters output |
| [kcp-hooks] | Session-start context loading for Claude Code |
| [kcp-dashboard] | Live terminal dashboard for toolchain usage |
| [kcp-triage] | Discovers web services and generates manifests for them |
| [pi-kcp] | KCP for the Pi harness — and **15 runnable demos**, the fastest way to see governance working |
| [kcp-playground] | In-browser demos, no install |

If you only run one thing, run the pi-kcp demos. Demo 2 shows a skill failing closed;
demo 15 shows a playbook doing the same and enforcing per-step ceilings.

## 5. How the RFC process works

The specification changes through RFCs, and the vocabulary is worth knowing because it
tells you what is *real* versus *proposed*.

```
issue  →  RFC (Draft)  →  adversarial review  →  Accepted  →  promoted into SPEC.md
```

The `**Status:**` line at the top of each RFC file is the truth:

| Status | Means |
|---|---|
| `Draft` | proposed. Nothing implements it. Do not build on it. |
| `Accepted — promoted to SPEC.md v0.27` | in the spec, in the parsers, safe to use |

**An RFC being merged does not mean the feature ships.** Merging records that reviewers
reached rough consensus on the design; implementation is separate work, and the
promotion note tells you when it landed.

So: **read `SPEC.md` for what exists; read the RFCs for why, and for what is coming.**

### Reviewing is where the value is

RFCs here go through an adversarial review before promotion — several independent
readers, each with a different lens: normative precision, implementability, security,
and citation-checking. The last one earns its place most often. This series has, more
than once, cited mechanisms that did not exist — and caught it in review rather than in
production.

If you read one RFC to understand the culture, read [RFC-0027]'s changelog. It records
what each round of review broke in the previous round's fix.

## 6. Where to go next

- **Adopt it on a real repo** → [KCP-enable a GitHub repository, end to end][t1] —
  init through signing and trusted render
- **Retrofit an existing project** → [Adopting KCP in existing projects][t2]
- **Author skills properly** → [kcp-skill]'s `PROFILE.md`
- **Understand the trust model** → `SPEC.md` §16, and RFC-0018
- **See it run** → the [pi-kcp] demos

---

[§4.3a]: ../SPEC.md
[§4.3b]: ../SPEC.md
[RFC-0027]: ../RFC-0027-Playbooks.md
[t1]: ./kcp-enable-a-github-repo.md
[t2]: ./adopting-kcp-in-existing-projects.md
[cli]: https://www.npmjs.com/package/@cantara.no/kcp
[kcp-agent]: https://github.com/Cantara/kcp-agent
[kcp-harness]: https://github.com/Cantara/kcp-harness
[kcp-skill]: https://github.com/Cantara/kcp-skill
[kcp-memory]: https://github.com/Cantara/kcp-memory
[kcp-commands]: https://github.com/Cantara/kcp-commands
[kcp-hooks]: https://github.com/Cantara/kcp-hooks
[kcp-dashboard]: https://github.com/Cantara/kcp-dashboard
[kcp-triage]: https://github.com/Cantara/kcp-triage
[pi-kcp]: https://github.com/Cantara/pi-kcp
[kcp-playground]: https://github.com/Cantara/kcp-playground
