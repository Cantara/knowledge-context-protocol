package no.cantara.kcp.simulator.graph;

import java.util.*;

/**
 * Topological sort using Kahn's algorithm.
 * <p>
 * Given a dependency graph where an edge A -> B means "A depends on B",
 * returns units in safe load order: all dependencies before dependents.
 * <p>
 * Detects cycles and throws {@link CycleException} with the cycle path.
 * Handles disconnected components (nodes with no edges are included).
 */
public final class TopologicalSorter {

    private TopologicalSorter() {}

    /**
     * Sort the graph topologically.
     *
     * @param graph the dependency graph
     * @return unit IDs in safe load order (dependencies first)
     * @throws CycleException if the graph contains a cycle
     */
    public static List<String> sort(DependencyGraph graph) {
        Map<String, Set<String>> adj = graph.adjacencyMap();
        Set<String> allNodes = graph.nodes();

        // Compute in-degree for Kahn's: an edge A -> B means A depends on B,
        // so for load order, B must come before A.
        // We reverse the edges: B has an outgoing edge to A (B enables A).
        // In-degree of A = number of dependencies of A.
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (String node : allNodes) {
            inDegree.put(node, 0);
        }
        for (Map.Entry<String, Set<String>> entry : adj.entrySet()) {
            // entry: A -> {B, C} means A depends on B and C
            // In load order, B and C come before A, so A's in-degree += deps count
            inDegree.merge(entry.getKey(), entry.getValue().size(), Integer::sum);
        }

        // Start with nodes that have no dependencies (in-degree = 0)
        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String node = queue.poll();
            result.add(node);

            // For each other node that depends on this node, reduce in-degree
            for (Map.Entry<String, Set<String>> entry : adj.entrySet()) {
                if (entry.getValue().contains(node)) {
                    String dependent = entry.getKey();
                    int newDegree = inDegree.get(dependent) - 1;
                    inDegree.put(dependent, newDegree);
                    if (newDegree == 0) {
                        queue.add(dependent);
                    }
                }
            }
        }

        if (result.size() != allNodes.size()) {
            // Cycle detected — find the cycle path
            List<String> cyclePath = findCycle(adj, allNodes, result);
            throw new CycleException(cyclePath);
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * Find a cycle in the remaining (unvisited) nodes.
     */
    private static List<String> findCycle(Map<String, Set<String>> adj,
                                          Set<String> allNodes,
                                          List<String> visited) {
        Set<String> remaining = new LinkedHashSet<>(allNodes);
        remaining.removeAll(visited);

        // DFS to find cycle
        Set<String> onStack = new LinkedHashSet<>();
        Map<String, String> parent = new HashMap<>();

        for (String start : remaining) {
            List<String> cycle = dfs(start, adj, new HashSet<>(), onStack, parent, remaining);
            if (cycle != null) return cycle;
        }

        // Fallback: return remaining nodes as the cycle indication
        List<String> fallback = new ArrayList<>(remaining);
        fallback.add(fallback.get(0));
        return fallback;
    }

    private static List<String> dfs(String node, Map<String, Set<String>> adj,
                                    Set<String> visited, Set<String> onStack,
                                    Map<String, String> parent, Set<String> remaining) {
        if (!remaining.contains(node)) return null;
        if (onStack.contains(node)) {
            // Found cycle — reconstruct path
            List<String> cycle = new ArrayList<>();
            cycle.add(node);
            String current = parent.get(node);
            while (current != null && !current.equals(node)) {
                cycle.add(current);
                current = parent.get(current);
            }
            cycle.add(node);
            Collections.reverse(cycle);
            return cycle;
        }
        if (visited.contains(node)) return null;

        visited.add(node);
        onStack.add(node);

        for (String dep : adj.getOrDefault(node, Set.of())) {
            parent.put(dep, node);
            List<String> cycle = dfs(dep, adj, visited, onStack, parent, remaining);
            if (cycle != null) return cycle;
        }

        onStack.remove(node);
        return null;
    }
}
