package no.cantara.kcp.simulator.model;

/**
 * A directed relationship between two knowledge units.
 *
 * @param from the source unit ID
 * @param to   the target unit ID
 * @param type one of: depends_on, enables, supersedes, contradicts, context
 */
public record Relationship(String from, String to, String type) {

    public static final String DEPENDS_ON = "depends_on";
    public static final String ENABLES = "enables";
    public static final String SUPERSEDES = "supersedes";
    public static final String CONTRADICTS = "contradicts";
    public static final String CONTEXT = "context";
}
