package no.cantara.kcp.model;

import java.util.List;

/** A single accepted payment method. See SPEC.md §4.14 (v0.25). */
public record PaymentMethod(
        String type,                 // free | x402 | meter | subscription
        String currency,             // x402
        String pricePerRequest,      // x402 — decimal string
        List<String> networks,       // x402
        String wallet,               // x402
        String provider,             // meter
        String plansUrl,             // meter | subscription
        Boolean freeTier,            // subscription
        Integer freeRequestsPerDay,  // subscription
        String upgradeUrl            // subscription
) {}
