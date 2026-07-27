package no.cantara.kcp.mcp;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@code grant_request_events} — the §17 audit trail for §3.14 escalation and grant
 * requests (v0.28, RFC-0026). Normative at conformance Level 3.
 *
 * <p><b>One row per state transition, not one per request.</b> A request's full history
 * is reconstructed by grouping rows on {@code id}. That shape is deliberate: an audit
 * trail that overwrites a row on resolution cannot answer "how long was this pending"
 * or "was it denied before it was granted", which are the questions an escalation log
 * exists to answer.
 *
 * <p><b>Nothing in this repository calls this yet, by design.</b> RFC-0026 accepted the
 * semantics and the audit trail while explicitly deferring the active request/response
 * coordination mechanism to a future RFC. The adjudicator that raises grant requests
 * lives in the planner, not here. This class is the primitive that adjudicator writes
 * through, and {@code kcp stats} reads — so the table is usable end to end rather than
 * write-only. It is a library primitive, not a claim that escalation ships in this repo.
 *
 * <p>Local-only, same as {@code usage_events}: grant requests carry internal-escalation
 * context and MUST NOT be transmitted to external services without explicit consent.
 *
 * <p>Mirrors {@link UsageLogger} — same store, same WAL mode, same fire-and-forget
 * write path. Observability must never block or break the operation it observes.
 */
public final class GrantRequestLogger {

    /** Event types, per §17. A row's {@code event_type} MUST be one of these. */
    public enum EventType { CREATED, GRANTED, DENIED, EXPIRED;
        @Override public String toString() { return name().toLowerCase(); }
    }

    /** Escalation triggers, per §3.14 / RFC-0026. */
    public enum Trigger {
        REQUIRES_APPROVAL, INSUFFICIENT_AUTHORITY_LEVEL, CONFIDENCE_BELOW_THRESHOLD;
        @Override public String toString() { return name().toLowerCase(); }
    }

    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();
    private static volatile boolean initialized = false;

    private GrantRequestLogger() {}

    /**
     * Record a state transition for a grant request.
     *
     * @param id                    the GrantRequest's own id — rows sharing it form one history
     * @param eventType             created / granted / denied / expired
     * @param trigger               why escalation was raised; null on resolution rows
     * @param taskTypeRef           task type the request concerns
     * @param agentRef              agent that raised it
     * @param bindingSourceRefs     populated for insufficient_authority_level (§3.13 sources)
     * @param currentEffectiveLevel the level in force when the request was raised
     * @param requestedLevel        the level being asked for
     * @param grantorRole           role permitted to resolve it
     * @param resolvedBy            populated on granted/denied rows only
     * @param correlationId         W3C traceparent stitching one run's events together
     */
    public static CompletableFuture<Void> log(String id, EventType eventType, Trigger trigger,
                           String taskTypeRef, String agentRef,
                           List<String> bindingSourceRefs,
                           String currentEffectiveLevel, String requestedLevel,
                           String grantorRole, String resolvedBy, String correlationId) {
        // Returns the future rather than discarding it. Still fire-and-forget — callers
        // may ignore it — but writes run on a pool, so two calls can land out of order.
        // An adjudicator recording a created->granted sequence needs them ordered, and
        // so does any test that asserts the sequence. Awaiting is how you get that.
        return CompletableFuture.runAsync(() -> {
            try {
                ensureSchema();
                insert(id, eventType, trigger, taskTypeRef, agentRef, bindingSourceRefs,
                        currentEffectiveLevel, requestedLevel, grantorRole, resolvedBy,
                        correlationId);
            } catch (Exception ignored) {
                // Swallowed on purpose, as in UsageLogger: a failed audit write must not
                // take down the adjudication it was observing. The tradeoff is that a
                // broken log is silent — which is why ensureSchema is exercised by tests
                // rather than trusted at runtime.
            }
        });
    }

    static void ensureSchema() throws Exception {
        if (initialized) return;
        WRITE_LOCK.lock();
        try {
            if (initialized) return;
            Files.createDirectories(UsageLogger.dbPath.getParent());
            try (Connection conn = getConnection();
                 Statement st = conn.createStatement()) {
                st.executeUpdate("PRAGMA journal_mode=WAL");
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS grant_request_events (
                        row_id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                        id                      TEXT    NOT NULL,
                        timestamp               TEXT    NOT NULL,
                        event_type              TEXT    NOT NULL,
                        trigger                 TEXT,
                        task_type_ref           TEXT,
                        agent_ref               TEXT,
                        binding_source_refs     TEXT,
                        current_effective_level TEXT,
                        requested_level         TEXT,
                        grantor_role            TEXT,
                        resolved_by             TEXT,
                        correlation_id          TEXT
                    )""");
                // Grouping on `id` is how a history is read, so it is the index that matters.
                st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_grant_id ON grant_request_events(id)");
                st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_grant_timestamp ON grant_request_events(timestamp)");
                st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_grant_correlation ON grant_request_events(correlation_id)");
            }
            initialized = true;
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private static void insert(String id, EventType eventType, Trigger trigger,
                               String taskTypeRef, String agentRef,
                               List<String> bindingSourceRefs,
                               String currentEffectiveLevel, String requestedLevel,
                               String grantorRole, String resolvedBy,
                               String correlationId) throws Exception {
        WRITE_LOCK.lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                 INSERT INTO grant_request_events
                     (id, timestamp, event_type, trigger, task_type_ref, agent_ref,
                      binding_source_refs, current_effective_level, requested_level,
                      grantor_role, resolved_by, correlation_id)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, id);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, eventType.toString());
            ps.setString(4, trigger != null ? trigger.toString() : null);
            ps.setString(5, taskTypeRef);
            ps.setString(6, agentRef);
            ps.setString(7, toJsonArray(bindingSourceRefs));
            ps.setString(8, currentEffectiveLevel);
            ps.setString(9, requestedLevel);
            ps.setString(10, grantorRole);
            ps.setString(11, resolvedBy);
            ps.setString(12, correlationId);
            ps.executeUpdate();
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /** §17 stores binding_source_refs as a JSON array; SQLite has no array type. */
    static String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    private static Connection getConnection() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + UsageLogger.dbPath);
    }

    /** Test seam — resets the one-shot schema guard between temp databases. */
    static void resetForTests() {
        initialized = false;
    }
}
