package no.cantara.kcp.simulator;

import no.cantara.kcp.simulator.agent.KnowledgeIngestionAgent;
import no.cantara.kcp.simulator.audit.IngestionLog;
import no.cantara.kcp.simulator.model.KnowledgeUnit;
import no.cantara.kcp.simulator.model.LoadResult;
import no.cantara.kcp.simulator.model.Relationship;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeIngestionAgentTest {

    private KnowledgeUnit unit(String id, String... deps) {
        return new KnowledgeUnit(id, "docs/" + id + ".md", "Test", "public", "public",
                List.of(deps), null, false);
    }

    private KnowledgeUnit unitWithSupersedes(String id, String supersedes, String... deps) {
        return new KnowledgeUnit(id, "docs/" + id + ".md", "Test", "public", "public",
                List.of(deps), supersedes, false);
    }

    @Test
    void loadsInDependencyOrder() {
        var units = List.of(unit("C", "B"), unit("B", "A"), unit("A"));
        var log = new IngestionLog(new PrintStream(new ByteArrayOutputStream()));
        var agent = new KnowledgeIngestionAgent(log);

        List<LoadResult> results = agent.ingest(units, List.of());

        assertEquals(3, results.size());
        // All should be loaded
        assertTrue(results.stream().allMatch(r -> r.status() == LoadResult.Status.LOADED));
        // A should be loaded before B, B before C
        int posA = results.stream().map(LoadResult::unitId).toList().indexOf("A");
        int posB = results.stream().map(LoadResult::unitId).toList().indexOf("B");
        int posC = results.stream().map(LoadResult::unitId).toList().indexOf("C");
        assertTrue(posA < posB, "A before B");
        assertTrue(posB < posC, "B before C");
    }

    @Test
    void skipsWhenDependencyFails() {
        // A -> B -> C, but B is forced to fail by referencing a nonexistent dep
        var units = List.of(
                unit("A"),
                unit("B", "A", "nonexistent"),
                unit("C", "B"));
        var log = new IngestionLog(new PrintStream(new ByteArrayOutputStream()));
        var agent = new KnowledgeIngestionAgent(log);

        List<LoadResult> results = agent.ingest(units, List.of());

        // A: loaded, B: skipped (nonexistent not found), C: skipped (B not available)
        assertEquals(LoadResult.Status.LOADED,
                results.stream().filter(r -> "A".equals(r.unitId())).findFirst().get().status());

        var bResult = results.stream().filter(r -> "B".equals(r.unitId())).findFirst().get();
        assertEquals(LoadResult.Status.SKIPPED, bResult.status());
        assertTrue(bResult.reason().contains("nonexistent"));

        var cResult = results.stream().filter(r -> "C".equals(r.unitId())).findFirst().get();
        assertEquals(LoadResult.Status.SKIPPED, cResult.status());
        assertTrue(cResult.reason().contains("B"));
    }

    @Test
    void logsSupersedesRelationship() {
        var units = List.of(
                unit("old-api"),
                unitWithSupersedes("new-api", "old-api"));
        var rels = List.of(new Relationship("new-api", "old-api", "supersedes"));
        var log = new IngestionLog(new PrintStream(new ByteArrayOutputStream()));
        var agent = new KnowledgeIngestionAgent(log);

        agent.ingest(units, rels);

        assertTrue(log.messages().stream()
                        .anyMatch(m -> m.contains("SUPERSEDES") && m.contains("new-api")),
                "Should log supersedes");
    }

    @Test
    void logsContradictionWarning() {
        var units = List.of(unit("policy-v1"), unit("policy-v2"));
        var rels = List.of(new Relationship("policy-v1", "policy-v2", "contradicts"));
        var log = new IngestionLog(new PrintStream(new ByteArrayOutputStream()));
        var agent = new KnowledgeIngestionAgent(log);

        agent.ingest(units, rels);

        assertTrue(log.messages().stream()
                        .anyMatch(m -> m.contains("CONFLICT") && m.contains("contradicts")),
                "Should log contradiction warning");
        assertTrue(log.warnCount() > 0, "Should have at least one warning");
    }

    @Test
    void handlesNoDependencies() {
        var units = List.of(unit("A"), unit("B"), unit("C"));
        var log = new IngestionLog(new PrintStream(new ByteArrayOutputStream()));
        var agent = new KnowledgeIngestionAgent(log);

        List<LoadResult> results = agent.ingest(units, List.of());

        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(r -> r.status() == LoadResult.Status.LOADED));
    }

    @Test
    void handlesEmptyManifest() {
        var log = new IngestionLog(new PrintStream(new ByteArrayOutputStream()));
        var agent = new KnowledgeIngestionAgent(log);

        List<LoadResult> results = agent.ingest(List.of(), List.of());
        assertEquals(0, results.size());
    }

    @Test
    void dependencyFromRelationshipsOnly() {
        // No inline depends_on, only relationship
        var units = List.of(unit("A"), unit("B"));
        var rels = List.of(new Relationship("B", "A", "depends_on"));
        var log = new IngestionLog(new PrintStream(new ByteArrayOutputStream()));
        var agent = new KnowledgeIngestionAgent(log);

        List<LoadResult> results = agent.ingest(units, rels);

        var ids = results.stream().map(LoadResult::unitId).toList();
        assertTrue(ids.indexOf("A") < ids.indexOf("B"), "A before B");
    }
}
