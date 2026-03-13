# Public Comment: NCCoE Concept Paper
## "Accelerating the Adoption of Software and AI Agent Identity and Authorization"
### Submitted by: Knowledge Context Protocol (KCP) Project — Cantara / Thor Henning Hetland
### Email: totto@cantara.no
### Date: March 2026
### Submitted to: AI-Identity@nist.gov

---

## About the Submitter

The Knowledge Context Protocol (KCP) is an open specification for declaring what knowledge resources an AI agent may access, how to access them, and under what constraints. The project is maintained by Cantara, an open-source organization, and developed by Thor Henning Hetland (Oslo, Norway), a software architect and practitioner with 30+ years of enterprise architecture experience, founder of JavaZone (Norway's largest software conference), and former board member of IASA (International Association of Software Architects). The KCP specification, parsers (Python, Java, TypeScript), and MCP bridge implementations are available at https://github.com/cantara/knowledge-context-protocol.

KCP v0.10 is a draft specification with working implementations in three languages, a three-language conformance test suite (537 tests passing), and adoption in enterprise knowledge infrastructure tooling. It has been submitted for consideration to the Agentic AI Foundation (Linux Foundation) alongside MCP, AGENTS.md, and goose.

---

## Executive Summary

This comment introduces the Knowledge Context Protocol (KCP) as a resource-side metadata standard that directly addresses several gaps identified in the concept paper. Where the concept paper focuses on how agents identify and authorize *themselves*, KCP addresses the complementary problem: how knowledge resources declare their own access requirements, trust expectations, and delegation constraints to agents.

KCP is not an identity standard — it is a *resource declaration* standard. It works alongside identity infrastructure (OAuth 2.1, SPIFFE, API keys) rather than replacing it. We believe NIST guidance in this area should address both sides of the agent–resource interaction: agent identity (the focus of the concept paper) and resource metadata (the gap KCP fills).

**Concrete ask:** We recommend that the NCCoE lab demonstration include a resource-side metadata component alongside agent identity — specifically, demonstrating agents performing pre-flight authorization checks against resource manifests before accessing knowledge resources.

---

## Response to Specific Areas

### 1. Use Cases

Organizations deploying agentic AI systems face a recurring problem: agents must determine *which* knowledge sources to consult, *whether* they are authorized to access them, and *how fresh* the information is — before making a decision. Without a standard for resource metadata, agents either receive overly broad access (all documents in a repository) or must rely on human-curated context injection, which does not scale.

KCP has been designed for and demonstrated in the following deployment patterns:

- **Enterprise knowledge bases**: A `knowledge.yaml` manifest declares 50–200 knowledge units (API docs, architecture decisions, compliance policies) with audience targeting (`agent` vs `human`), sensitivity levels, and access requirements. The MCP bridge exposes these as MCP resources, allowing Claude Code, GitHub Copilot, and other MCP-compatible agents to discover and retrieve only what they are authorized to read.
- **Regulatory compliance contexts**: Organizations handling GDPR, NIS2, HIPAA, and FedRAMP-bounded data can use KCP's `compliance` block to declare data residency constraints, applicable regulations, and access restrictions directly in the manifest, so agents can apply jurisdiction-aware filtering without querying a separate policy engine.
- **Multi-agent delegation chains**: Multi-agent workflows where a root agent spawns sub-agents use KCP's `delegation` block to enforce depth limits and human-in-the-loop approval requirements at the resource level, independent of the orchestration framework.

---

### 2. Challenges

**The resource-side gap in current agent identity standards:**

Current identity and authorization standards (OAuth 2.0/2.1, SCIM, SPIFFE/SVID) were designed for human-facing or service-to-service authentication. They address *who the caller is* but not *what the resource requires from the caller* in machine-readable, agent-consumable form.

For AI agents, this creates a specific class of problem: an agent may hold a valid OAuth token but have no way to determine, without executing a request and receiving a 403, whether a particular knowledge resource is appropriate to access. This trial-and-error approach generates unnecessary audit log noise, increases latency, and creates opportunities for information leakage.

A second challenge is **information-driven scope expansion**. Practitioners deploying agentic systems observe that agents without structured knowledge of what resources are available and accessible tend to expand their retrieval scope opportunistically — following links, querying adjacent systems, and accessing resources outside their intended boundary. This is not misalignment; it is rational behavior under information uncertainty. Providing agents with a precise, upfront declaration of *what knowledge exists and is accessible to them* reduces the conditions that drive this behavior. Structured resource manifests address the information gap directly; they do not merely constrain agents after the fact.

**Alignment with Zero Trust Architecture:** KCP's "resource declares its own requirements" model aligns naturally with NIST SP 800-207 (Zero Trust Architecture). ZTA requires that access decisions be made dynamically and that resources be treated as untrusted regardless of network position. KCP extends this principle to the resource side: each knowledge resource declares its trust requirements in a machine-readable manifest, enabling agents to apply ZTA-compliant access decisions before making contact.

---

### 3. Standards

**KCP as a resource-side complement to agent-side identity standards:**

Just as OpenAPI lets REST APIs describe themselves to developers, KCP lets knowledge resources describe themselves to agents. The analogy is precise: OpenAPI is a machine-readable declaration of *what an API does and how to call it*; KCP is a machine-readable declaration of *what knowledge is available, who may access it, and under what constraints*.

The current standards landscape for agentic AI can be organized along two axes:

| Layer | Agent-side | Resource-side |
|-------|-----------|---------------|
| Identity | OAuth 2.1, SPIFFE/SVID, DID | *(gap)* |
| Discovery | Agent Cards (A2A), MCP Registry | `llms.txt`, `AGENTS.md`, **KCP** |
| Access requirements | RBAC, ABAC, OPA | *(gap)* **→ KCP** |
| Audit | W3C Trace Context, OpenTelemetry | *(gap)* **→ KCP `trust.audit`** |

KCP fills the resource-side gap in Discovery and Access requirements, and partially in Audit. It does not replace enforcement mechanisms (RBAC/OPA enforce access; KCP *declares* requirements) but provides the machine-readable pre-flight metadata that makes enforcement decisions faster and more precise.

**Authorization requirements declaration (`auth` block, §3.3):**
```yaml
auth:
  methods:
    - type: oauth2
      issuer: https://auth.example.com
      scopes: [knowledge:read]
    - type: api_key
      header: X-API-Key
```
An agent holding an OAuth 2.1 token can inspect this block *before* making a request to determine whether its credential type is accepted and which scopes are required. This enables pre-flight authorization checks that do not require a round-trip to the resource.

**Per-unit access and compliance declaration (`access`, `auth_scope`, `compliance` fields, §4.11 and §3.5):**
```yaml
units:
  - id: employee-compensation-policy
    access: restricted
    auth_scope: hr:compensation:read
    sensitivity: confidential
    compliance:
      data_residency: [US]
      regulations: [HIPAA]
      restrictions: [no-train]
```
Each knowledge unit can declare its access level, required authorization scope, data residency constraints, applicable regulations, and usage restrictions. This allows agents to apply least-privilege access at the individual document level and respect jurisdictional constraints without consulting a separate policy engine. The `no-train` restriction explicitly signals that the resource content must not be used for model training — directly relevant to emerging AI governance requirements including FISMA and FedRAMP AI overlays.

**Delegation constraints (`delegation` block, §3.4):**
```yaml
delegation:
  max_depth: 2
  require_capability_attenuation: true
  audit_chain: true
  human_in_the_loop:
    required: true
    approval_mechanism: oauth_consent
```
The `delegation` block enforces constraints on sub-agent delegation directly in the resource manifest:
- `max_depth`: limits how many levels deep an agent may re-delegate access
- `require_capability_attenuation`: each delegation step must reduce, not expand, the granted capabilities — implementing least privilege across the delegation chain
- `audit_chain`: requires a complete chain of custody to be maintained across all delegation hops
- `human_in_the_loop`: requires human approval (via OAuth consent, UMA, or custom mechanism) before an agent may act on this resource

These constraints operate at the resource level. A sub-agent cannot exceed the delegation constraints declared by the knowledge resource, regardless of what the orchestrating agent grants. This provides a resource-enforced backstop independent of orchestration framework behavior.

The `trust.audit` block (§3.2) provides two resource-declared audit obligations: `agent_must_log: true` asserts that any agent accessing this resource is expected to record the access in its audit log; `require_trace_context: true` requires the agent to propagate a W3C Trace Context header (W3C Trace Context Recommendation, https://www.w3.org/TR/trace-context/), enabling end-to-end traceability across multi-agent workflows. These are not enforcement mechanisms but explicit, machine-readable obligations that conformant agent frameworks can honor — and that NIST could formalize as a best practice pattern.

---

### 4. Technologies

KCP v0.10 (draft) ships with:
- **Three parser implementations**: Python, Java (JVM), TypeScript — all open source, MIT licensed, conformance-tested
- **Three MCP bridge implementations**: TypeScript (`kcp-mcp` npm), Python (`kcp-mcp` pip), Java (`no.cantara.kcp:kcp-mcp` Maven) — expose knowledge units as MCP resources to any MCP-compatible agent
- **Conformance test suite**: Three-language runner, 268 tests passing, ensures cross-implementation interoperability
- **`/.well-known/kcp.json` endpoint**: Auto-discovery of KCP manifests on any domain (IANA registration submitted March 2026)
- **GitHub Copilot integration**: `--generate-all` flag produces static Copilot instructions from a KCP manifest

The MCP bridge is the primary integration point with current agent infrastructure. Since MCP is supported by Claude (Anthropic), GitHub Copilot (Microsoft), ChatGPT (OpenAI), Gemini (Google), and all major AI IDEs, KCP manifests are immediately accessible to the dominant commercial agent platforms without custom integration.

---

### 5. Technical Controls

**On agent identification:** KCP does not address agent identity itself but provides the resource-side declaration (`auth.methods`) that enables agents to present the correct credential type. NIST guidance should address both sides: standards for how agents assert identity *and* standards for how resources declare what identity they require.

**On authorization:** The `access` + `auth_scope` + `auth.methods` combination provides a machine-readable pre-flight authorization check. We recommend NIST guidance include a requirement that knowledge resources expose their authorization requirements in a standard, agent-consumable format before the agent attempts access.

**On auditing and non-repudiation:** `trust.audit.agent_must_log` and `trust.audit.require_trace_context` establish resource-declared audit obligations. The W3C Trace Context Recommendation provides the technical foundation for cross-agent trace propagation. NIST could formalize the pattern of "resource-declared audit obligations" as a best practice for enterprise agentic deployments.

**On delegation depth and capability attenuation:** `delegation.max_depth` and `delegation.require_capability_attenuation` implement the principle of least privilege across delegation chains. We recommend NIST guidance include delegation depth limits and capability attenuation as mandatory controls for multi-agent deployments in high-sensitivity environments.

**On prompt injection:** Providing agents with a precise, structured declaration of accessible resources reduces opportunistic information foraging — a key vector for prompt injection payloads embedded in unexpected content. When an agent knows exactly what to retrieve (from a `knowledge.yaml` manifest), it is less likely to follow attacker-controlled links or expand its retrieval scope. This is a defense-in-depth measure, not a primary control.

---

## Recommendations for NIST Guidance

1. **Address both sides of the agent–resource interaction.** Current proposals focus on agent identity. Equal attention should be given to how resources declare their requirements to agents. A standard for resource-side metadata (analogous to OpenAPI for REST APIs) is missing from the landscape.

2. **Standardize resource-declared authorization requirements.** Agents should be able to perform a pre-flight authorization check against a machine-readable resource manifest before attempting access. This reduces trial-and-error 403 patterns and limits information leakage.

3. **Standardize delegation constraints at the resource level.** Multi-agent systems need a way to express depth limits, capability attenuation requirements, and human-in-the-loop obligations at the resource level, independent of the orchestration framework. This should be included in any NIST agent authorization framework.

4. **Adopt W3C Trace Context as the baseline for agent audit trails.** The `require_trace_context` pattern — where a resource declares that accessing agents must propagate a trace context — provides a lightweight, standards-based foundation for cross-agent auditability without requiring a new protocol.

5. **Include resource-side metadata in the NCCoE lab demonstration.** We recommend that the lab demonstration include a scenario where agents perform pre-flight authorization checks against resource manifests (e.g., a `knowledge.yaml` or equivalent) before accessing knowledge resources. This would produce concrete, reusable guidance for enterprise implementers and directly address the resource-side gap identified in this comment.

---

## Conclusion

The concept paper identifies a genuine and urgent problem. The primary gap we see in the current framing is the absence of resource-side standards: how knowledge and data resources communicate their access requirements, delegation constraints, and audit obligations to agents. KCP was built to address exactly this gap, and it aligns directly with NIST SP 800-207 Zero Trust principles applied to the resource side of the agent–resource interaction.

We welcome further engagement with the NCCoE team and offer to present the KCP specification and reference implementations as input to the project's lab demonstration phase.

**Contact:** Thor Henning Hetland, totto@cantara.no
**Repository:** https://github.com/cantara/knowledge-context-protocol
**Specification:** https://cantara.github.io/knowledge-context-protocol/
