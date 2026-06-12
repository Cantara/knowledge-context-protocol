package no.cantara.kcp.model;

/**
 * Per-unit content hash binding referenced content to the signed manifest.
 * See RFC-0019 (draft).
 *
 * @param algorithm Hash algorithm: {@code sha256} | {@code sha384} | {@code sha512}.
 * @param value     Hex digest per RFC-0019 §3.2 — a file hashes its raw bytes; a
 *                  directory hashes the bytewise-sorted concatenation of
 *                  {@code relpath \0 hexdigest \n} entries over every regular file
 *                  beneath it (symlinks not followed, no exclusions).
 */
public record ContentHash(
        String algorithm,
        String value
) {
}
