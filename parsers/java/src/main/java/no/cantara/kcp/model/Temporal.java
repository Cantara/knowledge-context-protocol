package no.cantara.kcp.model;

/**
 * Bi-temporal validity block for a knowledge unit or manifest root default.
 * See SPEC.md §4.22 (v0.19) and §15.13 (v0.20).
 *
 * @param validFrom     ISO 8601 date — unit becomes active on this date (null = beginning of time)
 * @param validUntil    ISO 8601 date — unit expires after this date (null = open-ended)
 * @param recordedAt    ISO 8601 date — when this version was added to the manifest (informational)
 * @param supersededBy  id of the unit within this manifest that replaces this one
 */
public record Temporal(
        String validFrom,
        String validUntil,
        String recordedAt,
        String supersededBy
) {
}
