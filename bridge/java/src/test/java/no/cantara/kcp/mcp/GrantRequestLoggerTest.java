package no.cantara.kcp.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * grant_request_events — §17 audit trail for §3.14 escalation (v0.28, RFC-0026).
 *
 * <p>Writes go through a CompletableFuture and swallow their exceptions, so a broken
 * schema would fail silently in production. These tests drive ensureSchema and insert
 * synchronously for exactly that reason: the failure mode this table has is being
 * unwritable without anyone noticing.
 */
class GrantRequestLoggerTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void isolateDatabase() {
        UsageLogger.dbPath = tmp.resolve("usage.db");
        GrantRequestLogger.resetForTests();
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + UsageLogger.dbPath);
    }

    /** Wait for an async writer to create its table. A fixed sleep loses this race on a
     *  slow runner — CI reported "usage_events lost" from exactly that. */
    private void awaitTable(String name) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection c = open(); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='" + name + "'")) {
                if (rs.next()) return;
            }
            Thread.sleep(50);
        }
        fail("table " + name + " never appeared within 10s");
    }

    @Test
    void createsTheTableWithEverySpecifiedColumn() throws Exception {
        GrantRequestLogger.ensureSchema();
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(grant_request_events)")) {
            var columns = new java.util.HashSet<String>();
            while (rs.next()) columns.add(rs.getString("name"));
            // §17 names these twelve. A missing column is a silent conformance failure:
            // inserts still succeed, the data is simply never recorded.
            assertTrue(columns.containsAll(List.of(
                    "id", "timestamp", "event_type", "trigger", "task_type_ref",
                    "agent_ref", "binding_source_refs", "current_effective_level",
                    "requested_level", "grantor_role", "resolved_by", "correlation_id")),
                    "missing §17 columns; present: " + columns);
        }
    }

    @Test
    void coexistsWithUsageEvents() throws Exception {
        // §17 puts all four tables in one store. Creating this one must not disturb
        // usage_events, which the bridge writes on every search.
        UsageLogger.logSearch("proj", "q", 1, 10);
        awaitTable("usage_events");   // UsageLogger has no awaitable seam; poll instead
        GrantRequestLogger.ensureSchema();
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT name FROM sqlite_master WHERE type='table'")) {
            var tables = new java.util.HashSet<String>();
            while (rs.next()) tables.add(rs.getString("name"));
            assertTrue(tables.contains("usage_events"), "usage_events lost: " + tables);
            assertTrue(tables.contains("grant_request_events"), "table missing: " + tables);
        }
    }

    @Test
    void oneRowPerTransition_historyReconstructsByGroupingOnId() throws Exception {
        // The shape §17 requires: a request's history is rows sharing an id, not one row
        // mutated in place. An overwriting log cannot answer "how long was this pending"
        // or "was it denied before it was granted".
        // Await each write. Writes run on a thread pool, so firing both and sleeping
        // races: CI observed `granted` landing first. The audit trail's whole point is
        // ordered history, so the ordering must be established by the caller, not hoped
        // for — which is why log() returns its future.
        GrantRequestLogger.log("req-1", GrantRequestLogger.EventType.CREATED,
                GrantRequestLogger.Trigger.INSUFFICIENT_AUTHORITY_LEVEL,
                "deploy", "agent-a", List.of("policy.yaml", "tenant.yaml"),
                "suggest", "commit", "release-manager", null, "trace-1").join();
        GrantRequestLogger.log("req-1", GrantRequestLogger.EventType.GRANTED,
                null, "deploy", "agent-a", null,
                "suggest", "commit", "release-manager", "totto", "trace-1").join();

        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT event_type, trigger, resolved_by, binding_source_refs "
                 + "FROM grant_request_events WHERE id='req-1' ORDER BY row_id")) {
            assertTrue(rs.next());
            assertEquals("created", rs.getString("event_type"));
            assertEquals("insufficient_authority_level", rs.getString("trigger"));
            assertNull(rs.getString("resolved_by"), "resolved_by belongs on resolution rows only");
            assertEquals("[\"policy.yaml\",\"tenant.yaml\"]", rs.getString("binding_source_refs"));

            assertTrue(rs.next(), "the granted transition should be a second row, not an update");
            assertEquals("granted", rs.getString("event_type"));
            assertEquals("totto", rs.getString("resolved_by"));
            assertFalse(rs.next());
        }
    }

    @Test
    void bindingSourceRefsSerialiseAsJsonAndEscape() {
        assertNull(GrantRequestLogger.toJsonArray(null));
        assertNull(GrantRequestLogger.toJsonArray(List.of()));
        assertEquals("[\"a\"]", GrantRequestLogger.toJsonArray(List.of("a")));
        assertEquals("[\"a\",\"b\"]", GrantRequestLogger.toJsonArray(List.of("a", "b")));
        // A quote in a source ref would otherwise produce invalid JSON that no reader
        // can parse — and the reader is a different language.
        assertEquals("[\"a\\\"b\"]", GrantRequestLogger.toJsonArray(List.of("a\"b")));
    }

    @Test
    void eventTypeAndTriggerSerialiseToTheSpecVocabulary() {
        assertEquals("created", GrantRequestLogger.EventType.CREATED.toString());
        assertEquals("expired", GrantRequestLogger.EventType.EXPIRED.toString());
        assertEquals("requires_approval",
                GrantRequestLogger.Trigger.REQUIRES_APPROVAL.toString());
        assertEquals("confidence_below_threshold",
                GrantRequestLogger.Trigger.CONFIDENCE_BELOW_THRESHOLD.toString());
    }
}
