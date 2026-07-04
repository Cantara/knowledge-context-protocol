# Org-federation example (v0.24)

An enterprise **knowledge hub** — the front door an AI agent reaches when it knows only a company
domain name. It shows how the two RFC-0011 fields promoted in v0.24 solve the enterprise-bootstrap
problem (SPEC §3.6):

- **`manifests[].context`** — environment labels (`dev`/`test`/`staging`/`prod`) on each federation
  edge, so one hub can publish a federation list spanning environments and an agent selects only
  the entries matching its runtime.
- **`manifests[].agent_identity`** — a pre-fetch credential-planning hint (`required`,
  `credential_hint`, `issuer_hint`, `docs_url`) telling the agent what to acquire *before* it
  fetches a sub-manifest.

It also demonstrates two **usage-convention** patterns that need no new spec fields:

- **Org Hub** — `network.role: hub`, a public load-eager `front-door` unit, and a `manifests[]`
  block listing sub-manifests.
- **Progressive disclosure** — three `compliance.sensitivity` tiers: `public` (`overview`,
  `auth-guide`) → `internal` (`service-catalogue`) → `confidential` (`data-contracts`).

The load-bearing idea, as everywhere in KCP: **the hub declares; the agent (or its runtime) acts.**
`context` and `agent_identity` are advisory — the renderer surfaces them and never dereferences
`docs_url` or `issuer_hint`, and the sub-manifest's own `auth` block remains the enforcement point.

```bash
kcp validate examples/org-federation/knowledge.yaml   # ✓ Valid
kcp render   examples/org-federation/knowledge.yaml   # surfaces context + agent_identity per edge
```

## Runnable demo

```bash
(cd cli && npm install && npm run build)        # one-time
node examples/org-federation/demo.js            # all scenarios, narrated
node examples/org-federation/demo.js env-prod   # one scenario by id
node examples/org-federation/demo.js --list     # list scenario ids
```

Five scenarios drive the real `kcp` CLI against this hub and narrate the agent's traversal —
computed from authentic `kcp render` output, not scripted: **cold** arrival, **env-prod** /
**env-dev** context selection, **credentials** planning via `agent_identity`, and **disclosure**
up the sensitivity ladder. Browser replay: [`docs/showcase.html`](../../docs/showcase.html).

## Files

- [`knowledge.yaml`](./knowledge.yaml) — the hub manifest (4 units, 3 federated sub-manifests).
- [`overview.md`](./overview.md) — the public front-door unit an unauthenticated agent loads first.
- [`guides/agent-authentication.md`](./guides/agent-authentication.md) — how a developer obtains the
  credentials the federation edges declare.
- [`catalogue/services.md`](./catalogue/services.md) — the internal (T1) service index.
- [`catalogue/data-contracts.md`](./catalogue/data-contracts.md) — the confidential (T2) data contracts.

Full walkthrough: [guides/enterprise-discovery-with-org-federation.md](../../guides/enterprise-discovery-with-org-federation.md).
