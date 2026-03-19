package no.cantara.kcp.model;

import java.util.List;
import java.util.Map;

/**
 * Visibility block for conditional access control on a knowledge unit or manifest default.
 * See SPEC.md §RFC-0009 (v0.12).
 *
 * <p>The YAML field {@code default} is mapped to {@code defaultSensitivity} because
 * {@code default} is a reserved word in Java.
 *
 * @param defaultSensitivity  Base sensitivity when no condition matches.
 *                            One of: {@code public} | {@code internal} | {@code confidential} |
 *                            {@code restricted}. Optional.
 * @param conditions          List of conditional overrides. Each entry is a map with keys
 *                            {@code when} (containing optional {@code environment} and
 *                            {@code agent_role}) and {@code then} (containing optional
 *                            {@code sensitivity}, {@code requires_auth}, {@code authority}).
 *                            Optional.
 */
public record Visibility(
        String defaultSensitivity,
        List<Map<String, Object>> conditions
) {
    public Visibility {
        conditions = conditions != null ? List.copyOf(conditions) : List.of();
    }
}
