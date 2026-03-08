# A2A Agent Card + KCP Manifest: Two Layers, One Agent

This example shows how a [Google A2A Agent Card](https://google.github.io/A2A/) and a [KCP manifest](https://github.com/cantara/knowledge-context-protocol) compose as complementary layers in a multi-agent system.

**Domain:** Clinical research. The agent helps researchers look up trial protocols and analyse patient cohorts. The domain makes the access control, PII handling, and human-in-the-loop constraints feel necessary rather than decorative.

**Blog post:** [The Front Door and the Filing Cabinet: A2A Agent Cards Meet KCP](https://wiki.totto.org/blog/2026/03/08/the-front-door-and-the-filing-cabinet-a2a-agent-cards-meet-kcp/)

---

## The mental model

**A2A Agent Card = the front door.** It tells other agents: here is what I can do, here is how to call me, here is how to authenticate *to* me.

**KCP manifest = the filing cabinet inside.** It tells agents: here is what knowledge I have access to, here is who can see each piece, here is how deep the delegation chain can go, and here is where a human must approve.

An agent without an Agent Card is invisible — no one can find it or call it.
An agent without a KCP manifest is a black box — callers know it exists, but not what knowledge it holds or what constraints apply to each piece.

A production multi-agent system needs both.

---

## What each layer covers

| Concern | A2A Agent Card | KCP Manifest |
|---------|---------------|--------------|
| **Granularity** | Per agent | Per knowledge unit |
| **Identity** | Agent name, URL, version, provider | Project name, version, publisher provenance |
| **Authentication** | How to authenticate *to the agent* (OAuth2 at the transport layer) | How to authenticate *to the knowledge source* (OAuth2/API key per auth method) |
| **Capabilities** | What the agent can *do* — skills, I/O modes, streaming | What the agent can *access* — knowledge units, their intent, freshness, format |
| **Access control** | Binary: you can call the agent or you cannot | Three levels per unit: `public`, `authenticated`, `restricted` |
| **Sensitivity** | Not addressed | Per-unit classification: `public`, `internal`, `confidential`, `restricted` |
| **Delegation** | Not addressed | `max_depth`, capability attenuation, `human_in_the_loop`, audit chain |
| **Audit** | Not addressed | `agent_must_log`, `require_trace_context` (W3C Trace Context) |
| **Discovery** | `/.well-known/agent.json` | `/.well-known/kcp.json` |

---

## Files in this example

### `agent-card.json` — A2A Agent Card

The [A2A specification](https://google.github.io/A2A/) defines a JSON file served at `/.well-known/agent.json`. This example declares:

- **OAuth2 security** (`client_credentials` flow) — how calling agents authenticate to this agent.
- **Two skills**: `protocol-lookup` (retrieve trial protocols) and `cohort-analysis` (analyse patient data).
- **Streaming** capability enabled.
- **JSON and text** I/O modes.
- **`knowledgeManifest`** field pointing to `/.well-known/kcp.json` — the link from the front door to the filing cabinet.

The `knowledgeManifest` field is not part of the A2A specification today. It is a proposed extension point: a single URL that lets any agent that discovers the Agent Card also discover the knowledge inventory behind it.

### `knowledge.yaml` — KCP Manifest

A [KCP v0.6](https://github.com/cantara/knowledge-context-protocol) manifest for the same agent. Three knowledge units with escalating access:

| Unit | Access | Sensitivity | Auth Scope | Delegation | HITL |
|------|--------|------------|------------|------------|------|
| `public-guidelines` | `public` | `public` | — | root default (depth 2) | no |
| `trial-protocols` | `authenticated` | `internal` | — | root default (depth 2) | no |
| `patient-cohort` | `restricted` | `restricted` | `clinical-staff` | depth 1 (override) | **required** |

The manifest also declares:

- **Root `auth.methods`**: OAuth2 (`client_credentials`) + `none` fallback for public units.
- **Root `trust.audit`**: `agent_must_log: true`, `require_trace_context: true`.
- **Root `delegation`**: `max_depth: 2`, `require_capability_attenuation: true`, `audit_chain: true`.
- **Per-unit delegation override** on `patient-cohort`: `max_depth: 1`, `human_in_the_loop: required`.

---

## How they compose: a multi-agent interaction

A requesting agent wants to analyse patient cohort data. Here is the step-by-step flow:

```
Requesting Agent                    Clinical Research Agent
      |                                      |
      |  1. GET /.well-known/agent.json      |
      |------------------------------------->|
      |  <-- Agent Card (skills, auth)       |
      |                                      |
      |  2. Read knowledgeManifest URL       |
      |     from Agent Card                  |
      |                                      |
      |  3. GET /.well-known/kcp.json        |
      |------------------------------------->|
      |  <-- { manifest: "/knowledge.yaml" } |
      |                                      |
      |  4. GET /knowledge.yaml              |
      |------------------------------------->|
      |  <-- KCP manifest (3 units)          |
      |                                      |
      |  5. Inspect patient-cohort unit:     |
      |     access: restricted               |
      |     auth_scope: clinical-staff       |
      |     delegation.max_depth: 1          |
      |     human_in_the_loop: required      |
      |                                      |
      |  6. Acquire OAuth2 token with        |
      |     scope: read:cohort               |
      |------------------------------------->|  auth.research.example.com
      |  <-- access token                    |
      |                                      |
      |  7. Obtain human approval            |
      |     (oauth_consent flow)             |
      |                                      |
      |  8. Call cohort-analysis skill       |
      |     with token + trace context       |
      |------------------------------------->|
      |  <-- cohort analysis results         |
      |                                      |
```

**Steps 1-2** use A2A. The requesting agent discovers what the Clinical Research Agent can do and how to call it.

**Steps 3-5** use KCP. The requesting agent discovers what knowledge is available, checks access requirements, and learns that patient data requires `clinical-staff` scope and human approval.

**Steps 6-7** are the credential acquisition and human approval that KCP's manifest made the agent aware of *before* it attempted the call.

**Step 8** is the actual A2A skill invocation, now with the right credentials and trace context.

Without the Agent Card, the requesting agent would not know this agent exists or how to call it. Without the KCP manifest, the requesting agent would call `cohort-analysis` without knowing it needs `clinical-staff` scope or human approval — and would fail at runtime instead of planning ahead.

---

## What each layer cannot do alone

**A2A without KCP:**
- Knows the agent has a `cohort-analysis` skill, but not that patient data is PII.
- Knows OAuth2 is required to *call the agent*, but not that a specific scope (`clinical-staff`) is required for a specific knowledge unit.
- Cannot express delegation depth limits, capability attenuation, or human-in-the-loop requirements.
- Cannot distinguish between public guidelines and restricted patient data — it is all behind the same front door.

**KCP without A2A:**
- Knows exactly what knowledge exists and who can access each piece, but has no way to advertise the agent's capabilities to other agents.
- Cannot describe skills, I/O modes, or streaming support.
- Cannot participate in agent-to-agent discovery — other agents cannot find it.
- Describes the filing cabinet in detail, but the front door is missing.

---

## Using this example

1. Copy `agent-card.json` and `knowledge.yaml` into your project.
2. Replace the clinical research domain with your own.
3. Serve `agent-card.json` at `/.well-known/agent.json`.
4. Serve `knowledge.yaml` (or its JSON equivalent) via `/.well-known/kcp.json`.
5. Adjust the `auth.methods`, `delegation`, and per-unit `access`/`sensitivity` fields to match your requirements.

The `delegation` block is defined in [RFC-0002 Proposal 3](../../RFC-0002-Auth-and-Delegation.md) and has not yet been promoted to the core spec. It is included here because multi-agent delegation constraints are central to the A2A + KCP composition story. Use it in regulated or high-stakes deployments; omit it for simpler setups.

---

## Running the Simulator

The `simulator/` directory contains a runnable Java demo that walks through all four phases of the A2A + KCP interaction: agent discovery, knowledge discovery, OAuth2 authentication, and per-unit access decisions with escalating controls.

```bash
cd simulator
mvn package
java -jar target/kcp-a2a-simulator-0.1.0-jar-with-dependencies.jar --auto-approve
```

See [simulator/README.md](simulator/README.md) for prerequisites, build instructions, and interactive mode.

---

## References

- [A2A Specification](https://google.github.io/A2A/) — Google's Agent-to-Agent protocol
- [KCP Specification](https://github.com/cantara/knowledge-context-protocol) — Knowledge Context Protocol
- [RFC-0002: Auth and Delegation](../../RFC-0002-Auth-and-Delegation.md) — KCP auth, access control, and delegation constraints
- [RFC-0004: Trust and Compliance](../../RFC-0004-Trust-and-Compliance.md) — KCP trust, provenance, and audit
- [IANA Well-Known URI Registration](../../IANA-REGISTRATION.md) — `/.well-known/kcp.json`
- [Blog post: The Front Door and the Filing Cabinet](https://wiki.totto.org/blog/2026/03/08/the-front-door-and-the-filing-cabinet-a2a-agent-cards-meet-kcp/)
