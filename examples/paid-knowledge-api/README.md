# Paid knowledge API (v0.25)

A knowledge API with a **free public tier and paid premium tiers** — the reference example for the
v0.25 economic metadata layer (RFC-0005, SPEC §4.14/§4.15). It lets an agent decide *what access
costs* and *how much it can consume* before issuing a single request:

- **`payment.methods[]`** — ordered by publisher preference: `free`, `x402` (per-request
  micropayment: `currency`, `price_per_request`, `networks`, `wallet`), `meter` (provider +
  `plans_url`), `subscription` (`plans_url`, `free_tier`, `free_requests_per_day`, `upgrade_url`).
- **`rate_limits`** — per-tier budgets (`default` / `authenticated` / `premium`), a token-based
  sub-block for LLM pipelines, the live-state `headers`, and a `backoff` strategy. `unlimited` is a
  valid sentinel.
- **Unit-level overrides** — one manifest, three economic models: a free `docs` index, an
  x402-metered `realtime-prices` feed, and a subscription `premium-research` corpus. A unit's
  `payment` **replaces** the root block (no merge).

The load-bearing idea, as everywhere in KCP: **KCP declares the economics; it settles nothing.** The
renderer surfaces `payment`/`rate_limits` as data and never dereferences a `wallet`, `plans_url`, or
`upgrade_url`.

```bash
kcp validate examples/paid-knowledge-api/knowledge.yaml   # ✓ Valid
kcp render   examples/paid-knowledge-api/knowledge.yaml   # surfaces payment + rate_limits per unit
```

## Runnable demo

```bash
(cd cli && npm install && npm run build)          # one-time
node examples/paid-knowledge-api/demo.js          # all scenarios, narrated
node examples/paid-knowledge-api/demo.js budget   # one scenario by id
node examples/paid-knowledge-api/demo.js --list   # list scenario ids
```

Four scenarios drive the real `kcp` CLI and narrate an agent's cost planning — computed from
authentic `kcp render` output, not scripted: **catalogue** (survey the economics), **method**
(select a supported payment method), **budget** (compute the bill before fetching), and **rateplan**
(read the per-tier rate budget). Browser replay: [`docs/showcase.html`](../../docs/showcase.html).

## Files

- [`knowledge.yaml`](./knowledge.yaml) — the API manifest (3 units, three economic models).
- [`docs/index.md`](./docs/index.md) — the free public catalogue + pricing.
- [`data/prices.md`](./data/prices.md) — the x402-metered real-time feed.
- [`corpus/research.md`](./corpus/research.md) — the subscription research corpus.

Walkthrough: [guides/monetizing-knowledge-with-payment.md](../../guides/monetizing-knowledge-with-payment.md).
