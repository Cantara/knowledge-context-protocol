# Tutorial: Gating restricted knowledge with attestation

A hands-on walkthrough of **Trust & Attestation** (v0.22): declaring that an agent must
prove *who it is* before a knowledge source will serve its restricted units — and seeing
that gate enforced by the renderer and the MCP bridge.

The one principle to hold onto: **KCP declares trust requirements; it never performs auth.**
The manifest says *what* attestation is expected; the agent runtime does the attesting. The
renderer never dereferences `attestation_url`; the bridge checks a credential was *presented*
but never verifies it. This keeps the render pipeline deterministic and keeps KCP out of the
business of being an identity provider.

Everything here runs against the shipped example at
[`examples/attestation/`](../examples/attestation/).

## 1. The manifest

`trust.agent_requirements` (SPEC §3.2) declares the requirement; a unit opts in by being
`access: restricted`:

```yaml
trust:
  agent_requirements:
    require_attestation: true
    # Satisfied by EITHER an allowlisted provider identity OR a credential the endpoint accepts.
    trusted_providers: ["internal-agents.acme.com"]   # identity-based (OIDC-A agent_provider)
    attestation_url: "https://acme.com/v1/attest"      # credential-based (HTTPS)
    attestation_jwks: "https://acme.com/.well-known/jwks.json"

units:
  - id: onboarding
    intent: "How do I get started?"
    access: public               # served to anyone
  - id: incident-runbook
    intent: "Production incident runbook with escalation contacts"
    access: restricted           # gated behind attestation
```

The extended `auth.methods` types (v0.22) declare *how* an agent presents identity —
`spiffe` (workload SVID), `http_signature` (RFC 9421), `did`, `bearer_token`:

```yaml
auth:
  methods:
    - type: spiffe
      trust_domain: acme.internal
    - type: http_signature
      key_id: acme-agent-2026
      algorithm: ed25519
    - type: none        # public fallback
```

Validate it:

```bash
kcp validate examples/attestation/knowledge.yaml
# ✓ Valid — no errors or warnings
```

The validator will warn if you make the requirement unsatisfiable (`require_attestation: true`
with no `trusted_providers` or `attestation_url`), if `attestation_url` isn't HTTPS, or if you
set `propagate_to_governed: true` without a `governs` relationship.

## 2. Render it — the requirement is surfaced, not enforced

```bash
kcp render examples/attestation/knowledge.yaml
```

The rendered artifact surfaces `agent_requirements` as data and flags the restricted unit:

```yaml
trust:
  tier: unsigned
  agent_requirements:
    require_attestation: true
    trusted_providers: ["internal-agents.acme.com"]
    attestation_url: "https://acme.com/v1/attest"
units:
  - id: incident-runbook
    requires_attestation: true      # ← the C19 marker
```

Two things the renderer deliberately does **not** do (conformance C19):

- It never calls `attestation_url` — the strings are copied through verbatim. The render stays
  deterministic and network-free.
- It does **not** set `load_eligible: false` on the restricted unit just because attestation is
  required. Gating there would be theater — the renderer can't attest. The *bridge* is the real
  gate (C20). A restricted unit at `trusted` tier is still `load_eligible: true` **with** the
  `requires_attestation` marker: "you may load this once you've attested."

## 3. Serve it through the bridge — the gate fires

Point an MCP bridge at the manifest (any of the three — TypeScript, Python, Java):

```bash
npx kcp-mcp examples/attestation/knowledge.yaml      # TypeScript
# or:  python -m kcp_mcp examples/attestation/knowledge.yaml
```

Now the gate is live on **every retrieval path** (C20):

| Call | Result |
|------|--------|
| `get_unit(unit_id: "onboarding")` | ✅ served — the unit is `access: public` |
| `get_unit(unit_id: "incident-runbook")` | ❌ `{"error": "attestation_required", "agent_requirements": {…}}` |
| `get_unit(unit_id: "incident-runbook", attestation: "spiffe://acme.internal/agent")` | ✅ served — a credential was presented |
| `read_resource("knowledge://…/incident-runbook")` | ❌ refused — resource reads carry no attestation channel; use `get_unit` |
| `search_knowledge(query: "incident")` | returns the unit **marked** `requires_attestation: true` so the agent knows to attest first |

The bridge checks that an `attestation` argument was *presented*. It does **not** verify the
credential — it never calls `attestation_url`. Verification is the agent runtime's job (and the
mechanism — on-chain token, Verifiable Credential, OIDC-A claim, SPIFFE assertion — is out of
KCP's scope). KCP's contribution is the *declaration layer* so an agent can plan before it acts.

## 4. Governance propagation (optional)

A governing manifest can push its attestation floor onto the sources it `governs`:

```yaml
trust:
  agent_requirements:
    require_attestation: true
    propagate_to_governed: true    # governed sources MUST meet at least this bar
relationships:
  - from: security-policy
    to: some-team-manifest
    type: governs
```

An agent resolving the `governs` edge treats the governing manifest's requirements as a floor
on the governed source, warning if the governed manifest declares weaker requirements (C21).
This resolves [#47](https://github.com/Cantara/knowledge-context-protocol/issues/47).

## Where this sits in the trust model

v0.16–v0.21 answered *"is this knowledge intact and current?"* — signatures, per-unit content
hashes, origin evidence, composition integrity, temporal validity. v0.22 answers the
complementary question: *"who may consume it, and how do they prove it?"* Producer integrity
(`content_integrity`) and consumer identity (`agent_requirements`) are the two halves of the
same story, and they compose cleanly with payment (RFC-0005) for token-gated access — attest to
get free access, fall through to `payment.methods` if you can't.

## See also

- [SPEC §3.2 `trust.agent_requirements`](../SPEC.md#32-trust) · [§3.3 extended `auth.methods`](../SPEC.md#33-auth) · [§16.5 C19–C21](../SPEC.md#165-renderer-conformance)
- [RFC-0004 Trust & Compliance](../RFC-0004-Trust-and-Compliance.md) · [RFC-0002 Auth & Delegation](../RFC-0002-Auth-and-Delegation.md)
- [`examples/attestation/`](../examples/attestation/)
