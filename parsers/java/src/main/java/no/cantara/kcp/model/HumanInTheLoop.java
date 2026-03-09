package no.cantara.kcp.model;

/**
 * Human-in-the-loop approval object — see SPEC.md §3.4.
 */
public record HumanInTheLoop(
        Boolean required,
        String approvalMechanism,   // "oauth_consent" | "uma" | "custom"
        String docsUrl
) {
}
