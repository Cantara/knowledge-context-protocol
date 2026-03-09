package no.cantara.kcp.simulator.agent;

import no.cantara.kcp.simulator.audit.IngestionLog;
import no.cantara.kcp.simulator.graph.DependencyGraph;
import no.cantara.kcp.simulator.graph.TopologicalSorter;
import no.cantara.kcp.simulator.model.KnowledgeUnit;
import no.cantara.kcp.simulator.model.LoadResult;
import no.cantara.kcp.simulator.model.Relationship;

import java.util.*;

/**
 * An agent that loads knowledge units in topological (dependency) order.
 * <p>
 * Behaviour:
 * <ul>
 *   <li>Uses {@link TopologicalSorter} to determine safe load order</li>
 *   <li>If a dependency failed/was skipped, skips the dependent</li>
 *   <li>Logs supersedes relationships when encountered</li>
 *   <li>Flags contradicts relationships as warnings</li>
 * </ul>
 */
public final class KnowledgeIngestionAgent {

    private final IngestionLog log;

    public KnowledgeIngestionAgent(IngestionLog log) {
        this.log = log;
    }

    /**
     * Load all units in dependency order.
     *
     * @param units         the knowledge units
     * @param relationships all relationships
     * @return result for each unit
     */
    public List<LoadResult> ingest(List<KnowledgeUnit> units, List<Relationship> relationships) {
        // Build graph and get load order
        DependencyGraph graph = DependencyGraph.build(units, relationships);
        List<String> loadOrder = TopologicalSorter.sort(graph);

        // Index units and relationships for lookup
        Map<String, KnowledgeUnit> unitIndex = new LinkedHashMap<>();
        for (KnowledgeUnit u : units) {
            unitIndex.put(u.id(), u);
        }

        // Track supersedes and contradicts for logging
        Map<String, String> supersedesMap = new HashMap<>(); // newId -> oldId
        List<String[]> contradictions = new ArrayList<>(); // [from, to]

        for (Relationship rel : relationships) {
            if (Relationship.SUPERSEDES.equals(rel.type())) {
                supersedesMap.put(rel.from(), rel.to());
            } else if (Relationship.CONTRADICTS.equals(rel.type())) {
                contradictions.add(new String[]{rel.from(), rel.to()});
            }
        }

        // Also check inline supersedes fields
        for (KnowledgeUnit u : units) {
            if (u.supersedes() != null) {
                supersedesMap.put(u.id(), u.supersedes());
            }
        }

        // Load in order
        Set<String> loadedSuccessfully = new HashSet<>();
        Set<String> failedOrSkipped = new HashSet<>();
        List<LoadResult> results = new ArrayList<>();

        for (String unitId : loadOrder) {
            KnowledgeUnit unit = unitIndex.get(unitId);
            if (unit == null) {
                // Node exists in graph (from relationships) but not in units list
                LoadResult result = LoadResult.skipped(unitId, "unit not found in manifest");
                results.add(result);
                failedOrSkipped.add(unitId);
                log.skipped(unitId, "unit not found in manifest");
                continue;
            }

            // Check if all dependencies are loaded
            Set<String> deps = graph.dependenciesOf(unitId);
            String failedDep = null;
            for (String dep : deps) {
                if (failedOrSkipped.contains(dep)) {
                    failedDep = dep;
                    break;
                }
            }

            if (failedDep != null) {
                LoadResult result = LoadResult.skipped(unitId, "dependency " + failedDep + " not available");
                results.add(result);
                failedOrSkipped.add(unitId);
                log.skipped(unitId, "dependency " + failedDep + " not available");
                continue;
            }

            // Load the unit
            loadedSuccessfully.add(unitId);
            results.add(LoadResult.loaded(unitId));
            log.loaded(unitId);

            // Log supersedes if applicable
            if (supersedesMap.containsKey(unitId)) {
                log.supersedes(unitId, supersedesMap.get(unitId));
            }
        }

        // Log contradictions (after all loading, so both sides are known)
        for (String[] contradiction : contradictions) {
            log.contradicts(contradiction[0], contradiction[1]);
        }

        return Collections.unmodifiableList(results);
    }
}
