package no.cantara.kcp.model;

/**
 * A directed relationship between two knowledge units.
 */
public record Relationship(
        String fromId,
        String toId,
        String type
) {}
