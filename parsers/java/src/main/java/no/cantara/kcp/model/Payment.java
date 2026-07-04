package no.cantara.kcp.model;

import java.util.List;

/** Monetisation block — root-level and per-unit override. See SPEC.md §4.14. */
public record Payment(
        String defaultTier,             // free | metered | subscription
        List<PaymentMethod> methods,    // v0.25
        String billingContact           // v0.25
) {}
