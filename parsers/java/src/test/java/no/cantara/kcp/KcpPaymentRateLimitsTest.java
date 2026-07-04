package no.cantara.kcp;

import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** v0.25 Economic Metadata: structured payment.methods + rate_limits tiers (RFC-0005). */
class KcpPaymentRateLimitsTest {

    @SuppressWarnings("unchecked")
    private static KnowledgeManifest parse(String yaml) {
        return KcpParser.fromMap((Map<String, Object>) new Yaml().load(yaml));
    }

    @Test void parsesPaymentAndRateLimitTiers() {
        KnowledgeManifest m = parse("""
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
            """);
        assertEquals("metered", m.payment().defaultTier());
        assertEquals(List.of("free", "x402", "subscription"),
                m.payment().methods().stream().map(PaymentMethod::type).toList());
        PaymentMethod x402 = m.payment().methods().stream().filter(x -> "x402".equals(x.type())).findFirst().orElseThrow();
        assertEquals("USDC", x402.currency());
        assertEquals("0.001", x402.pricePerRequest());
        assertEquals(List.of("base", "ethereum"), x402.networks());
        PaymentMethod sub = m.payment().methods().stream().filter(x -> "subscription".equals(x.type())).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, sub.freeTier());
        assertEquals(100, sub.freeRequestsPerDay());
        assertEquals("billing@ex.com", m.payment().billingContact());
        assertEquals(100, m.rateLimits().authenticated().requestsPerMinute());
        assertEquals("unlimited", m.rateLimits().premium().requestsPerDay());
        assertEquals(40000, m.rateLimits().tokens().defaultTier().tokensPerMinute());
        assertEquals("X-RateLimit-Remaining", m.rateLimits().headers().remaining());
        assertEquals("exponential", m.rateLimits().backoff());
        assertTrue(KcpValidator.validate(m).warnings().stream()
                .noneMatch(w -> w.contains("payment") || w.contains("backoff")));
    }

    @Test void warnsOnBadX402UnknownMethodAndBackoff() {
        KnowledgeManifest m = parse("""
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
            """);
        var w = KcpValidator.validate(m).warnings();
        assertTrue(w.stream().anyMatch(x -> x.contains("x402 method is missing required 'currency'")), w.toString());
        assertTrue(w.stream().anyMatch(x -> x.contains("x402 method is missing required 'price_per_request'")), w.toString());
        assertTrue(w.stream().anyMatch(x -> x.contains("unknown type 'crypto-hug'")), w.toString());
        assertTrue(w.stream().anyMatch(x -> x.contains("backoff must be one of")), w.toString());
    }

    @Test void warnsWhenPaidTierHasOnlyFreeMethod() {
        KnowledgeManifest m = parse("""
            kcp_version: "0.25"
            project: bad2
            version: 1.0.0
            payment:
              default_tier: metered
              methods:
                - type: free
            units:
              - {id: u, path: u.md, intent: x, scope: project, audience: [agent]}
            """);
        var w = KcpValidator.validate(m).warnings();
        assertTrue(w.stream().anyMatch(x -> x.contains("default_tier is 'metered' but no paid method")), w.toString());
    }
}
