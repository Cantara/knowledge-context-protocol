# Attestation example (v0.22)

An internal-knowledge manifest that requires agents to **attest who they are** before it will
serve its restricted units — the consumer-identity half of KCP's security model
([Trust & Attestation](../../guides/gating-restricted-knowledge-with-attestation.md), RFC-0004/0002).

- **`onboarding`** — `access: public`, served to anyone.
- **`incident-runbook`** — `access: restricted`, gated behind `trust.agent_requirements`.

The load-bearing idea: **KCP declares the requirement; it never performs the auth.** The renderer
surfaces `agent_requirements` and marks the restricted unit `requires_attestation`, but never
calls `attestation_url`. The bridge refuses restricted content until an `attestation` argument is
presented — and checks only that it *was* presented, never verifying it. The agent runtime does
the real attesting (SPIFFE SVID, RFC 9421 signature, OIDC-A claim, …).

```bash
kcp validate examples/attestation/knowledge.yaml   # ✓ Valid
kcp render   examples/attestation/knowledge.yaml   # surfaces agent_requirements + requires_attestation
```

Full walkthrough (validate → render → bridge gating → governance propagation):
[**guides/gating-restricted-knowledge-with-attestation.md**](../../guides/gating-restricted-knowledge-with-attestation.md).
