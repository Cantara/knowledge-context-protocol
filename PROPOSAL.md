# The Case for a Knowledge Architecture Standard for AI Agents

*A proposal for a community standard addressing the knowledge representation gap in AI agent
deployments.*

*eXOReaction AS — February 2026*

---

## Executive Summary

AI agents need structured, navigable knowledge. The current best practice — `llms.txt` — provides
a flat index. What agents actually need is a map: topology, freshness, intent, and selective
loading. This document proposes the **Knowledge Context Protocol (KCP)**, a lightweight file
format specification that fills the gap between a static text file and a full runtime knowledge
engine.

The gap is not theoretical. We discovered it empirically while deploying production AI agents
against a codebase of 8,934 files. The agents failed — not because the models were weak, but
because the knowledge they consumed was stale, unstructured, and had no way to signal its own
limitations.

---

## 1. Where the Problem Comes From

The AI agent ecosystem has invested heavily in three layers:

1. **Model capability** — reasoning, code generation, multi-step planning
2. **Agent frameworks** — orchestration, tool use, memory, action execution
3. **Tool connectivity** — MCP and similar protocols for connecting agents to external systems

What has been largely neglected is the layer that sits between an agent and the knowledge it
needs to act: **how that knowledge is structured**.

The current state of the art is one of two approaches:

**Stuff the context.** Load everything that might be relevant into the system prompt. This hits
context limits immediately in any real system, becomes a maintenance burden as knowledge evolves,
and provides no mechanism for the agent to distinguish fresh information from stale.

**Use RAG.** Embed documents, store them in a vector database, retrieve the most "similar" chunks
at query time. This works for unstructured natural language content. It does not work well for
structured technical knowledge — code, dependencies, cross-repository relationships — where what
you need is structural relevance, not semantic similarity.

Both approaches treat knowledge as a payload to be delivered, rather than as a navigable
structure to be queried.

---

## 2. What llms.txt Gets Right — and Where It Stops

Jeremy Howard's llms.txt standard is genuinely useful. It provides a canonical, machine-readable
index at a known location. For personal sites, project landing pages, and small documentation
sets, it solves the "existence problem" cleanly: an agent can find out what a site contains.

The problem is not that llms.txt is badly designed. It is that the problem it solves is the
smallest and least interesting version of the actual problem.

A site with 27 blog posts is well-served by llms.txt. An enterprise with 8,934 files across
multiple repositories is not. The standard has no answer for scale beyond "make a bigger text
file." This is the same architectural mistake as loading entire database tables into memory
instead of using indexed queries — it worked when tables were small, and it stopped working
precisely when it mattered most.

The six structural limitations of llms.txt are documented in the README. They are not bugs to
be fixed in a future version; they are consequences of the standard's design philosophy.
Dead simple is the right choice for the "hello world" of knowledge representation. It is not
the right choice for production AI agent deployments.

---

## 3. Evidence from Production

We did not invent this problem. We ran into it.

In January 2026, eXOReaction built a PCB design library — 197,831 lines of Java in 11 days,
using AI-assisted development at approximately 25–66x the industry standard pace. The AI tools
worked. The problem was what happened next: 8,934 files existed across three workspaces, and
the agents we deployed to work with this codebase had no reliable way to navigate it.

We ran what we called the Mirror Test: an AI agent consuming stale skill files about a module
gave confident, fluent, wrong answers about that module's structure — because the documentation
had not kept pace with the code. The agent did not know what it did not know. It had high
confidence and low accuracy. Stale knowledge that looks authoritative is worse than no knowledge
at all.

This failure mode has a direct cause: there was no freshness signal, no dependency graph, no
way for the knowledge to declare its own scope or limitations. The agent had a table of contents.
It needed a map.

We built Synthesis to address this for our own use. We are proposing KCP to address it for
everyone.

---

## 4. What KCP Is and Is Not

**KCP is a file format specification.** A `knowledge.yaml` manifest you drop at the root of a
project. It is still a file. It still works without any tooling. The minimum viable version has
three fields. You can start in five minutes.

**KCP is not a runtime protocol.** It does not require a server, a database, or a running
process. A static site can be fully KCP-compliant.

**KCP is not a replacement for llms.txt.** The two standards address adjacent problems. llms.txt
tells agents what a site contains. KCP tells agents how that content is structured, what it
depends on, how fresh it is, and when to load it. They can coexist.

**KCP is not Synthesis.** Synthesis is an open-source knowledge infrastructure tool that
implements KCP natively. But KCP is an open standard — any tool can implement it, and a
KCP-compliant `knowledge.yaml` is useful even without Synthesis.

---

## 5. The Design Principles

KCP is designed around five principles derived from what actually failed in production:

**Topology over lists.** Knowledge units declare what they depend on and what they supersede.
An agent loading a unit knows what else it needs first. An agent updating documentation knows
what depends on the unit it is changing.

**Intent over titles.** Each unit declares the question it answers, not just its title. "What
is the deployment procedure for version 3.x?" is a better signal for an agent than
"deployment.md".

**Freshness as a first-class field.** Every unit carries a `validated` timestamp — the last
date a human confirmed the content was accurate. An agent can refuse to act on knowledge that
has not been validated since a major release.

**Selective loading.** An agent working on authentication loads units tagged for authentication,
in dependency order. It does not load the project history, the contributor guide, and the
changelog as a side effect.

**Adoption gradient.** Three fields are enough to start. The full field set is available for
those who need it. The standard allows complexity but does not demand it.

---

## 6. Positioning

KCP sits in a specific gap in the current ecosystem:

```
llms.txt          KCP                    Synthesis
(static index) ── (structured format) ── (runtime engine)
```

llms.txt is widely adopted and useful for simple cases. Synthesis (and similar knowledge
engines) are powerful but require infrastructure. KCP is the format specification that allows
any tool — existing or future — to serve structured knowledge to agents without reinventing
the metadata model.

The parallel to MCP is deliberate:

- **MCP**: open standard for how agents connect to tools. Anthropic proposed it; the ecosystem
  adopted it; many tools now implement it.
- **KCP**: open standard for how knowledge is structured for agents. eXOReaction is proposing
  it; the goal is the same ecosystem adoption.

Anthropic's MCP solved the tool connectivity problem. No equivalent standard exists for
knowledge structure. KCP is a proposal for what that standard should look like.

---

## 7. Who Benefits

**Individual developers** with personal sites or project documentation: drop a `knowledge.yaml`
next to your `llms.txt`. Five minutes. Agents querying your site can now navigate by intent and
check freshness.

**Open source projects**: structure documentation as knowledge units. Contributors and agents
understand prerequisites. Documentation drift is visible — units with stale `validated` dates
surface as technical debt.

**Enterprise documentation teams**: replace flat doc sites with knowledge-graph-navigable
documentation. Multiple agent roles query the same corpus with different task contexts. The
authorship burden shifts from "write for every possible reader" to "declare intent and audience."

**AI tool makers**: KCP becomes the interchange format for structured knowledge. A tool that
consumes `knowledge.yaml` works with any KCP-compliant documentation source.

---

## 8. What We Are Asking For

This is a draft proposal. We are asking for:

1. **Feedback on the format.** Are the fields right? Are there missing fields that real use
   cases require? Are any fields wrong-headed?

2. **Use cases.** What does your agent deployment need that this format does not address?

3. **Implementations.** If you build a tool that reads or generates `knowledge.yaml`, we want
   to know about it.

4. **Adoption.** If you add a `knowledge.yaml` to a project and it works, that is evidence.
   If it does not work, that is more valuable evidence.

The reference parser (Python) is forthcoming. `synthesis export --format kcp` is on the
Synthesis roadmap. Both will be available before we consider this format stable.

---

## 9. What Must Be True for This to Succeed

We are honest about the conditions for success:

- The minimum viable `knowledge.yaml` must be genuinely simple — three fields, five minutes
- At least two independent tools must implement KCP within six months
- The spec must fit in one README
- At least one conference talk must validate that the problem resonates with the community

If these conditions are not met, the standard should be reconsidered. We are not proposing KCP
because we want a standard. We are proposing it because we ran into the problem it solves, built
a partial solution, and believe the format layer should be open.

---

*eXOReaction AS — Oslo, Norway — February 2026*

*Synthesis: [github.com/exoreaction/Synthesis](https://github.com/exoreaction/Synthesis)*
*KCP Spec: [github.com/cantara/knowledge-context-protocol](https://github.com/cantara/knowledge-context-protocol)*
