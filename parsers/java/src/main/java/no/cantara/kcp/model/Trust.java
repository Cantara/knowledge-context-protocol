package no.cantara.kcp.model;

/**
 * Root-level trust block containing publisher provenance and audit requirements.
 * See SPEC.md §3.2.
 */
public record Trust(
        TrustProvenance provenance,
        TrustAudit audit
) {
}
