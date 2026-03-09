package no.cantara.kcp.simulator.graph;

import no.cantara.kcp.simulator.model.KnowledgeUnit;
import no.cantara.kcp.simulator.model.Relationship;

import java.util.*;

/**
 * Builds a directed dependency graph from two sources:
 * <ol>
 *   <li>Inline {@code depends_on} fields on each unit</li>
 *   <li>{@code relationships[]} entries with {@code type: depends_on}</li>
 * </ol>
 * Edges are deduplicated. The graph is expressed as an adjacency list
 * where an edge A -> B means "A depends on B" (B must be loaded before A).
 */
public final class DependencyGraph {

    // Maps unitId -> set of unitIds it depends on
    private final Map<String, Set<String>> adjacency = new LinkedHashMap<>();
    private final Set<String> allNodes = new LinkedHashSet<>();

    private DependencyGraph() {}

    /**
     * Build a dependency graph from units and relationships.
     *
     * @param units         the knowledge units
     * @param relationships all relationships (only type:depends_on edges are used for ordering)
     * @return the constructed graph
     */
    public static DependencyGraph build(List<KnowledgeUnit> units, List<Relationship> relationships) {
        DependencyGraph graph = new DependencyGraph();

        // Register all nodes
        for (KnowledgeUnit unit : units) {
            graph.allNodes.add(unit.id());
            graph.adjacency.putIfAbsent(unit.id(), new LinkedHashSet<>());
        }

        // Source 1: inline depends_on fields
        for (KnowledgeUnit unit : units) {
            for (String dep : unit.dependsOn()) {
                graph.addEdge(unit.id(), dep);
            }
        }

        // Source 2: relationships with type: depends_on
        // "from: A, to: B, type: depends_on" means A depends on B
        for (Relationship rel : relationships) {
            if (Relationship.DEPENDS_ON.equals(rel.type())) {
                graph.addEdge(rel.from(), rel.to());
            }
        }

        return graph;
    }

    private void addEdge(String from, String to) {
        allNodes.add(from);
        allNodes.add(to);
        adjacency.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
        adjacency.putIfAbsent(to, new LinkedHashSet<>());
    }

    /**
     * Get the set of unit IDs that the given unit depends on.
     */
    public Set<String> dependenciesOf(String unitId) {
        return Collections.unmodifiableSet(adjacency.getOrDefault(unitId, Set.of()));
    }

    /**
     * Get all node IDs in the graph.
     */
    public Set<String> nodes() {
        return Collections.unmodifiableSet(allNodes);
    }

    /**
     * Get the full adjacency map.
     */
    public Map<String, Set<String>> adjacencyMap() {
        return Collections.unmodifiableMap(adjacency);
    }

    /**
     * Number of edges in the graph.
     */
    public int edgeCount() {
        return adjacency.values().stream().mapToInt(Set::size).sum();
    }
}
