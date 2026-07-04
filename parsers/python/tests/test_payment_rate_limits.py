"""v0.25 Economic Metadata: structured payment.methods + rate_limits tiers (RFC-0005)."""
import yaml
from kcp.parser import parse_dict
from kcp.validator import validate

M = yaml.safe_load("""
kcp_version: "0.25"
project: paid-api
version: 1.0.0
payment:
  default_tier: metered
  methods:
    - type: free
    - type: x402
      currency: USDC
      price_per_request: "0.001"
      networks: [base, ethereum]
      wallet: "0xABC"
    - type: subscription
      plans_url: "https://ex.com/pricing"
      free_tier: true
      free_requests_per_day: 100
  billing_contact: "billing@ex.com"
rate_limits:
  default: {requests_per_minute: 10, requests_per_day: 500}
  authenticated: {requests_per_minute: 100}
  premium: {requests_per_minute: 1000, requests_per_day: unlimited}
  tokens:
    default: {tokens_per_minute: 40000}
  headers: {remaining: "X-RateLimit-Remaining", retry_after: "Retry-After"}
  backoff: exponential
units:
  - {id: docs, path: docs.md, intent: x, scope: global, audience: [agent]}
""")


def test_parses_payment_and_rate_limit_tiers():
    m = parse_dict(M)
    assert m.payment.default_tier == "metered"
    assert [x.type for x in m.payment.methods] == ["free", "x402", "subscription"]
    x402 = next(x for x in m.payment.methods if x.type == "x402")
    assert x402.currency == "USDC"
    assert x402.price_per_request == "0.001"
    assert x402.networks == ["base", "ethereum"]
    sub = next(x for x in m.payment.methods if x.type == "subscription")
    assert sub.free_tier is True
    assert sub.free_requests_per_day == 100
    assert m.payment.billing_contact == "billing@ex.com"
    assert m.rate_limits.authenticated.requests_per_minute == 100
    assert m.rate_limits.premium.requests_per_day == "unlimited"
    assert m.rate_limits.tokens.default.tokens_per_minute == 40000
    assert m.rate_limits.headers.remaining == "X-RateLimit-Remaining"
    assert m.rate_limits.backoff == "exponential"
    econ_warnings = [w for w in validate(m).warnings if "payment" in w or "backoff" in w]
    assert econ_warnings == []


def test_warns_on_bad_x402_unknown_method_and_backoff():
    m = parse_dict(yaml.safe_load("""
kcp_version: "0.25"
project: bad
version: 1.0.0
payment:
  methods:
    - type: x402
    - type: crypto-hug
rate_limits:
  backoff: aggressive
units:
  - {id: u, path: u.md, intent: x, scope: project, audience: [agent]}
"""))
    w = validate(m).warnings
    assert any("x402 method is missing required 'currency'" in x for x in w)
    assert any("x402 method is missing required 'price_per_request'" in x for x in w)
    assert any("unknown type 'crypto-hug'" in x for x in w)
    assert any("backoff must be one of" in x for x in w)


def test_warns_when_paid_tier_has_only_free_method():
    m = parse_dict(yaml.safe_load("""
kcp_version: "0.25"
project: bad2
version: 1.0.0
payment:
  default_tier: metered
  methods:
    - type: free
units:
  - {id: u, path: u.md, intent: x, scope: project, audience: [agent]}
"""))
    w = validate(m).warnings
    assert any("default_tier is 'metered' but no paid method" in x for x in w)
