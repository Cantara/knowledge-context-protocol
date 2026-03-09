package no.cantara.kcp.simulator.graph;

import java.util.List;

/**
 * Thrown when a cycle is detected in the dependency graph.
 */
public final class CycleException extends RuntimeException {

    private final List<String> cyclePath;

    public CycleException(List<String> cyclePath) {
        super("Dependency cycle detected: " + String.join(" -> ", cyclePath));
        this.cyclePath = List.copyOf(cyclePath);
    }

    /**
     * The IDs forming the cycle, e.g. ["A", "B", "C", "A"].
     */
    public List<String> cyclePath() {
        return cyclePath;
    }
}
