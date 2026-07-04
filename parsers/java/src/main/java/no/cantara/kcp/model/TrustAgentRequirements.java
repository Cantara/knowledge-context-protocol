package no.cantara.kcp.model;

import java.util.List;

/**
 * Agent attestation requirements within the trust block. See SPEC.md §3.2 (v0.22).
 *
 * <p>Declares what an agent must prove about itself before {@code access: restricted} units are
 * served. KCP declares these requirements; it does not perform authentication.
 */
public record TrustAgentRequirements(
        Boolean requireAttestation,
        List<String> trustedProviders,
        String attestationUrl,
        String attestationJwks,
        Boolean propagateToGoverned
) {
    public TrustAgentRequirements {
        trustedProviders = trustedProviders != null ? List.copyOf(trustedProviders) : List.of();
    }
}
