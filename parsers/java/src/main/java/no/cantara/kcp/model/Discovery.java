package no.cantara.kcp.model;

/**
 * Discovery metadata for a knowledge unit or manifest default. See SPEC.md §RFC-0012 (v0.12).
 *
 * @param verificationStatus  One of: {@code rumored} | {@code observed} | {@code verified} | {@code deprecated}.
 *                            Default: {@code verified}.
 * @param source              How this unit was discovered: {@code manual} | {@code web_traversal} |
 *                            {@code openapi} | {@code llm_inference}. Default: {@code manual}.
 * @param observedAt          ISO 8601 datetime when the unit was first observed. Optional.
 * @param verifiedAt          ISO 8601 datetime when the unit was last verified. Optional.
 * @param confidence          Float 0.0–1.0. Default: {@code 1.0}.
 *                            Normative: rumored MUST have confidence &lt; 0.5;
 *                            verified SHOULD have confidence &ge; 0.8.
 * @param contradictedBy      Unit id that contradicts this unit. Optional.
 */
public record Discovery(
        String verificationStatus,
        String source,
        String observedAt,
        String verifiedAt,
        Double confidence,
        String contradictedBy
) {}
