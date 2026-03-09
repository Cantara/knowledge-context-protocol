package no.cantara.kcp.simulator;

import no.cantara.kcp.simulator.graph.CycleException;
import no.cantara.kcp.simulator.graph.DependencyGraph;
import no.cantara.kcp.simulator.graph.TopologicalSorter;
import no.cantara.kcp.simulator.model.KnowledgeUnit;
import no.cantara.kcp.simulator.model.Relationship;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TopologicalSorterTest {

    private KnowledgeUnit unit(String id, String... deps) {
        return new KnowledgeUnit(id, "docs/" + id + ".md", "Test", "public", "public",
                List.of(deps), null, false);
    }

    @Test
    void linearChain_correctOrder() {
        // A -> B -> C (A depends on B, B depends on C)
        var units = List.of(unit("A", "B"), unit("B", "C"), unit("C"));
        var graph = DependencyGraph.build(units, List.of());
        var order = TopologicalSorter.sort(graph);

        int posC = order.indexOf("C");
        int posB = order.indexOf("B");
        int posA = order.indexOf("A");
        assertTrue(posC < posB, "C before B");
        assertTrue(posB < posA, "B before A");
    }

    @Test
    void diamondDependency() {
        // D depends on B and C; B and C depend on A
        var units = List.of(
                unit("A"),
                unit("B", "A"),
                unit("C", "A"),
                unit("D", "B", "C"));
        var graph = DependencyGraph.build(units, List.of());
        var order = TopologicalSorter.sort(graph);

        assertTrue(order.indexOf("A") < order.indexOf("B"));
        assertTrue(order.indexOf("A") < order.indexOf("C"));
        assertTrue(order.indexOf("B") < order.indexOf("D"));
        assertTrue(order.indexOf("C") < order.indexOf("D"));
    }

    @Test
    void disconnectedComponents() {
        // Two independent chains: A->B and C->D
        var units = List.of(unit("A"), unit("B", "A"), unit("C"), unit("D", "C"));
        var graph = DependencyGraph.build(units, List.of());
        var order = TopologicalSorter.sort(graph);

        assertEquals(4, order.size());
        assertTrue(order.indexOf("A") < order.indexOf("B"));
        assertTrue(order.indexOf("C") < order.indexOf("D"));
    }

    @Test
    void singleNode() {
        var units = List.of(unit("solo"));
        var graph = DependencyGraph.build(units, List.of());
        var order = TopologicalSorter.sort(graph);

        assertEquals(List.of("solo"), order);
    }

    @Test
    void noDependencies_allIncluded() {
        var units = List.of(unit("A"), unit("B"), unit("C"));
        var graph = DependencyGraph.build(units, List.of());
        var order = TopologicalSorter.sort(graph);

        assertEquals(3, order.size());
        assertTrue(order.containsAll(List.of("A", "B", "C")));
    }

    @Test
    void cycleDetected_throwsCycleException() {
        // A -> B -> C -> A (cycle)
        var units = List.of(unit("A", "B"), unit("B", "C"), unit("C", "A"));
        var graph = DependencyGraph.build(units, List.of());

        CycleException ex = assertThrows(CycleException.class,
                () -> TopologicalSorter.sort(graph));
        assertNotNull(ex.cyclePath());
        assertTrue(ex.cyclePath().size() >= 3, "Cycle path should have at least 3 nodes");
        assertTrue(ex.getMessage().contains("cycle"), "Message should mention cycle");
    }

    @Test
    void twoNodeCycle() {
        var units = List.of(unit("A", "B"), unit("B", "A"));
        var graph = DependencyGraph.build(units, List.of());

        assertThrows(CycleException.class, () -> TopologicalSorter.sort(graph));
    }

    @Test
    void selfCycle() {
        var units = List.of(unit("A", "A"));
        var graph = DependencyGraph.build(units, List.of());

        assertThrows(CycleException.class, () -> TopologicalSorter.sort(graph));
    }

    @Test
    void relationshipDependsOnUsed() {
        // No inline depends_on, but relationship says A depends_on B
        var units = List.of(unit("A"), unit("B"));
        var rels = List.of(new Relationship("A", "B", "depends_on"));
        var graph = DependencyGraph.build(units, rels);
        var order = TopologicalSorter.sort(graph);

        assertTrue(order.indexOf("B") < order.indexOf("A"));
    }
}
