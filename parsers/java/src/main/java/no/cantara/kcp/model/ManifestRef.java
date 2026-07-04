package no.cantara.kcp.model;

/**
 * A reference to an external KCP manifest in the federation.
 * See SPEC.md §3.6.
 */
public record ManifestRef(
        String id,
        String url,
        String label,
        String relationship,
        Auth auth,
        String updateFrequency,
        String localMirror,
        String versionPin,
        String versionPolicy,
        Temporal temporal,
        java.util.List<String> context,      // RFC-0011 / §3.6 (v0.24)
        AgentIdentity agentIdentity           // RFC-0011 / §3.6 (v0.24)
) {}
