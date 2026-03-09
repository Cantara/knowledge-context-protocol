package no.cantara.kcp.simulator;

import no.cantara.kcp.simulator.graph.DependencyGraph;
import no.cantara.kcp.simulator.model.KnowledgeUnit;
import no.cantara.kcp.simulator.model.Relationship;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DependencyGraphTest {

    private KnowledgeUnit unit(String id, String... deps) {
        return new KnowledgeUnit(id, "docs/" + id + ".md", "Test", "public", "public",
                List.of(deps), null, false);
    }

    @Test
    void mergesInlineAndRelationshipDependsOn() {
        // Inline: A depends_on B
        // Relationship: A depends_on C
        var units = List.of(unit("A", "B"), unit("B"), unit("C"));
        var rels = List.of(new Relationship("A", "C", "depends_on"));
        var graph = DependencyGraph.build(units, rels);

        assertEquals(Set.of("B", "C"), graph.dependenciesOf("A"));
    }

    @Test
    void deduplicatesEdges() {
        // Both inline and relationship say A depends_on B
        var units = List.of(unit("A", "B"), unit("B"));
        var rels = List.of(new Relationship("A", "B", "depends_on"));
        var graph = DependencyGraph.build(units, rels);

        assertEquals(Set.of("B"), graph.dependenciesOf("A"));
        assertEquals(1, graph.edgeCount());
    }

    @Test
    void ignoresNonDependsOnRelationships() {
        var units = List.of(unit("A"), unit("B"));
        var rels = List.of(
                new Relationship("A", "B", "enables"),
                new Relationship("A", "B", "supersedes"),
                new Relationship("A", "B", "context"),
                new Relationship("A", "B", "contradicts"));
        var graph = DependencyGraph.build(units, rels);

        assertEquals(Set.of(), graph.dependenciesOf("A"));
        assertEquals(Set.of(), graph.dependenciesOf("B"));
        assertEquals(0, graph.edgeCount());
    }

    @Test
    void emptyGraph() {
        var graph = DependencyGraph.build(List.of(), List.of());
        assertEquals(0, graph.nodes().size());
        assertEquals(0, graph.edgeCount());
    }

    @Test
    void allNodesPresent() {
        var units = List.of(unit("A", "B"), unit("B"), unit("C"));
        var graph = DependencyGraph.build(units, List.of());

        assertEquals(Set.of("A", "B", "C"), graph.nodes());
    }

    @Test
    void nodeFromRelationshipOnlyAdded() {
        // Relationship references "D" which is not in units
        var units = List.of(unit("A"));
        var rels = List.of(new Relationship("A", "D", "depends_on"));
        var graph = DependencyGraph.build(units, rels);

        assertTrue(graph.nodes().contains("D"), "D should be added from relationship");
        assertEquals(Set.of("D"), graph.dependenciesOf("A"));
    }

    @Test
    void multipleEdgesFromSameNode() {
        var units = List.of(unit("A", "B", "C", "D"), unit("B"), unit("C"), unit("D"));
        var graph = DependencyGraph.build(units, List.of());

        assertEquals(Set.of("B", "C", "D"), graph.dependenciesOf("A"));
        assertEquals(3, graph.edgeCount());
    }
}
