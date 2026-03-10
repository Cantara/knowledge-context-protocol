package no.cantara.kcp.model;

/**
 * An explicit typed relationship between units across manifest boundaries.
 * See SPEC.md §3.6.
 */
public record ExternalRelationship(
        String fromManifest,
        String fromUnit,
        String toManifest,
        String toUnit,
        String type
) {}
