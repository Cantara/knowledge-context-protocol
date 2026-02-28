# RFC-0004: Trust, Provenance, and Compliance Metadata

**Status:** Request for Comments
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-02-28
**Supersedes:** Proposals E and G in [RFC-0001](./RFC-0001-KCP-Extended.md)
**Issues:** [#5 (trust/audit)](https://github.com/Cantara/knowledge-context-protocol/issues/5) · [#11 (compliance)](https://github.com/Cantara/knowledge-context-protocol/issues/11)
**Discussion:** [GitHub Issues](https://github.com/Cantara/knowledge-context-protocol/issues)
**Spec:** [SPEC.md](./SPEC.md) (current: v0.3)
**Related:** [RFC-0002](./RFC-0002-Auth-and-Delegation.md) (auth and delegation) · [RFC-0003](./RFC-0003-Federation.md) (federation) · [RFC-0005](./RFC-0005-Payment-and-Rate-Limits.md) (payment — composes with `agent_requirements` for token-gated access patterns)
**Contributions:** `attestation_url` / `attestation_jwks` mechanism proposed by [@douglasborthwick-crypto](https://github.com/douglasborthwick-crypto) in [#5](https://github.com/Cantara/knowledge-context-protocol/issues/5)

---

## What This RFC Proposes

Two additions to the KCP manifest format, grouped because they represent the two halves of enterprise knowledge governance:

1. **`trust` block** — who published this knowledge, has it been tampered with, what audit trail is required, and what kind of agent is permitted to access it?
2. **`compliance` block** — where may this knowledge be stored and processed, which regulations apply, how sensitive is it, and what processing restrictions apply?

Both blocks operate as **root-level defaults with per-unit overrides**. Both are **advisory declarations** — consistent with KCP's philosophy throughout (SPEC.md §12.1).

---

## Why These Were Deferred

SPEC.md §12.1 already establishes the KCP trust model: *"All KCP metadata is advisory. The presence of a field in a manifest is a declaration by the publisher, not a guarantee of correctness or a technical control."*

The trust and compliance proposals needed to be deferred from the core spec because:

- **Trust infrastructure was immature.** Content signing (JWS, HTTP signatures) and DID-based publisher identity were not widely deployed. W3C Verifiable Credentials 2.0 reached recommendation in May 2025; widespread adoption follows.
- **Compliance vocabulary was contested.** Which regulations to name, how to express data residency, whether `sensitivity` levels should align with NATO, ISO 27001, or a custom vocabulary — these questions needed real-world feedback.
- **RFC-0002 needed to come first.** Several compliance restrictions (`no-external-llm`, `human-approval-required`) depend on auth and delegation mechanisms. Those are now defined.
- **They are opt-in for good reason.** A personal site or open-source project has no need for compliance metadata. Requiring it — or even suggesting it — in the core spec would create friction where none is needed.

The NIST NCCoE AI Agent Standards Initiative (Feb 2026) has made these fields urgent for enterprise and government adoption. The comment period is open until April 2, 2026.

---

## Part 1: The `trust` Block

### Root-level `trust` block

```yaml
trust:                              # OPTIONAL root-level block

  # Provenance: who published this knowledge?
  provenance:
    publisher: "Acme Corp"
    publisher_url: "https://acme.com"
    publisher_did: "did:web:acme.com"   # W3C DID for cryptographically verifiable identity
    contact: "knowledge-team@acme.com"  # contact for questions about this manifest

  # Content integrity: has this manifest or its units been tampered with?
  content_integrity:
    manifest_hash:
      algorithm: sha256             # sha256 | sha384 | sha512
      value: "e3b0c44298fc..."       # hex-encoded hash of the canonical manifest YAML
    signing:
      enabled: true
      method: jws                   # jws (RFC 7515) | http_signature (RFC 9421)
      verification_url: "https://acme.com/.well-known/kcp-signing-key"
      key_id: "key-2026-02"

  # Audit: what must agents do when accessing this knowledge?
  audit:
    agent_must_log: true
    # Advisory: agents SHOULD record access in their own audit trail.

    require_trace_context: true
    # If true: agents MUST include W3C Trace Context headers (traceparent, tracestate)
    # so the full access chain can be reconstructed. Compatible with OpenTelemetry.

    provides_access_receipts: false
    # If true: the serving infrastructure issues a receipt per access.
    receipt_format: otel_span       # w3c_vp | json | otel_span

  # Agent requirements: what kind of agent is permitted to access this knowledge?
  agent_requirements:
    require_attestation: false
    # If true: agent must present attestation before accessing restricted units.
    # Satisfied by EITHER trusted_providers (identity-based) OR attestation_url
    # (credential-based). If both are present, satisfying either is sufficient.

    trusted_providers: ["internal-agents.acme.com"]
    # Identity-based: only agents from these provider domains are accepted.
    # Matched against OIDC-A `agent_provider` claim or equivalent.
    # Unknown agents SHOULD be refused access to restricted units.

    attestation_url: "https://acme.com/v1/attest"
    # Credential-based: URL of an endpoint that verifies agent credentials.
    # The verification mechanism (on-chain token check, W3C Verifiable Credential,
    # OIDC claim, SPIFFE assertion) is outside KCP's scope.
    # MUST use HTTPS. If present, `require_attestation` SHOULD be `true`.

    attestation_jwks: "https://acme.com/.well-known/jwks.json"
    # URL of a JWKS endpoint for verifying signed responses from `attestation_url`.
    # If absent, agents MUST use the TLS certificate of `attestation_url` as
    # the trust anchor.
```

### Per-unit `trust` overrides

Individual units may tighten (but not loosen) trust requirements relative to the root:

```yaml
units:
  - id: public-overview
    path: README.md
    intent: "What is this project?"
    trust:
      audit:
        agent_must_log: false     # this unit is public; no audit required

  - id: board-minutes
    path: governance/board-minutes.md
    intent: "What were the decisions at the last board meeting?"
    trust:
      agent_requirements:
        require_attestation: true
        trusted_providers: ["internal-agents.acme.com"]  # identity-based
        attestation_url: "https://acme.com/v1/attest"    # credential-based (either satisfies)
      audit:
        agent_must_log: true
        require_trace_context: true
```

### `trust` field reference

#### `provenance` sub-fields

| Field | Required | Description |
|-------|----------|-------------|
| `publisher` | OPTIONAL | Human-readable name of the publishing organisation or individual. |
| `publisher_url` | OPTIONAL | URL of the publisher's web presence. |
| `publisher_did` | OPTIONAL | W3C Decentralized Identifier for cryptographically verifiable publisher identity. Resolved per W3C DID Core spec. |
| `contact` | OPTIONAL | Email or URL for questions about this manifest's content. |

#### `content_integrity` sub-fields

| Field | Required | Description |
|-------|----------|-------------|
| `manifest_hash` | OPTIONAL | Hash of the canonical manifest YAML, for integrity verification. |
| `manifest_hash.algorithm` | REQUIRED if `manifest_hash` present | Hash algorithm: `sha256`, `sha384`, or `sha512`. |
| `manifest_hash.value` | REQUIRED if `manifest_hash` present | Hex-encoded hash value. |
| `signing.enabled` | OPTIONAL | Whether content signing is active. Default: `false`. |
| `signing.method` | REQUIRED if enabled | `jws` (RFC 7515) or `http_signature` (RFC 9421). |
| `signing.verification_url` | RECOMMENDED if enabled | URL where the signing key or JWKS can be retrieved. |
| `signing.key_id` | OPTIONAL | Identifier for the specific key used. |

#### `audit` sub-fields

| Field | Required | Description |
|-------|----------|-------------|
| `agent_must_log` | OPTIONAL | Advisory: agents SHOULD record access in their audit trail. Default: `false`. |
| `require_trace_context` | OPTIONAL | If `true`: agents MUST include W3C Trace Context headers (`traceparent`, `tracestate`). Default: `false`. |
| `provides_access_receipts` | OPTIONAL | Whether the serving infrastructure issues access receipts. |
| `receipt_format` | OPTIONAL | Format of access receipts: `w3c_vp`, `json`, or `otel_span`. |

#### `agent_requirements` sub-fields

| Field | Required | Description |
|-------|----------|-------------|
| `require_attestation` | OPTIONAL | If `true`: agents MUST present attestation before accessing restricted units. Satisfied by `trusted_providers` OR `attestation_url` — either is sufficient. Default: `false`. |
| `trusted_providers` | OPTIONAL | List of trusted agent provider domain names (identity-based). Matched against OIDC-A `agent_provider` claim or equivalent. Agents not matching SHOULD be refused access to restricted units. |
| `attestation_url` | OPTIONAL | URL of an endpoint that verifies agent credentials (credential-based). The verification mechanism — on-chain token check, W3C Verifiable Credential, OIDC claim, SPIFFE assertion — is outside KCP's scope. MUST use HTTPS. If present, `require_attestation` SHOULD be `true`. |
| `attestation_jwks` | OPTIONAL | JWKS endpoint URL for verifying signed responses from `attestation_url`. If absent, agents MUST use the TLS certificate of `attestation_url` as the trust anchor. |

---

## Part 2: The `compliance` Block

### Root-level `compliance` block

```yaml
compliance:                         # OPTIONAL root-level block

  # Where may this knowledge be stored and processed?
  data_residency:
    regions: [EEA]                  # see region vocabulary below
    hard_requirement: false         # if true: legally mandatory, not advisory

  # Which regulations apply to this knowledge?
  regulations: [GDPR]               # see regulation vocabulary below

  # How sensitive is this knowledge?
  sensitivity: internal             # see sensitivity levels below

  # What processing restrictions apply?
  restrictions: []                  # see restriction vocabulary below
```

### Per-unit `compliance` overrides

Units may override the root compliance declaration. Either tightening (more restrictive) or loosening (less restrictive, e.g. marking a specific public unit within a generally restricted manifest) is permitted:

```yaml
units:
  - id: public-overview
    path: README.md
    intent: "What is this project?"
    compliance:
      sensitivity: public           # override: less restrictive than root default

  - id: customer-pii
    path: data/customer-profiles.md
    intent: "How is customer personal data structured?"
    compliance:
      data_residency:
        regions: [EEA]
        hard_requirement: true      # legally mandatory — not advisory
      regulations: [GDPR, ePrivacy]
      sensitivity: confidential
      restrictions:
        - no-external-llm
        - no-logging
        - audit-required

  - id: security-runbook
    path: ops/security-runbook.md
    intent: "How do we respond to a security incident?"
    compliance:
      sensitivity: restricted
      regulations: [NIS2]
      restrictions:
        - no-external-llm
        - human-approval-required   # connects to RFC-0002 delegation.human_in_the_loop

  - id: financial-projections
    path: finance/projections.md
    intent: "What are the financial projections for this quarter?"
    compliance:
      sensitivity: confidential
      regulations: [MiFID2]
      data_residency:
        regions: [EU, NO]
```

### `compliance` field reference

#### Sensitivity levels

| Value | Meaning |
|-------|---------|
| `public` | No restrictions. Freely shareable. |
| `internal` | For internal use only. Not for external parties. |
| `confidential` | Restricted within the organisation. Need-to-know basis. |
| `restricted` | Highest sensitivity. Strict access controls. Human approval required. |

These levels are intentionally aligned with common information classification frameworks (ISO 27001, UK HMG, many national standards). `public` through `restricted` is a widely understood gradient.

#### Regulation vocabulary

Named regulations for the `regulations` field. Unknown values MUST be silently ignored:

| Value | Regulation |
|-------|-----------|
| `GDPR` | EU General Data Protection Regulation |
| `ePrivacy` | EU ePrivacy Directive / Regulation |
| `NIS2` | EU Network and Information Security Directive 2 |
| `HIPAA` | US Health Insurance Portability and Accountability Act |
| `HITECH` | US Health Information Technology for Economic and Clinical Health Act |
| `CCPA` | California Consumer Privacy Act |
| `MiFID2` | EU Markets in Financial Instruments Directive II |
| `DORA` | EU Digital Operational Resilience Act (financial sector) |
| `ITAR` | US International Traffic in Arms Regulations |
| `EAR` | US Export Administration Regulations |
| `eIDAS` | EU Electronic Identification, Authentication and Trust Services |
| `EU-AI-Act` | EU Artificial Intelligence Act |
| `SOC2` | Service Organization Control 2 (US) |
| `ISO27001` | ISO/IEC 27001 Information Security Management |
| `NIST-AI-RMF` | NIST AI Risk Management Framework |

This list is not exhaustive. Custom regulation identifiers are permitted; tools SHOULD warn on unknown values but MUST NOT reject the manifest.

#### Data residency regions

| Value | Coverage |
|-------|---------|
| `global` | No residency restriction |
| `EEA` | European Economic Area |
| `EU` | European Union member states only |
| `US` | United States |
| `UK` | United Kingdom |
| `NO` | Norway |
| `CH` | Switzerland |
| `AU` | Australia |

ISO 3166-1 alpha-2 country codes MAY be used directly for any country not listed. Unknown region values MUST be silently ignored.

`hard_requirement: true` signals that the constraint is legally mandatory rather than advisory. Agents encountering `hard_requirement: true` SHOULD refuse to process the unit if they cannot confirm they are operating within the declared region(s). This is the only field in KCP that approaches normative agent behaviour for compliance — and it is still advisory at the format level.

#### Processing restrictions

| Value | Meaning |
|-------|---------|
| `no-external-llm` | MUST NOT be sent to external LLM APIs for processing |
| `no-logging` | MUST NOT appear in agent logs or audit traces |
| `no-caching` | MUST NOT be cached beyond the immediate session |
| `no-redistribution` | MUST NOT be passed to other agents, tools, or systems |
| `audit-required` | Access MUST be logged to an external compliance system |
| `human-approval-required` | Agent MUST obtain human approval before accessing (connects to RFC-0002 `delegation.human_in_the_loop`) |
| `encryption-at-rest-required` | Cached or stored copies MUST be encrypted |
| `anonymisation-required` | Personally identifiable information MUST be anonymised before use |

Unknown restriction values MUST be silently ignored. Custom restriction values (e.g. `x-acme-restricted`) are permitted per §11.

---

## Relationships Between Trust and Compliance

The two blocks are designed to compose:

```yaml
trust:
  audit:
    agent_must_log: true
    require_trace_context: true

compliance:
  sensitivity: confidential
  regulations: [GDPR]
  restrictions:
    - audit-required               # requires external audit log (trust.audit provides the mechanism)
    - human-approval-required      # connects to RFC-0002 delegation.human_in_the_loop
```

| Compliance restriction | Corresponding trust/auth mechanism |
|-----------------------|-----------------------------------|
| `audit-required` | `trust.audit.agent_must_log` + `trust.audit.require_trace_context` |
| `human-approval-required` | RFC-0002 `delegation.human_in_the_loop` |
| `no-external-llm` | Advisory only; no technical mechanism in spec |
| `no-logging` | Contradicts `trust.audit.agent_must_log`; unit-level `no-logging` SHOULD override root audit requirements |

### Composability with RFC-0005 (Payment)

`trust.agent_requirements` and the RFC-0005 `payment` block are independent axes that compose cleanly. A common pattern is **token-gated access**: agents that pass attestation get free access; agents that do not fall through to a paid method.

```yaml
trust:
  agent_requirements:
    require_attestation: true
    attestation_url: "https://example.com/v1/attest"   # e.g. on-chain token check
    attestation_jwks: "https://example.com/.well-known/jwks.json"

payment:
  default_tier: metered
  methods:
    - type: x402
      currency: USDC
      price_per_request: "0.001"
      networks: [base]
      wallet: "0x..."
```

Agent flow: call `attestation_url` first. Pass → access granted (free). Fail → fall through to `payment.methods` and pay via x402. This keeps token-gating in `trust` (access-by-proof) and payment in `payment` (access-by-transaction), avoiding semantic overlap between the two blocks.

---

## Relationship to SPEC.md §12.1 (Existing Trust Model)

SPEC.md §12.1 already establishes that:

> *"All KCP metadata is advisory. The presence of a field in a manifest is a declaration by the publisher, not a guarantee of correctness or a technical control."*

RFC-0004 is additive — it provides richer vocabulary for declarations that were already possible in spirit but not in form. The core principle is unchanged: KCP declares; the consuming system enforces.

Specifically, §12.1 already notes:

- **Freshness (`validated`):** A `validated` date is a declaration, not a proof. RFC-0004 adds `content_integrity` for cryptographic verification.
- **Compliance scope:** §12.1 says compliance declarations "assert the publisher's stated intent, not verified compliance status." RFC-0004's `regulations` and `restrictions` fields follow this same principle.
- **Access requirements:** §12.1 says auth metadata "declares the publisher's intended access controls." RFC-0004's `compliance.restrictions` are in the same advisory category.

---

## Alignment with NIST NCCoE (Feb 2026)

The NIST NCCoE AI Agent Standards Initiative concept paper identifies three requirements directly addressed by RFC-0004:

| NIST Requirement | RFC-0004 Response |
|-----------------|------------------|
| "Logging and transparency: linking specific AI agent actions to their non-human entity" | `trust.audit.require_trace_context` (W3C Trace Context) + `trust.audit.agent_must_log` |
| "Data governance: ensuring AI agents handle data in compliance with applicable laws" | `compliance.regulations` + `compliance.data_residency` + `compliance.restrictions` |
| "Trust verification: mechanisms for verifying the trustworthiness of AI agents" | `trust.agent_requirements.require_attestation` + `trust.agent_requirements.trusted_providers` |

---

## Complete Example

A regulated internal knowledge base for a financial services firm:

```yaml
kcp_version: "0.3"
project: acme-financial-knowledge
version: 2.0.0
updated: "2026-02-28"
language: "en"
license: "proprietary"

auth:
  methods:
    - type: oauth2
      flow: client_credentials
      token_endpoint: "https://auth.acme.com/token"
      scopes: ["read:knowledge"]

trust:
  provenance:
    publisher: "Acme Financial Services"
    publisher_did: "did:web:acme.com"
    contact: "knowledge-ops@acme.com"
  content_integrity:
    signing:
      enabled: true
      method: jws
      verification_url: "https://acme.com/.well-known/kcp-signing-key"
  audit:
    agent_must_log: true
    require_trace_context: true

compliance:
  data_residency:
    regions: [EEA]
    hard_requirement: true
  regulations: [GDPR, MiFID2, DORA]
  sensitivity: confidential

units:
  - id: overview
    path: README.md
    intent: "What does this knowledge base cover?"
    scope: global
    audience: [human, agent]
    compliance:
      sensitivity: public           # public despite confidential default

  - id: trading-algorithms
    path: quant/trading-algos.md
    intent: "How are our proprietary trading algorithms structured?"
    scope: module
    audience: [developer, agent]
    access: restricted
    auth_scope: quant-team
    compliance:
      sensitivity: restricted
      regulations: [MiFID2, ITAR]
      restrictions:
        - no-external-llm
        - no-redistribution
        - audit-required
    trust:
      agent_requirements:
        require_attestation: true
        trusted_providers: ["internal-agents.acme.com"]

  - id: customer-pii-schema
    path: data/customer-pii.md
    intent: "How is customer personal data structured and protected?"
    scope: module
    audience: [developer, agent]
    access: restricted
    auth_scope: data-team
    compliance:
      data_residency:
        regions: [EEA]
        hard_requirement: true
      regulations: [GDPR, ePrivacy]
      sensitivity: confidential
      restrictions:
        - no-external-llm
        - no-logging
        - no-caching
        - audit-required
        - human-approval-required
```

---

## Conformance Level Implications

This RFC proposes the following additions to SPEC.md §8 when promoted to core:

- **Level 2**: `trust.provenance` (publisher identity — useful with minimal overhead)
- **Level 2**: `compliance.sensitivity` (single field, high signal value)
- **Level 3**: `trust.content_integrity` (signing infrastructure required)
- **Level 3**: `trust.audit` (observability infrastructure required)
- **Level 3**: `trust.agent_requirements` (agent attestation infrastructure required)
- **Level 3**: `compliance.data_residency` + `compliance.regulations` + `compliance.restrictions`

---

## Open Questions

**1. Sensitivity level alignment**
Should the four sensitivity levels (`public`, `internal`, `confidential`, `restricted`) align explicitly with an existing standard — ISO 27001, UK HMG, NATO? Or is the current informal vocabulary more universally adoptable? The risk of alignment: a standard-specific vocabulary excludes projects not in that ecosystem. The risk of informality: enterprises have their own classification schemes that may not map cleanly.

**2. `hard_requirement` on `data_residency`**
`hard_requirement: true` is the only field in KCP that approaches normative agent behaviour — it says agents "SHOULD refuse to process." Is this too strong for an advisory format? Alternative: remove `hard_requirement` and instead recommend that agents treat ALL data residency declarations as binding by default, with an explicit `advisory: true` flag to opt out.

**3. Content hash scope**
The `manifest_hash` covers the full manifest YAML. Should there also be per-unit content hashes, so agents can verify individual files without rehashing the whole manifest? This would require either embedding hashes in each unit declaration or pointing to a separate hash manifest.

**4. `trusted_providers` matching** ✅ *Resolved*
`attestation_url` is now the specified interim mechanism (added based on community feedback from [@douglasborthwick-crypto](https://github.com/douglasborthwick-crypto)). Publishers who cannot rely on OIDC-A ratification can declare an `attestation_url` pointing to any verification endpoint — on-chain credential check, W3C VC presentation, or custom OIDC claim. KCP remains mechanism-agnostic: the manifest declares *where* to verify; the endpoint decides *how*. `trusted_providers` is retained for identity-based (static allowlist) matching when OIDC-A or a compatible standard is available.

**5. Conflict resolution: `no-logging` vs `audit-required`**
A unit might inherit `audit-required` from the root `compliance` block and also declare `no-logging`. These are contradictory. The proposal suggests unit-level `no-logging` overrides root audit requirements — but should there be explicit conflict resolution rules, or should this be left to implementations?

**6. `compliance.certification` field**
Should there be a field to declare that this manifest or its publisher holds a specific certification (ISO 27001, SOC 2, FedRAMP)? This would allow agents to filter knowledge sources by certification level. Or is this better expressed as a `publisher_did` attestation rather than a free-form string?

---

## References

- [NIST NCCoE: AI Agent Identity and Authorization (Feb 2026)](https://www.nccoe.nist.gov/projects/software-and-ai-agent-identity-and-authorization) — logging, data governance, trust verification
- [NIST AI Risk Management Framework — Govern 1.6](https://airc.nist.gov/) — data governance requirements
- [EU AI Act — data requirements for high-risk AI](https://artificialintelligenceact.eu/)
- [GDPR — EU General Data Protection Regulation](https://gdpr.eu/)
- [NIS2 Directive](https://digital-strategy.ec.europa.eu/en/policies/nis2-directive)
- [DORA — EU Digital Operational Resilience Act](https://www.digital-operational-resilience-act.com/)
- [W3C Verifiable Credentials 2.0](https://www.w3.org/press-releases/2025/verifiable-credentials-2-0/)
- [W3C DID Core Specification](https://www.w3.org/TR/did-core/)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/) — `traceparent` / `tracestate` headers
- [OpenTelemetry GenAI Semantic Conventions](https://opentelemetry.io/blog/2025/ai-agent-observability/)
- [DIF Trusted AI Agents Working Group](https://identity.foundation/working-groups/trusted-agents.html)
- [RFC 7515: JSON Web Signature (JWS)](https://datatracker.ietf.org/doc/html/rfc7515)
- [RFC 9421: HTTP Message Signatures](https://datatracker.ietf.org/doc/html/rfc9421)
- [RFC 8693: OAuth 2.0 Token Exchange](https://datatracker.ietf.org/doc/html/rfc8693) — agent-to-agent delegation
- [OIDC-A Proposal](https://subramanya.ai/2025/04/28/oidc-a-proposal/) — agent attestation claims
- [ISO/IEC 27001:2022 — Information Security Management](https://www.iso.org/standard/27001)

---

## How to Participate

- **Comment on [Issue #5](https://github.com/Cantara/knowledge-context-protocol/issues/5)** — trust, provenance, audit feedback
- **Comment on [Issue #11](https://github.com/Cantara/knowledge-context-protocol/issues/11)** — compliance, data residency, regulation vocabulary feedback
- **Open a new issue** if your compliance use case is not covered
- **Submit a PR** to this document with concrete improvements

RFC-0002 (auth) should stabilise before RFC-0004 is promoted to core — several compliance restrictions depend on the delegation mechanism defined there.

---

*Knowledge Context Protocol — proposed by [eXOReaction AS](https://www.exoreaction.com), Oslo, Norway.*
