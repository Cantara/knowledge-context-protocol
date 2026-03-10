package no.cantara.kcp.model;

/**
 * A cross-manifest dependency for a knowledge unit.
 * See SPEC.md §3.6.
 */
public record ExternalDependency(
        String manifest,
        String unit,
        String onFailure
) {
    /** Default on_failure value when not specified. */
    public static final String DEFAULT_ON_FAILURE = "skip";

    public ExternalDependency {
        if (onFailure == null) {
            onFailure = DEFAULT_ON_FAILURE;
        }
    }
}
