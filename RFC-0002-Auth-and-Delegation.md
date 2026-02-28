# RFC-0002: Auth and Delegation Metadata

**Status:** Request for Comments
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-02-28
**Supersedes:** Proposals A and B in [RFC-0001](./RFC-0001-KCP-Extended.md)
**Issues:** [#1 (auth)](https://github.com/Cantara/knowledge-context-protocol/issues/1) · [#3 (delegation)](https://github.com/Cantara/knowledge-context-protocol/issues/3)
**Discussion:** [GitHub Issues](https://github.com/Cantara/knowledge-context-protocol/issues)
**Spec:** [SPEC.md](./SPEC.md) (current: v0.3)

---

## What This RFC Proposes

Two additions to the KCP manifest format:

1. **Unit-level `access` field** — a lightweight advisory signal: is this unit public, authenticated, or restricted?
2. **Root-level `auth` block** — a structured description of how to authenticate to the knowledge source.
3. **Root-level `delegation` block** — constraints on multi-agent delegation chains accessing this manifest.

These fields are **advisory declarations**, not enforcement mechanisms — consistent with KCP's philosophy throughout (see SPEC.md §12.1).

---

## The Problem

KCP currently has no way to describe how a knowledge resource is protected.

When an agent discovers a `knowledge.yaml` manifest and begins loading units, it has no signal about which units require credentials, what kind of credentials, or how deep a delegation chain is permitted. This creates two failure modes:

- **Silent failure**: the agent attempts to load a restricted unit, gets a 401, and cannot proceed — with no indication of what credential to acquire.
- **Over-permissive access**: the agent loads content it should not have access to, because the transport layer did not enforce restrictions.

As KCP manifests are increasingly served via MCP servers, HTTP endpoints, and agentic discovery protocols, the absence of auth metadata becomes a gap that every implementer fills differently.

---

## The Standards Landscape (February 2026)

The agentic infrastructure ecosystem has largely converged on a baseline:

- **OAuth 2.1** — the minimum viable auth that every platform supports. MCP requires it as of November 2025. A2A supports it. All major cloud platforms implement it.
- **Cross App Access (XAA)** — now part of MCP ("Enterprise-Managed Authorization"). Tracks and revokes delegation lineage. Built on the ID-JAG spec.
- **OAuth Token Exchange (RFC 8693)** — agents convert session tokens into short-lived, scoped credentials per delegation hop.
- **DPoP (RFC 9449)** — sender-constrained tokens that bind credentials to the requesting client.
- **HTTP Message Signatures (RFC 9421)** — used by Visa TAP and Mastercard Agent Pay for non-OAuth agent identity.
- **SPIFFE/SPIRE** — workload identity for non-human actors in zero-trust environments.
- **OIDC-A** — proposed standard claims for delegation chain representation.
- **W3C Verifiable Credentials 2.0** — decentralised identity for agents, active in Feb 2026.

The NIST NCCoE launched its AI Agent Standards Initiative in February 2026 with a concept paper explicitly on agent identity and authorization. The comment period is open until April 2, 2026. Aligning KCP with NIST guidance positions it for enterprise and government adoption.

**No single standard has won.** RFC-0002 deliberately supports multiple auth schemes rather than betting on one.

---

## Proposal 1: Unit-level `access` field

A lightweight advisory field on knowledge units. Three values cover the practical cases:

```yaml
units:
  - id: public-overview
    path: README.md
    intent: "What is this project?"
    scope: global
    audience: [human, agent]
    # access omitted = "public" by default

  - id: internal-runbook
    path: ops/runbook.md
    intent: "How do I handle a production incident?"
    scope: project
    audience: [operator, agent]
    access: authenticated        # any valid credential for this source

  - id: sensitive-customer-data
    path: reports/customer-analysis.md
    intent: "What does the customer cohort analysis show?"
    scope: module
    audience: [agent]
    access: restricted
    auth_scope: data-team        # named scope or role required
```

### `access` values

| Value | Meaning |
|-------|---------|
| `public` | No credentials required. Default if `access` is omitted. |
| `authenticated` | Any valid credential accepted by the source. The `auth` block (see Proposal 2) describes how to acquire one. |
| `restricted` | Specific scope or role required. Declare it in `auth_scope`. |

### `auth_scope` field

An optional companion to `access: restricted`. A free-form string naming the scope, role, or group required. Examples: `"ops-team"`, `"read:internal"`, `"cn=data-analysts"`. Parsers treat it as an opaque advisory string — no validation of the value itself.

### Conformance implications

- `access` is **OPTIONAL**. Omitting it means `public`.
- Parsers MUST NOT reject a manifest because `access` is absent on any unit.
- Unknown `access` values SHOULD produce a validation warning and MUST be treated as `restricted` by cautious agents.

---

## Proposal 2: Root-level `auth` block

A structured description of how to authenticate to the knowledge source. Relevant when the manifest is served via a KCP-aware MCP server, an HTTP endpoint, or a federated discovery registry.

```yaml
auth:                           # OPTIONAL root-level block
  methods:
    - type: none                # public access, no credentials

    - type: oauth2
      flow: client_credentials  # client_credentials | authorization_code | device_code
      token_endpoint: "https://auth.example.com/token"
      authorization_endpoint: "https://auth.example.com/authorize"  # for auth_code/device flows
      scopes: ["read:knowledge"]
      resource: "https://example.com/knowledge"   # RFC 8707 resource indicator

    - type: api_key
      header: "X-API-Key"
      registration_url: "https://example.com/register"

    - type: bearer_token
      token_endpoint: "https://auth.example.com/token"

    - type: spiffe
      trust_domain: "example.com"

    - type: did
      supported_methods: ["did:web", "did:key"]
      required_vc_types: ["OrganizationMembership"]   # optional VC type constraint

    - type: http_signature      # RFC 9421 — used by Visa TAP, Mastercard Agent Pay
      algorithm: "ecdsa-p256-sha256"
      key_id_header: "Signature-Input"
```

### How agents use the `auth` block

1. Agent finds a manifest and inspects units for `access` values.
2. Any unit with `access: authenticated` or `access: restricted` triggers credential acquisition.
3. Agent reads `auth.methods` to determine how to acquire credentials.
4. Agent selects the method it supports and proceeds.
5. If no supported method exists, agent SHOULD surface this to its operator rather than silently failing.

### Multiple methods

The `methods` list allows the publisher to declare multiple supported auth schemes in preference order. Agents try methods in order until one succeeds. This enables graceful degradation across environments.

### `auth` block at unit level (optional extension)

For multi-tenant or per-section auth, the `auth` block MAY appear on individual units to override the root-level block for that unit:

```yaml
units:
  - id: partner-api-docs
    path: partners/api.md
    intent: "How do partner integrations authenticate?"
    access: restricted
    auth_scope: partner-portal
    auth:
      methods:
        - type: oauth2
          flow: client_credentials
          token_endpoint: "https://partners.example.com/token"
          scopes: ["partner:read"]
```

---

## Proposal 3: Root-level `delegation` block

As AI agents operate in multi-hop chains — a human authorizes an orchestrator, which delegates to a sub-agent, which accesses KCP content — manifests need a way to express constraints on that chain.

```yaml
delegation:                          # OPTIONAL root-level block
  max_depth: 3
  # Maximum number of hops from a human to this resource.
  # 1 = direct human access or one directly-authorized agent only.
  # 0 = no agents — humans only.
  # Omit = no constraint.

  require_capability_attenuation: true
  # Each hop in the delegation chain MUST narrow (not expand) permissions.
  # Agents SHOULD reject delegation tokens that grant more than the parent granted.
  # Based on Auth0 FGA / Google Zanzibar attenuation model.

  require_delegation_proof: false
  # If true, agents MUST present a verifiable delegation chain
  # (e.g. XAA lineage token, OIDC-A chain claim) when accessing.
  # Agents that cannot present proof MUST NOT access restricted units.

  human_in_the_loop:
    required: false
    # If true, a human approval step is required before agent access.
    approval_mechanism: oauth_consent   # uma | oauth_consent | custom
    # uma: UMA 2.0 asynchronous policy (resource owner sets policy in advance)
    # oauth_consent: standard OAuth authorization code flow with user present
    # custom: described at docs_url
    docs_url: "https://..."             # required if approval_mechanism is "custom"

  audit_chain: true
  # If true, agents MUST include W3C Trace Context headers (traceparent/tracestate)
  # so the full delegation chain can be reconstructed from access logs.
  # Compatible with OpenTelemetry. The lightest enforcement option.
```

### Known attacks this addresses

| Attack | Mitigation |
|--------|-----------|
| Agent Session Smuggling | `max_depth` limits chain length |
| Cross-Agent Privilege Escalation | `require_capability_attenuation` |
| EchoLeak (CVE-2025-32711, CVSS 9.3) | `audit_chain` + `require_delegation_proof` |
| Unauthorized autonomous access | `human_in_the_loop: required: true` |

### Per-unit delegation constraints

The `delegation` block MAY appear on individual units to tighten constraints for sensitive content:

```yaml
units:
  - id: patient-data-summary
    path: reports/patient-cohort.md
    intent: "What does the patient cohort analysis show?"
    access: restricted
    auth_scope: clinical-staff
    delegation:
      max_depth: 1
      human_in_the_loop:
        required: true
        approval_mechanism: oauth_consent
```

---

## Complete Example

A knowledge manifest for an internal documentation system with mixed access levels:

```yaml
kcp_version: "0.3"
project: internal-docs.example.com
version: 2.0.0
updated: "2026-02-28"
language: "en"
license: "proprietary"

auth:
  methods:
    - type: oauth2
      flow: authorization_code
      token_endpoint: "https://auth.example.com/token"
      authorization_endpoint: "https://auth.example.com/authorize"
      scopes: ["read:docs"]
      resource: "https://internal-docs.example.com"
    - type: spiffe
      trust_domain: "example.com"

delegation:
  max_depth: 3
  require_capability_attenuation: true
  audit_chain: true

units:
  - id: overview
    path: README.md
    intent: "What does this documentation system cover?"
    scope: global
    audience: [human, agent]
    access: public

  - id: architecture
    path: docs/architecture.md
    intent: "How is the internal platform architected?"
    scope: global
    audience: [developer, architect, agent]
    access: authenticated

  - id: incident-runbook
    path: ops/runbook.md
    intent: "How do I respond to a production incident?"
    scope: project
    audience: [operator, agent]
    access: restricted
    auth_scope: on-call-team

  - id: patient-cohort-analysis
    path: reports/patient-cohort.md
    intent: "What does the Q1 patient cohort analysis show?"
    scope: module
    audience: [agent]
    access: restricted
    auth_scope: clinical-staff
    delegation:
      max_depth: 1
      require_delegation_proof: true
      human_in_the_loop:
        required: true
        approval_mechanism: oauth_consent
```

---

## Design Principles

**1. Advisory, not enforcement.** KCP declares requirements; the transport and storage layers enforce them. An agent reading `access: restricted` without a credential SHOULD NOT attempt to load the unit. But KCP cannot prevent it. This is consistent with how `audience`, `validated`, and `indexing` work throughout the spec (see SPEC.md §12.1).

**2. Omission means public.** An `access` field absent from a unit means `public`. A `delegation` block absent from the manifest means no delegation constraints. This ensures backward compatibility: every existing manifest remains valid and implicitly public.

**3. Multi-scheme auth.** The identity landscape is fragmented. Agents operate across environments that may support OAuth 2.1, SPIFFE, DIDs, or API keys. The `auth.methods` list lets publishers declare multiple supported schemes; agents pick the one they support.

**4. OAuth 2.1 is the baseline.** Of the supported types, `oauth2` is the one every compliant implementation SHOULD support. Other types (SPIFFE, DID, HTTP signatures) are valuable but optional.

**5. `delegation` is the heavier mechanism.** The `access` field is a five-minute adoption — one word per unit. The `auth` block is an hour of work. The `delegation` block is a day of work, appropriate for regulated or high-stakes deployments.

**6. Forward compatibility.** Unknown `auth.methods[].type` values MUST be silently ignored (per SPEC.md §7). An agent that does not support SPIFFE skips that method and tries the next. Unknown `delegation` fields are silently ignored.

---

## Conformance Level Implications

This RFC proposes the following additions to SPEC.md §8:

- **Level 2**: `access` field on units (advisory access classification)
- **Level 3**: `auth` block (full authentication description)
- **Level 3**: `delegation` block (multi-agent delegation constraints)

The `auth_scope` field accompanies `access: restricted` and is also Level 3.

---

## Open Questions

We are asking for community input on the following:

**1. Should `access` have more granularity?**
The three-value model (`public` / `authenticated` / `restricted`) covers most cases but may be too coarse. Should `confidential` or `internal` be distinct values? Or is the combination of `access: restricted` + `auth_scope` sufficient?

**2. Should `auth` be required when any unit has `access: authenticated`?**
Currently both fields are optional and independent. A stricter rule: if any unit declares `access: authenticated` or `access: restricted`, the manifest SHOULD include an `auth` block. Too prescriptive, or useful guidance?

**3. Is `delegation.max_depth` the right abstraction?**
An integer hop count is simple but coarse. Should there be named delegation roles (`orchestrator`, `sub-agent`, `tool`) instead of or alongside depth? Or is depth enough for the advisory model?

**4. How should `human_in_the_loop` interact with fully autonomous agents?**
`required: true` means a human approval step is required. But some deployments have no interactive human available. Should there be a machine-interpretable signal for asynchronous approval (UMA policy) vs. synchronous consent?

**5. Should `auth` appear at the unit level or only at the root?**
The proposal allows per-unit `auth` overrides for multi-tenant scenarios. This adds complexity. Is there a real use case that requires per-unit auth blocks, or is the root-level block sufficient?

**6. OAuth 2.1 flows: which are in scope?**
The proposal supports `client_credentials`, `authorization_code`, and `device_code`. Should `token_exchange` (RFC 8693) be a named flow, given its importance for agent delegation chains?

---

## References

- [MCP Authorization Specification](https://modelcontextprotocol.io/specification/draft/basic/authorization) — OAuth 2.1 as baseline
- [IETF draft-oauth-ai-agents-on-behalf-of-user-00](https://datatracker.ietf.org/doc/html/draft-oauth-ai-agents-on-behalf-of-user-00) — agent delegation via OAuth
- [NIST NCCoE Concept Paper: AI Agent Identity and Authorization](https://www.nccoe.nist.gov/projects/software-and-ai-agent-identity-and-authorization) — Feb 2026, comment period open
- [RFC 8693: OAuth 2.0 Token Exchange](https://datatracker.ietf.org/doc/html/rfc8693) — agent-to-agent delegation
- [RFC 9449: DPoP](https://datatracker.ietf.org/doc/html/rfc9449) — sender-constrained tokens
- [RFC 9421: HTTP Message Signatures](https://datatracker.ietf.org/doc/html/rfc9421) — used by Visa TAP, Mastercard Agent Pay
- [RFC 8707: Resource Indicators for OAuth 2.0](https://datatracker.ietf.org/doc/html/rfc8707) — scoping tokens to specific resources
- [SPIFFE/SPIRE for Agentic AI](https://www.hashicorp.com/en/blog/spiffe-securing-the-identity-of-agentic-ai-and-non-human-actors) — workload identity
- [W3C Verifiable Credentials 2.0](https://www.w3.org/press-releases/2025/verifiable-credentials-2-0/) — decentralised identity
- [Okta: Control the Chain](https://www.okta.com/blog/ai/agent-security-delegation-chain/) — XAA, delegation lineage
- [OIDC-A Proposal](https://subramanya.ai/2025/04/28/oidc-a-proposal/) — delegation chain claims in OIDC
- [UMA 2.0](https://docs.kantarainitiative.org/uma/wg/rec-oauth-uma-grant-2.0.html) — async resource owner authorization
- [Auth0 FGA / Google Zanzibar](https://auth0.com/fine-grained-authorization) — capability attenuation model
- CVE-2025-32711 (EchoLeak, CVSS 9.3) — cross-agent data exfiltration via prompt injection

---

## How to Participate

- **Comment on [Issue #1](https://github.com/Cantara/knowledge-context-protocol/issues/1)** for auth metadata feedback
- **Comment on [Issue #3](https://github.com/Cantara/knowledge-context-protocol/issues/3)** for delegation feedback
- **Open a new issue** if you have a use case this does not cover
- **Submit a PR** to this document if you have concrete improvements to the field definitions

This RFC will inform the v0.4 spec. No implementation timeline is set — the design needs community validation first.

---

*Knowledge Context Protocol — proposed by [eXOReaction AS](https://www.exoreaction.com), Oslo, Norway.*
