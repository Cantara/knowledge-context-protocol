# WebMCP and KCP: Comparison and Composability

**Status:** Published (March 2026)
**Audience:** Developers evaluating agentic web standards
**Last verified:** WebMCP spec draft 5 March 2026 / KCP v0.10 draft 13 March 2026

---

## TL;DR

WebMCP and KCP solve different problems at different layers. They are not competing
standards. They are complementary — and a well-instrumented service will likely use both.

| | **WebMCP** | **KCP** |
|-|-----------|--------|
| **One-line summary** | Lets websites expose **actions** to in-browser AI agents | Lets knowledge sources declare **what they contain** to any AI agent |
| **Core question answered** | "What can an agent *do* on this page?" | "What does an agent *need to know* from this source, and under what constraints?" |
| **Artifact** | JavaScript API + HTML attributes | YAML manifest (`knowledge.yaml`) |
| **Runtime requirement** | Browser (Chrome 146+) | None (static file) |
| **Backed by** | Google + Microsoft (W3C Web ML CG) | Cantara / eXOReaction (submitted to AAIF) |

If you are building a website where AI agents need to **take actions** — book flights,
submit forms, navigate checkout flows — look at WebMCP.

If you have **knowledge resources** that agents need to navigate — documentation, specs,
policies, APIs — and you need those agents to understand what is available, how fresh it is,
who can access it, and what depends on what — look at KCP.

If you are building a service that has both actions *and* knowledge (most real services do),
use both. Section 5 of this document shows how.

---

## 1. What WebMCP Is

[WebMCP](https://webmachinelearning.github.io/webmcp/) is a browser-native API proposed by
Google and Microsoft, incubated in the W3C Web Machine Learning Community Group. It was
first published in August 2025 and entered early preview in Chrome 146 (February 2026).

The problem it solves: AI agents interacting with websites today rely on screenshot analysis,
DOM scraping, and coordinate-based clicking — what the WebMCP authors describe as "agents
squinting at pixels." These approaches are fragile, slow, and token-expensive. WebMCP
replaces them with a structured tool interface.

### Core API

**Imperative (JavaScript):**

```javascript
navigator.modelContext.registerTool({
  name: "book-flight",
  description: "Book a flight between two airports on a given date",
  inputSchema: {
    type: "object",
    properties: {
      origin:      { type: "string", description: "IATA airport code" },
      destination: { type: "string", description: "IATA airport code" },
      date:        { type: "string", format: "date" }
    },
    required: ["origin", "destination", "date"]
  },
  async execute(input, client) {
    const result = await flightAPI.search(input);
    return { flights: result.options, cheapest: result.options[0] };
  }
});
```

**Declarative (HTML — spec status: TODO):**

The declarative API will allow standard HTML forms to be exposed as tools without JavaScript.
This part of the spec is not yet defined (marked TODO as of the 5 March 2026 draft).

### Key characteristics

- **Browser as mediator.** The browser sits between the agent and the website. The agent
  sees a structured tool interface; it never touches the DOM directly.
- **User consent.** The browser prompts the user before the agent executes an action
  (e.g. "Allow AI to book this flight?"). The user remains in the loop.
- **Session inheritance.** Tools inherit the user's existing browser session — if the user
  is logged into their bank, WebMCP tools operate within that authenticated session.
- **Scope: in-browser only.** WebMCP requires a browser runtime. It does not address
  server-side agents, CLI agents, or agents operating on local file systems.

### Current gaps (as of March 2026)

The spec is early. The following sections are explicitly marked as TODO or empty in the
5 March 2026 draft:

- Declarative HTML API (not yet specified)
- `requestUserInteraction()` method steps (not yet defined)
- Security and privacy considerations (section empty)
- Accessibility considerations (section empty)
- Discoverability mechanism (how an agent finds what tools a site exposes — not resolved)

These are not criticisms. This is a draft community group report, not a W3C Recommendation.
The gaps reflect the normal state of an early-stage specification.

---

## 2. What KCP Is

[KCP](https://github.com/Cantara/knowledge-context-protocol) is a file format specification
for structured knowledge manifests. A `knowledge.yaml` file describes the knowledge resources
in a project — their intent, dependencies, freshness, audience, access requirements, and
compliance constraints — so that AI agents can navigate them without loading everything at
once.

The problem it solves: agents consuming knowledge today either stuff everything into context
(hits token limits immediately) or use RAG (works for prose, fails for structured technical
knowledge). Both approaches treat knowledge as a payload to be delivered, not as a navigable
structure to be queried. KCP provides the map.

### Core artifact

```yaml
kcp_version: "0.10"
project: acme-platform
version: 2.1.0
updated: "2026-03-01"

auth:
  methods:
    - type: oauth2
      issuer: "https://auth.acme.com"
      scopes: ["knowledge:read"]
    - type: api_key
      header: "X-API-Key"

compliance:
  data_residency: [EU]
  regulations: [GDPR]
  restrictions: [no-train]

units:
  - id: api-reference
    path: docs/api/openapi.yaml
    kind: schema
    intent: "What endpoints does the Acme API expose and how do I call them?"
    scope: global
    audience: [developer, agent]
    validated: "2026-02-28"
    triggers: [api, endpoints, rest, openapi]
    hints:
      token_estimate: 12000
      load_strategy: lazy

  - id: deployment-guide
    path: docs/ops/deployment.md
    intent: "How do I deploy Acme Platform to production?"
    scope: project
    audience: [operator, agent]
    validated: "2026-03-01"
    depends_on: [api-reference]
    triggers: [deploy, production, kubernetes, helm]

relationships:
  - from: api-reference
    to: deployment-guide
    type: enables
```

### Key characteristics

- **Static file. No runtime.** A `knowledge.yaml` works on a local file system, in a git
  repo, or on a static site. No server, no database, no running process.
- **Topology, not lists.** Units declare dependencies (`depends_on`), supersession
  (`supersedes`), and typed relationships (`enables`, `context`, `contradicts`).
- **Freshness as first-class metadata.** Every unit carries a `validated` timestamp — the
  last date a human confirmed accuracy. Agents can refuse to act on stale knowledge.
- **Access, auth, compliance, delegation.** KCP declares what credentials a resource
  accepts, what regulations apply, what delegation constraints exist — all before the
  agent makes contact.
- **Transport-agnostic.** Works with MCP (via the KCP-MCP bridge), with A2A Agent Cards,
  with plain HTTP, or with no network at all.

### Current gaps (as of March 2026)

- Cross-manifest federation (RFC-0003) was promoted to core in v0.9 (§3.6); federation version pinning added in v0.10
- Content integrity verification (cryptographic signing) remains in RFC-0004
- Payment/rate-limit details beyond `default_tier` remain in RFC-0005
- Adoption is early: conformance tests pass in three languages (268 tests), but
  ecosystem-wide consumption is still growing

---

## 3. Where They Differ

The fundamental difference is not a matter of features. It is a difference in *what kind
of thing* each protocol describes.

### 3.1 Actions vs Knowledge

WebMCP describes **actions**: things an agent can *do* on a website. Register a tool, call
it, get a result. The mental model is a function call.

KCP describes **knowledge**: things an agent needs to *understand* before it can act
effectively. What documents exist, what they contain, how fresh they are, what depends on
what, who is allowed to read them. The mental model is a map.

This is not a spectrum — they are categorically different concerns:

```
WebMCP:  "Here is a function you can call: book-flight(origin, destination, date)"

KCP:     "Here is a document about our flight booking API — it was validated yesterday,
          depends on the authentication guide, requires OAuth 2.1, and you should load
          it before attempting to call any endpoints."
```

### 3.2 Runtime vs Static

WebMCP requires a browser. The API (`navigator.modelContext`) is a browser API.
Tools are registered in JavaScript and execute in the page's context. No browser, no WebMCP.

KCP requires nothing. The manifest is a YAML file. It works on a USB drive. It works in a
git repo that has never been cloned. It works on a static site served from S3 with no
JavaScript. It works as input to a CLI agent that has never opened a browser.

### 3.3 Scope

| Dimension | WebMCP | KCP |
|-----------|--------|-----|
| Where it runs | In-browser only | Anywhere (filesystem, HTTP, git, MCP) |
| What it describes | Website actions/tools | Knowledge resources and their metadata |
| Who consumes it | Browser-based AI agents | Any AI agent, any framework |
| Auth model | Inherits browser session | Declares auth requirements (OAuth 2.1, API key, none) |
| Freshness | Not addressed | First-class field (`validated` per unit) |
| Dependencies | Not addressed | `depends_on`, `supersedes`, typed `relationships` |
| Compliance | Not addressed | `compliance` block (GDPR, NIS2, HIPAA, data residency) |
| Delegation | Not addressed | `delegation` block (max_depth, attenuation, audit chain, HITL) |
| Token budget | Not addressed | `hints` block (token estimates, load strategy, density) |

### 3.4 Discoverability

Both protocols have a discoverability challenge, but in different shapes:

**WebMCP:** An agent arriving at a website needs to know that WebMCP tools exist. The spec
does not yet define how this discovery happens. The tools are registered in JavaScript, so an
agent must either execute the page or have the browser report available tools. This is an
acknowledged open problem in the spec.

**KCP:** Discovery is well-defined. A `knowledge.yaml` lives at the project root (like
`llms.txt`), can be declared via `llms.txt` header (`> knowledge: /docs/knowledge.yaml`),
or discovered via `/.well-known/kcp.json` for HTTP origins. No JavaScript execution required.

---

## 4. Where They Overlap (If Anywhere)

The overlap is narrow but real: both protocols help AI agents interact with web-hosted
services more effectively than unstructured exploration.

An agent visiting `acme.com` without either protocol must:
1. Parse HTML to guess what the site offers
2. Follow links hoping to find relevant documentation
3. Try clicking buttons to discover functionality

With WebMCP alone, the agent knows what **actions** it can take on the current page.
With KCP alone, the agent knows what **knowledge** the site offers and how to navigate it.
With both, the agent has both the map and the controls.

The protocols do not overlap at the field level. WebMCP has no concept of freshness,
dependencies, compliance, or delegation. KCP has no concept of executable tool registration
or browser-mediated consent. They describe fundamentally different aspects of a service.

---

## 5. The Stacking Story: Using Both on the Same Domain

Consider a real-world service: a cloud platform with an API, documentation, and a web
console. Here is how WebMCP and KCP compose on `platform.example.com`:

### What KCP describes (the knowledge layer)

```yaml
# knowledge.yaml — at the root of the documentation site
kcp_version: "0.10"
project: example-platform
version: 3.0.0
updated: "2026-03-01"

auth:
  methods:
    - type: oauth2
      issuer: "https://auth.example.com"
      scopes: ["docs:read"]

units:
  - id: api-reference
    kind: schema
    path: docs/api/v3/openapi.yaml
    intent: "What endpoints does Example Platform v3 expose?"
    scope: global
    audience: [developer, agent]
    validated: "2026-02-28"
    triggers: [api, endpoints, rest, v3]
    hints:
      token_estimate: 18000
      load_strategy: lazy

  - id: auth-guide
    path: docs/guides/authentication.md
    intent: "How do I authenticate to the Example Platform API?"
    scope: global
    audience: [developer, agent]
    validated: "2026-03-01"
    triggers: [auth, oauth, api key, token]
    hints:
      token_estimate: 3200
      load_strategy: eager
      priority: critical

  - id: billing-policy
    path: docs/legal/billing.md
    intent: "What are the billing terms, usage limits, and refund policies?"
    scope: global
    audience: [human, agent]
    validated: "2026-02-15"
    access: authenticated
    sensitivity: internal
    compliance:
      regulations: [GDPR]

relationships:
  - from: auth-guide
    to: api-reference
    type: enables
```

### What WebMCP describes (the action layer)

```javascript
// On the web console at console.example.com

navigator.modelContext.registerTool({
  name: "create-instance",
  description: "Create a new compute instance in Example Platform",
  inputSchema: {
    type: "object",
    properties: {
      name:   { type: "string", description: "Instance name" },
      region: { type: "string", enum: ["eu-west-1", "us-east-1", "ap-south-1"] },
      size:   { type: "string", enum: ["small", "medium", "large"] }
    },
    required: ["name", "region", "size"]
  },
  async execute(input) {
    return await platformAPI.createInstance(input);
  }
});

navigator.modelContext.registerTool({
  name: "check-billing",
  description: "Check current billing status and usage for this account",
  async execute() {
    return await platformAPI.getBillingStatus();
  }
});
```

### The agent's workflow with both

1. Agent receives task: "Set up a new staging environment on Example Platform."
2. Agent discovers `knowledge.yaml` (via `/.well-known/kcp.json` or root file).
3. KCP tells the agent: load `auth-guide` first (it is `priority: critical`, `load_strategy:
   eager`, and `enables` the API reference). Then load `api-reference` for the endpoint
   schema. Skip `billing-policy` — it is `access: authenticated` and `sensitivity: internal`,
   not needed for this task.
4. Agent now *understands* the platform: auth flow, available endpoints, instance types.
5. Agent opens the web console. WebMCP tools appear: `create-instance`, `check-billing`.
6. Agent calls `create-instance` with the parameters it learned from the API reference.
   The browser prompts the user: "Allow AI to create instance 'staging-01' in eu-west-1?"
7. User approves. Instance is created.

**KCP provided the knowledge.** The agent understood what it was doing before it acted.
**WebMCP provided the action.** The agent executed through a structured, consent-mediated
interface rather than by scraping the web console.

Neither protocol alone delivers this workflow. Together, they cover both halves.

---

## 6. Updated Ecosystem Table

The agentic infrastructure ecosystem as of March 2026, with WebMCP included:

| Standard | Layer | What it describes | Runtime |
|----------|-------|-------------------|---------|
| **llms.txt** | Discovery | "Here are URLs an LLM should read" (flat index) | Static file |
| **AGENTS.md** | Discovery | "Here is how to work in this codebase" (code-specific) | Static file |
| **A2A Agent Cards** | Discovery + Invocation | "Here is what this agent can do and how to call it" | JSON at `/.well-known/agent.json` |
| **MCP** | Communication | "Here are the tools, resources, and prompts available via this server" | Server (stdio/SSE/HTTP) |
| **MCP Registry** | Discovery | "Here is a directory of available MCP servers" | Registry service |
| **WebMCP** | **Action** | **"Here are the actions an agent can perform on this web page"** | **Browser (Chrome 146+)** |
| **KCP** | **Knowledge** | **"Here is what knowledge is available, how to access it, what it costs, how fresh it is, and what trust is needed"** | **Static file (YAML)** |

### The stack diagram

```
┌─────────────────────────────────────────────────────────┐
│  PAYMENTS         x402 · Stripe/OpenAI ACP · Visa TAP  │
├─────────────────────────────────────────────────────────┤
│  AUTHORIZATION    OAuth 2.1 · GNAP · UMA · RAR         │
├─────────────────────────────────────────────────────────┤
│  IDENTITY         SPIFFE · OIDC-A · DID/VC             │
├─────────────────────────────────────────────────────────┤
│  COMMUNICATION    MCP (16K+ servers) · A2A · ANP       │
├─────────────────────────────────────────────────────────┤
│  BROWSER ACTIONS  WebMCP (Chrome 146+)                  │  ← NEW
├─────────────────────────────────────────────────────────┤
│  DISCOVERY        Agent Cards · MCP Registry · llms.txt │
├─────────────────────────────────────────────────────────┤
│  KNOWLEDGE        KCP (knowledge.yaml)                  │  ← This repo
├─────────────────────────────────────────────────────────┤
│  INFRA            AgentGateway · HTTP/SSE/gRPC          │
└─────────────────────────────────────────────────────────┘
```

WebMCP sits at the browser-action layer — between communication protocols (MCP/A2A) and
the authorization layer. KCP sits at the knowledge layer — between infrastructure and
discovery. They occupy different positions in the stack.

---

## 7. Decision Guide

### Use KCP when:

- You have **documentation, specs, policies, or reference material** that agents need to
  navigate
- You need agents to understand **what depends on what** before loading content
- **Freshness matters** — you need agents to know when knowledge was last validated
- You need to declare **access requirements** (auth, compliance, data residency) on
  knowledge resources without building a runtime server
- Your agents are **not browser-based** — CLI tools, server-side agents, code assistants,
  CI/CD pipelines
- You want a single manifest that works across **multiple agent frameworks** (Claude Code,
  Copilot, ChatGPT, AutoGen, CrewAI, smolagents, OpenCode)
- You need **compliance metadata** (GDPR, NIS2, HIPAA) declared at the resource level
- You need **delegation constraints** for multi-agent knowledge access

### Use WebMCP when:

- You have a **website with interactive functionality** that agents should be able to invoke
- Your use case is **browser-based** — the agent operates within a browser session
- You want the **browser to mediate** between the agent and your website (consent prompts,
  session inheritance)
- You are replacing **screenshot-based or DOM-scraping** browser automation
- The actions you are exposing are **transactional** — booking, purchasing, form submission,
  account management

### Use both when:

- Your service has **both knowledge and actions** — documentation that agents should read
  *and* a web interface where agents should act
- You want agents to **understand before they act** — read the API docs (KCP), then call
  the API via the console (WebMCP)
- You are building a **full-stack agentic experience** where server-side agents use KCP to
  navigate knowledge and browser-based agents use WebMCP to execute workflows
- You want **different agent types** to interact with the same service — a CLI agent reads
  the `knowledge.yaml`, a browser agent uses the WebMCP tools, both serve the same user

---

## 8. What About MCP?

A common source of confusion: WebMCP has "MCP" in its name. How does it relate to
Anthropic's Model Context Protocol?

**MCP** (Anthropic) is a protocol for connecting agents to **backend tools and resources**
via a server process. An MCP server exposes tools (functions), resources (data), and prompts.
Agents connect over stdio, SSE, or HTTP. MCP is transport-agnostic but requires a running
server.

**WebMCP** (Google + Microsoft) extends the MCP concept to the **browser**. Instead of a
backend server, the website itself registers tools via a browser API. The WebMCP spec
explicitly states: "WebMCP works with existing protocols like MCP and is not a replacement."

**KCP** (Cantara) is not a protocol at all in the transport sense. It is a **metadata
format** — a YAML file that describes knowledge resources. KCP works *with* MCP (the
KCP-MCP bridge exposes knowledge units as MCP resources) but does not depend on it.

The three compose cleanly:

```
MCP server  →  exposes tools and KCP-described knowledge to backend agents
WebMCP      →  exposes tools to browser-based agents via the browser API
KCP         →  describes what knowledge exists, for any agent, via any transport
```

No two of these replace each other.

---

## 9. Maturity Comparison

Honesty requires noting where each standard is in its lifecycle:

| Dimension | WebMCP | KCP |
|-----------|--------|-----|
| **First published** | August 2025 | February 2026 |
| **Current version** | Draft CG Report (5 March 2026) | v0.10 draft (13 March 2026) |
| **Spec completeness** | Imperative API defined; declarative API, security, privacy, accessibility marked TODO | Core spec complete (incl. federation in v0.9, version pinning and query vocabulary in v0.10); content integrity, payment details in RFCs |
| **Standards body** | W3C Web ML Community Group | Submitted to AAIF (Linux Foundation); IANA well-known URI submitted |
| **Implementations** | Chrome 146 Canary (behind flag) | Parsers in Python, Java, TypeScript; 268 conformance tests; MCP bridge |
| **Corporate backing** | Google + Microsoft | eXOReaction / Cantara (independent) |
| **Browser support** | Chrome only (Edge expected) | N/A (not browser-dependent) |
| **Production use** | Not yet (early preview) | Early production (Synthesis, wiki.totto.org, wiki.cantara.no) |

Both are early-stage. Neither should be treated as stable for production systems that cannot
tolerate spec changes. Both are worth tracking and experimenting with now.

---

## 10. Frequently Asked Questions

**Q: Does WebMCP make KCP unnecessary?**

No. WebMCP describes actions on web pages. KCP describes knowledge resources. A website's
documentation, API reference, and policy documents are not "actions" — they are knowledge.
WebMCP has no mechanism for declaring freshness, dependencies, access requirements, compliance
constraints, or delegation rules on knowledge resources. These are different problems.

**Q: Does KCP make WebMCP unnecessary?**

No. KCP describes knowledge; it does not execute anything. An agent that has read your API
documentation via KCP still needs a way to *act* — call an endpoint, submit a form, complete
a checkout. If that action happens in a browser, WebMCP provides the structured interface.

**Q: Will they converge?**

Unlikely, because they address different categories. The same way that OpenAPI (describes
APIs) did not converge with OAuth (handles auth) — they compose rather than merge. WebMCP
may eventually reference KCP manifests for pre-action knowledge loading, or KCP manifests
may reference WebMCP tool endpoints. But the protocols themselves solve different problems
and will likely remain separate specifications.

**Q: I am building an MCP server. Which do I need?**

KCP, probably. An MCP server serves tools and resources to agents. If those resources include
knowledge (documentation, policies, schemas), a `knowledge.yaml` manifest lets agents
navigate that knowledge efficiently. WebMCP is relevant only if your agents interact with
websites via a browser.

**Q: I am building a browser extension for AI. Which do I need?**

WebMCP for the action layer (letting the agent interact with web pages). Consider KCP if the
extension also needs to navigate structured knowledge from documentation sites — the manifest
can be fetched via HTTP and parsed without a browser API.

**Q: What if I only have five minutes?**

Add a `knowledge.yaml` with five fields per unit to your documentation. That is Level 1 KCP
and takes five minutes. WebMCP requires JavaScript integration on your website and a
Chromium-based browser — it is a larger investment. Start where the return is highest for
your use case.

---

## 11. Further Reading

**WebMCP:**
- [W3C Specification](https://webmachinelearning.github.io/webmcp/) — normative draft
- [GitHub repository](https://github.com/webmachinelearning/webmcp) — issues and development
- [Chrome developer blog](https://developer.chrome.com/blog/webmcp-epp) — early preview announcement
- [VentureBeat coverage](https://venturebeat.com/infrastructure/google-chrome-ships-webmcp-in-early-preview-turning-every-website-into-a/) — industry context

**KCP:**
- [SPEC.md](../../SPEC.md) — normative specification (v0.10)
- [PROPOSAL.md](../../PROPOSAL.md) — the case for a knowledge architecture standard
- [README.md](../../README.md) — overview, comparison with MCP and llms.txt
- [RFC-0001](../../RFC-0001-KCP-Extended.md) — ecosystem positioning and extended capabilities
- [Adoption guide](../../guides/adopting-kcp-in-existing-projects.md) — add KCP in 5 minutes
- [A2A + KCP composability](../../examples/a2a-agent-card/) — another composability example

**Ecosystem context:**
- [MCP specification](https://modelcontextprotocol.io) — Model Context Protocol (Anthropic)
- [A2A specification](https://google.github.io/A2A/) — Agent-to-Agent protocol (Google)
- [llms.txt](https://llmstxt.org) — flat knowledge index standard
- [AGENTS.md](https://github.com/anthropics/agents-md) — codebase agent instructions

---

*This document was written to answer a community question: "WebMCP appeared — have you done
comparisons?" The answer is: they are complementary standards solving different problems at
different layers. Use the one that matches your problem. Use both if your service has both
actions and knowledge.*

*Last updated: March 2026*
