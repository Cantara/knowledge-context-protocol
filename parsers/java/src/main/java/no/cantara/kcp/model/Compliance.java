package no.cantara.kcp.model;

import java.util.List;

/**
 * Compliance metadata block — root-level and per-unit override.
 * Captures data residency, sensitivity classification, applicable regulations,
 * and usage restrictions. See SPEC.md §3.5.
 */
public record Compliance(
        List<String> dataResidency,
        String sensitivity,
        List<String> regulations,
        List<String> restrictions
) {
    public Compliance {
        dataResidency = dataResidency != null ? List.copyOf(dataResidency) : List.of();
        regulations   = regulations   != null ? List.copyOf(regulations)   : List.of();
        restrictions  = restrictions  != null ? List.copyOf(restrictions)  : List.of();
    }
}
