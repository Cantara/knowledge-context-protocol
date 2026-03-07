package no.cantara.kcp.model;

/**
 * Publisher identity fields within the {@code trust} block. See SPEC.md §3.2.
 */
public record TrustProvenance(
        String publisher,
        String publisherUrl,
        String contact
) {
}
