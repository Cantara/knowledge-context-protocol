package no.cantara.kcp.model;

import java.util.List;

/**
 * Content structure metadata for a knowledge unit. See RFC-0016 (v0.17).
 *
 * <p>Vocabulary (forward-compatible — unknown values pass through):
 * <ul>
 *   <li>{@code primary} / {@code contains} modalities: prose | table | code | list |
 *       diagram | reference | mixed</li>
 *   <li>{@code density}: sparse | normal | dense</li>
 * </ul>
 *
 * @param primary  The dominant content modality. Optional.
 * @param contains Additional modalities present in the unit. Optional.
 * @param density  Information density classification. Optional.
 */
public record ContentStructure(
        String primary,
        List<String> contains,
        String density
) {
    public ContentStructure {
        contains = contains != null ? List.copyOf(contains) : List.of();
    }
}
