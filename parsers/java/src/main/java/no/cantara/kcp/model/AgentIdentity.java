package no.cantara.kcp.model;

/**
 * Pre-fetch credential-planning hint on a manifests[] entry.
 * A declaration layer, not an auth protocol. See SPEC.md §3.6 (v0.24).
 */
public record AgentIdentity(
        Boolean required,        // default false
        String credentialHint,   // github_pat | oauth2 | confluence_pat | api_key | none
        String issuerHint,       // for oauth2: issuer URL
        String docsUrl           // where a developer finds credential instructions
) {}
