# RFC-0005: Payment and Rate-Limit Metadata

**Status:** Request for Comments
**Authors:** eXOReaction AS (Thor Henning Hetland)
**Date:** 2026-02-28
**Supersedes:** Proposals C and D in [RFC-0001](./RFC-0001-KCP-Extended.md)
**Issues:** [#2 (payment)](https://github.com/Cantara/knowledge-context-protocol/issues/2) · [#4 (rate limits)](https://github.com/Cantara/knowledge-context-protocol/issues/4)
**Discussion:** [GitHub Issues](https://github.com/Cantara/knowledge-context-protocol/issues)
**Spec:** [SPEC.md](./SPEC.md) (current: v0.3)

---

## What This RFC Proposes

Two additions to the KCP manifest format:

1. **Root-level `payment` block** — a structured declaration of how a knowledge source is monetised: free, metered, x402 micropayments, or subscription.
2. **Root-level `rate_limits` block** — proactive rate limit disclosure so agents can plan request budgets before hitting a 429.
3. **Unit-level overrides for both** — mixed manifests (some units free, some premium) are described cleanly by overriding defaults at the unit level.

These fields are **advisory declarations**, not enforcement mechanisms — consistent with KCP's philosophy throughout (see SPEC.md §12.1).

---

## The Problem

### Agents discover costs reactively

Today an agent loading a KCP manifest has no signal about what access will cost or how much of the resource it can consume. It discovers these constraints reactively:

- It loads a unit and gets an HTTP 402 (payment required) with no advance notice.
- It issues a burst of requests and gets a 429 (rate limited) with no idea what the limit was.
- It has no way to decide in advance whether it can afford the resource, or to choose a cheaper method if alternatives exist.

### Mixed manifests cannot be described

A single manifest may contain units with different economics: a public documentation index, authenticated API reference, and a premium research corpus. Current KCP has no way to say "units A and B are free, unit C costs $0.01 per access, unit D is included in the $49/month subscription."

### The agentic payments ecosystem is maturing

Three payment mechanisms are converging for autonomous agents:

| Mechanism | Model | Use case |
|-----------|-------|----------|
| **x402** | HTTP 402 revival, stablecoin micropayments per request | Sub-cent per-access knowledge, real-time data |
| **Metered (Stripe Agent Pay, Google AP2)** | Traditional per-call billing via API key | Enterprise knowledge APIs |
| **Subscription** | Monthly/annual plan, tiered free → paid | Documentation, knowledge bases |

Agents need to discover which mechanisms are accepted *before* attempting access so they can select the right method, check their budget, and decide whether to proceed.

---

## Design

### Root-level `payment` block

Declares how the knowledge source is monetised. If absent, the source is assumed to be free with no payment metadata.

```yaml
payment:
  default_tier: free           # free | metered | subscription — default for all units
  methods:                     # ordered list; agent picks first supported method
    - type: free
    - type: x402
      currency: USDC           # ISO 4217 or crypto ticker
      price_per_request: "0.001"   # string to avoid floating-point issues
      networks: [base, ethereum]   # settlement networks accepted
      wallet: "0xABC..."           # receiving wallet address
    - type: meter
      provider: stripe         # stripe | google-ap2 | generic
      plans_url: "https://example.com/pricing"
    - type: subscription
      plans_url: "https://example.com/pricing"
      free_tier: true
      free_requests_per_day: 100
      upgrade_url: "https://example.com/upgrade"
  billing_contact: "billing@example.com"   # optional
```

#### Payment method types

| Type | Description |
|------|-------------|
| `free` | No cost. No credentials required beyond what `auth` block specifies. |
| `x402` | HTTP 402-based micropayment. Agent pays per request via stablecoin transfer before receiving content. |
| `meter` | Traditional metered API billing. Agent uses an API key tied to a billing account; charges accumulate per call. |
| `subscription` | Access granted under a subscription plan. Agent presents a bearer token proving subscription status. |

#### `x402` fields

| Field | Required | Description |
|-------|----------|-------------|
| `currency` | yes | ISO 4217 code (`USD`, `EUR`) or crypto ticker (`USDC`, `USDT`, `ETH`) |
| `price_per_request` | yes | Amount as a decimal string (e.g. `"0.001"`) |
| `networks` | recommended | Settlement networks accepted (`ethereum`, `base`, `solana`, `polygon`) |
| `wallet` | recommended | Receiving wallet address or payment processor endpoint URL |

#### Method ordering

Methods are ordered by publisher preference. Agents SHOULD attempt methods in order, selecting the first one they support. This allows publishers to prefer x402 micropayments (lower overhead) while accepting subscription tokens as a fallback.

---

### Root-level `rate_limits` block

Proactive rate limit disclosure. If absent, the agent has no advance information about limits and must handle 429 responses reactively.

```yaml
rate_limits:
  default:                     # unauthenticated / anonymous access
    requests_per_minute: 10
    requests_per_hour: 100
    requests_per_day: 500
  authenticated:               # authenticated but not premium
    requests_per_minute: 100
    requests_per_hour: 2000
    requests_per_day: 20000
  premium:                     # paid / subscription tier
    requests_per_minute: 1000
    requests_per_day: unlimited
  tokens:                      # optional: token-based limits (for LLM knowledge APIs)
    default:
      tokens_per_minute: 40000
      tokens_per_day: 1000000
    authenticated:
      tokens_per_minute: 200000
  headers:                     # response header names carrying live limit state
    remaining: "X-RateLimit-Remaining"
    reset: "X-RateLimit-Reset"      # Unix timestamp or seconds until window resets
    retry_after: "Retry-After"
  backoff: exponential         # linear | exponential | none — recommended backoff strategy
```

#### Tier resolution

The applicable tier is determined by the agent's authentication state at request time:

1. If agent presents a valid subscription token → `premium` limits apply.
2. If agent presents valid credentials (API key, OAuth token) → `authenticated` limits apply.
3. Otherwise → `default` limits apply.

#### Token-based limits

Publishers serving knowledge to LLM pipelines may prefer to rate-limit by token count rather than request count (e.g. a single request may return 50,000 tokens). The `tokens` sub-block parallels the request-based structure and both may coexist. The binding constraint is whichever limit is hit first.

`unlimited` is a valid sentinel value meaning no limit at that tier.

---

### Unit-level overrides

Individual units may override the root-level defaults. An override **replaces** the root block for that unit entirely — it does not merge. This avoids ambiguity when a unit changes economic model (e.g. a free unit that becomes premium).

```yaml
units:
  - id: public-index
    path: index.md
    intent: "What documentation is available?"
    scope: global
    audience: [human, agent]
    # No payment or rate_limits override — root defaults apply (free)

  - id: premium-corpus
    path: corpus/research.md
    intent: "What are the latest research findings?"
    scope: global
    audience: [agent, developer]
    payment:
      default_tier: metered
      methods:
        - type: x402
          currency: USDC
          price_per_request: "0.05"
          networks: [base]
          wallet: "0xABC..."
        - type: subscription
          plans_url: "https://example.com/pricing"
    rate_limits:
      default:
        requests_per_minute: 2
      authenticated:
        requests_per_minute: 20

  - id: free-summary
    path: corpus/summary.md
    intent: "What is the summary of the research corpus?"
    scope: global
    audience: [human, agent]
    payment:
      default_tier: free
      methods:
        - type: free
    # rate_limits not overridden — root defaults apply
```

---

## Complete Example

A knowledge API with a free public tier and a paid premium tier:

```yaml
kcp_version: "0.3"
project: knowledge-api.example.org
version: 1.0.0
updated: "2026-02-28"
language: en
indexing: open

auth:
  method: api_key
  header: "X-API-Key"
  registration_url: "https://example.com/register"

payment:
  default_tier: free
  methods:
    - type: free
    - type: subscription
      plans_url: "https://example.com/pricing"
      free_tier: true
      free_requests_per_day: 500
      upgrade_url: "https://example.com/upgrade"
  billing_contact: "billing@example.com"

rate_limits:
  default:
    requests_per_minute: 10
    requests_per_day: 500
  authenticated:
    requests_per_minute: 100
    requests_per_day: 5000
  premium:
    requests_per_minute: 1000
    requests_per_day: unlimited
  headers:
    remaining: "X-RateLimit-Remaining"
    reset: "X-RateLimit-Reset"
    retry_after: "Retry-After"
  backoff: exponential

units:
  - id: docs
    path: docs/index.md
    intent: "What APIs and knowledge units are available?"
    scope: global
    audience: [human, agent, developer]
    # inherits root: free, 10 rpm default

  - id: realtime-prices
    path: data/prices.json
    intent: "What are the current asset prices?"
    scope: module
    audience: [agent, developer]
    update_frequency: hourly
    payment:
      default_tier: metered
      methods:
        - type: x402
          currency: USDC
          price_per_request: "0.002"
          networks: [base, ethereum]
          wallet: "0xDEF..."
        - type: subscription
          plans_url: "https://example.com/pricing"
    rate_limits:
      default:
        requests_per_minute: 1
      authenticated:
        requests_per_minute: 60
      premium:
        requests_per_minute: 600
```

---

## Relationship to RFC-0002 (Auth)

Payment and auth are complementary but separate:

- **RFC-0002 `auth`** describes *who* the agent must prove it is and *how*.
- **RFC-0005 `payment`** describes *what* access costs and *which* payment mechanisms are accepted.

A knowledge source may require both (authenticate first, then pay), either alone, or neither. Common combinations:

| Auth | Payment | Pattern |
|------|---------|---------|
| none | free | Open public knowledge |
| api_key | free | Rate-limited free tier (key used for tracking only) |
| api_key | meter | Traditional paid API (key tied to billing account) |
| oauth2 | subscription | Subscription service with SSO |
| none | x402 | Anonymous micropayment — pay per request, no account |
| oauth2 | x402 | Authenticated micropayment — identity + per-call payment |

When both are present, the agent MUST satisfy auth requirements *before* attempting payment. An unauthenticated agent MUST NOT attempt x402 payment for a resource that also requires authentication.

---

## Conformance

| Feature | Level | Notes |
|---------|-------|-------|
| `payment.default_tier` | Level 2 | Lightweight signal: free / metered / subscription |
| `payment.methods` | Level 2 | At least one method declared |
| `rate_limits.default` | Level 2 | Anonymous request limits |
| `rate_limits.authenticated` | Level 3 | Per-tier limits |
| `rate_limits.tokens` | Level 3 | Token-based limits for LLM pipelines |
| Unit-level overrides | Level 3 | Mixed-economics manifests |
| `payment.methods[].networks` | Level 3 | x402 settlement network details |

A manifest that declares `payment.default_tier: free` and `rate_limits.default` meets Level 2. Full per-unit economics with x402 details and token limits is Level 3.

---

## Open Design Questions

**1. Method ordering vs agent choice**

Methods are listed in publisher-preference order, and agents select the first supported method. Alternative: declare them as an unordered set and let agents pick freely. Which model is correct — publisher preference or agent autonomy?

**2. Price denomination**

`price_per_request` is a decimal string (e.g. `"0.001"`) to avoid IEEE 754 floating-point issues. Should this be a structured object `{amount: "0.001", currency: "USDC"}` instead, to avoid the currency field being required alongside it? Or is the flat structure at method level sufficient?

**3. Agent budget declaration**

Should agents be able to declare a maximum spend per manifest load in their request headers or as a well-known KCP field on the query? This would allow agents to signal a budget ceiling so publishers can plan capacity and agents can avoid runaway spend. Out of scope for the manifest format, or a KCP-adjacent convention worth defining?

**4. Quota windows**

`requests_per_minute`, `requests_per_hour`, `requests_per_day` may coexist. When multiple windows are declared, all constraints apply independently (the most restrictive binding at any moment). Should KCP require parsers to enforce this interpretation, or is it advisory?

**5. Token vs request limits**

The `tokens` sub-block enables token-based rate limiting, but token count is not known until the unit is fetched. Agents cannot pre-check token limits before loading a unit. Is this acceptable for an advisory declaration, or should token limits be expressed differently (e.g. `max_tokens_per_unit: 50000` as a per-unit advisory)?

**6. Free tier degradation**

The subscription method supports `free_tier: true` and `free_requests_per_day`, but the spec does not define what happens when the free tier is exhausted: hard block (403), payment prompt (402), or degraded service (partial response). Should KCP define an `on_limit_exhausted` field, or is this too implementation-specific?

---

## Summary

RFC-0005 adds the economic metadata layer to KCP:

- **`payment`** tells agents what access costs and which payment mechanisms the publisher accepts — including the emerging x402 micropayment standard.
- **`rate_limits`** tells agents how much they can consume per time window, proactively, before they hit a 429.
- **Unit-level overrides** solve the mixed-manifest problem: free public index alongside premium paid corpus, described cleanly in one manifest.

Together these fields allow an agent to make a fully informed resource-loading decision before issuing a single request.
