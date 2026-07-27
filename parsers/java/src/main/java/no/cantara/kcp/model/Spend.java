package no.cantara.kcp.model;

import java.util.List;

/**
 * What a {@code kind: skill} procedure may buy. See SPEC.md §4.3a.1.
 *
 * <p>Governs the buy <em>decision</em>, fail-closed — an unlisted vendor, an over-cap
 * amount or a currency mismatch is held. KCP never settles a payment; a runtime wallet
 * does, and a passing adjudication is not evidence that one succeeded.
 */
public record Spend(
        Double maxSpend,            // per-purchase cap, denominated in `currency`
        List<String> allowedVendors, // allowlist of vendor/payee identifiers
        String currency             // ISO 4217 code (USD, EUR) or asset ticker (USDC)
) {
    public Spend {
        allowedVendors = allowedVendors != null ? List.copyOf(allowedVendors) : null;
    }
}
