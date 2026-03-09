package no.cantara.kcp.simulator.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records each request made by an agent, including whether it was within
 * the advisory rate limit.
 */
public final class AuditLog {

    /**
     * A single audit entry.
     *
     * @param unitId      the knowledge unit accessed
     * @param agentName   the agent making the request
     * @param timestampMs simulated timestamp in epoch millis
     * @param withinLimit true if the request was within advisory limits
     * @param message     human-readable description
     */
    public record Entry(String unitId, String agentName, long timestampMs,
                        boolean withinLimit, String message) {}

    private final List<Entry> entries = new ArrayList<>();

    public void record(String unitId, String agentName, long timestampMs,
                       boolean withinLimit, String message) {
        entries.add(new Entry(unitId, agentName, timestampMs, withinLimit, message));
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** Count entries where the advisory limit was violated. */
    public long violationCount() {
        return entries.stream().filter(e -> !e.withinLimit()).count();
    }

    /** Count entries where the advisory limit was respected. */
    public long compliantCount() {
        return entries.stream().filter(Entry::withinLimit).count();
    }

    /** Get all violations for a specific unit. */
    public List<Entry> violationsForUnit(String unitId) {
        return entries.stream()
                .filter(e -> !e.withinLimit() && unitId.equals(e.unitId()))
                .toList();
    }

    /** Get all entries for a specific agent. */
    public List<Entry> entriesForAgent(String agentName) {
        return entries.stream()
                .filter(e -> agentName.equals(e.agentName()))
                .toList();
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }
}
