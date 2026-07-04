# Tutorial: Monetizing knowledge with payment & rate limits

**Level:** intermediate · **Spec:** SPEC.md §4.14 / §4.15 (v0.25) · **RFC:** [RFC-0005](../RFC-0005-Payment-and-Rate-Limits.md)
· **Example:** [examples/paid-knowledge-api/](../examples/paid-knowledge-api/)

Today an agent discovers cost the hard way: it loads a unit and gets an HTTP 402, or bursts and
gets a 429, with no advance warning. The v0.25 **economic metadata** layer fixes that — a manifest
declares *what access costs* and *how much an agent may consume*, so the agent can select a payment
method it supports, check its budget, and decide **before** issuing a single request.

Everything here is advisory: **KCP declares the economics and settles nothing.** The renderer
surfaces `payment`/`rate_limits` as data and never dereferences a `wallet`, `plans_url`, or
`upgrade_url`.

## 1. A mixed-economics manifest

Open [`examples/paid-knowledge-api/knowledge.yaml`](../examples/paid-knowledge-api/knowledge.yaml).
One manifest, three economic models:

```yaml
payment:                        # root default: free to read, with a subscription option
  default_tier: free
  methods:
    - type: free
    - type: subscription
      plans_url: "https://example.org/pricing"
      free_tier: true
      free_requests_per_day: 500

units:
  - id: docs                    # inherits root — free
  - id: realtime-prices         # overrides: x402 micropayment, 0.002 USDC / request
    payment:
      default_tier: metered
      methods:
        - type: x402
          currency: USDC
          price_per_request: "0.002"
          networks: [base, ethereum]
          wallet: "0xDEF..."
  - id: premium-research        # overrides: subscription, metered fallback
    payment:
      default_tier: subscription
      methods:
        - type: subscription
          plans_url: "https://example.org/pricing"
        - type: meter
          provider: stripe
```

A unit-level `payment` **replaces** the root block for that unit (no merge), so a unit that changes
economic model is unambiguous. Validate it:

```bash
kcp validate examples/paid-knowledge-api/knowledge.yaml
# ✓ Valid — no errors or warnings
```

## 2. Payment methods and method ordering

`payment.methods[]` is **ordered by publisher preference** — an agent attempts them in order and
selects the first it supports:

- **`free`** — no cost.
- **`x402`** — pay per request (typically stablecoin). Requires `currency` and a decimal-string
  `price_per_request`; `networks` and `wallet` are recommended.
- **`meter`** — metered API billing via an API key tied to a billing account (`provider`,
  `plans_url`).
- **`subscription`** — a bearer token proving plan status (`plans_url`, `free_tier`,
  `free_requests_per_day`, `upgrade_url`).

So an agent that holds a subscription token uses it; one that doesn't but can pay stablecoin falls
to x402; one that supports neither reads only the free units. The publisher expresses preference;
the agent exercises what it can.

The validator keeps declarations honest — it warns on an `x402` method missing `currency` or
`price_per_request`, a `price_per_request` that isn't a decimal string, an unknown method `type`,
and a `metered`/`subscription` `default_tier` whose only declared method is `free`.

## 3. Rate limits an agent can plan against

`rate_limits` discloses budgets per **tier** so an agent self-throttles instead of waiting for a
429:

```yaml
rate_limits:
  default:       { requests_per_minute: 10,   requests_per_day: 500 }      # anonymous
  authenticated: { requests_per_minute: 100,  requests_per_day: 5000 }     # keyed
  premium:       { requests_per_minute: 1000, requests_per_day: unlimited } # subscribed
  tokens:
    default: { tokens_per_minute: 40000 }        # LLM pipelines: limit by tokens, not requests
  headers:
    remaining: "X-RateLimit-Remaining"           # where live limit state is reported
    retry_after: "Retry-After"
  backoff: exponential
```

The applicable tier follows the agent's auth state at request time: subscription token → `premium`;
valid credentials → `authenticated`; otherwise → `default`. `unlimited` is a sentinel meaning no
limit at that tier. Token-based limits parallel request limits and both may apply — the binding
constraint is whichever is hit first.

## 4. Render it — the economics come through as data

```bash
kcp render examples/paid-knowledge-api/knowledge.yaml
```

```yaml
payment:
  default_tier: free
  methods:
    - type: free
    - type: subscription
      plans_url: https://example.org/pricing
      free_requests_per_day: 500
rate_limits:
  default: { requests_per_minute: 10, requests_per_day: 500 }
  premium: { requests_per_minute: 1000, requests_per_day: unlimited }
  backoff: exponential
```

The renderer copies tiers, prices, limits, and URLs through verbatim — and **dereferences none of
them**. A cost-aware agent reads this from the trusted render artifact and plans: it knows the
`docs` unit is free at 10 rpm, that `realtime-prices` will cost 0.002 USDC each, and that
`premium-research` needs a subscription — all before the first fetch.

## Where this sits

`payment` answers *what access costs* and `rate_limits` answers *how much I can consume*. They are
complementary to `auth` (§3.3, *who* the agent is) and `trust` (§3.2, *access-by-proof*): when both
auth and payment are present, the agent satisfies auth **before** attempting payment. Together they
let an agent make a fully informed resource-loading decision before issuing a single request.

## See also

- [SPEC.md §4.14](../SPEC.md) / [§4.15](../SPEC.md) — `payment` and `rate_limits` field references
- [RFC-0005](../RFC-0005-Payment-and-Rate-Limits.md) — the full design rationale and the x402 model
- [examples/paid-knowledge-api/](../examples/paid-knowledge-api/) — the runnable manifest used above
- [examples/api-platform-rate-limits/](../examples/api-platform-rate-limits/) — a rate-limit-only companion (v0.8)
