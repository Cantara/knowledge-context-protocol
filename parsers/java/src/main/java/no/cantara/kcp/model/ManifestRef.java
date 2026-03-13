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
        String versionPolicy
) {}
