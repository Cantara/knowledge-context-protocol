package no.cantara.kcp.model;

import java.util.List;

/**
 * Root-level authentication block describing methods available for accessing
 * protected units. See SPEC.md §3.3.
 */
public record Auth(
        List<AuthMethod> methods
) {
    public Auth {
        methods = methods != null ? List.copyOf(methods) : List.of();
    }
}
