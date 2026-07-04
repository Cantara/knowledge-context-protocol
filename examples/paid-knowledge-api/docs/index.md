# Knowledge API — catalogue & pricing

A knowledge API with a free public tier and paid premium tiers. This index is free to read; the
`payment` and `rate_limits` blocks in [`knowledge.yaml`](../knowledge.yaml) tell an agent what each
unit costs and how much it can consume before it issues a single request.

| Unit | Economics | Rate limit (anon) |
|------|-----------|-------------------|
| `docs` (this file) | free | 10 req/min |
| `realtime-prices` | x402 micropayment — 0.002 USDC/request | 1 req/min |
| `premium-research` | subscription (metered fallback) | tier-dependent |

Register for an API key at `https://example.org/register`; see plans at `https://example.org/pricing`.
