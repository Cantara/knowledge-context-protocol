# Grand tour — KCP end to end

**One narrated walk through the whole stack, driven by the shipping `kcp` CLI.** Every stop is a
real command run against a real example in this repo — nothing is mocked. It's the fastest way to
see what KCP actually does, in the order a project grows into it.

```bash
(cd cli && npm install && npm run build)   # one-time
node examples/grand-tour/demo.js            # the full tour
node examples/grand-tour/demo.js navigate   # one stop by id
node examples/grand-tour/demo.js --list     # list stop ids
```

## The six stops

| Stop | Capability | What the real command shows |
|------|-----------|-----------------------------|
| **adopt** | Adoption (v0.3) | `kcp validate` on the five-line `minimal` manifest — a conformant manifest with no ceremony |
| **navigate** | Query vocabulary (v0.14) | `kcp query` scores a real wiki and returns a ranked route — the 53–80% tool-call reduction |
| **time-travel** | Bi-temporal validity (v0.19–0.20) | the same `kcp query --as-of` two dates returns two policy versions — point-in-time reconstruction |
| **render** | Trusted render pipeline (v0.16–0.18) | `kcp render` tiers an unsigned manifest as readable data, never load-eligible — fail-closed |
| **attest** | Trust & attestation (v0.22) | `kcp render` surfaces `requires_attestation` without ever calling `attestation_url` |
| **federate** | Org-federation (v0.24) | `kcp render` surfaces per-edge `context` and `agent_identity` for enterprise discovery |

## Authenticity

There is one `kcp` binary: this demo invokes the production CLI, so what you see is what ships.
The browser replay at [`docs/showcase.html`](../../docs/showcase.html) plays back captures produced
by `node demo.js --capture` — it never reimplements the CLI, so it can't drift.

For the v0.24 layer up close, see the deeper [`../org-federation/demo.js`](../org-federation/).
