package no.cantara.kcp.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Logs KCP bridge usage events to ~/.kcp/usage.db (SQLite).
 * See RFC-0017: Observability Hooks.
 *
 * Design constraints:
 * - Always non-blocking: writes on a daemon thread via CompletableFuture
 * - Never throws: all errors silently swallowed to avoid disrupting MCP responses
 * - Thread-safe: process-wide lock on DB writes
 * - WAL mode: allows concurrent readers while writing
 */
public final class UsageLogger {

    static Path dbPath = Path.of(System.getProperty("user.home"), ".kcp", "usage.db");
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();
    private static volatile boolean initialized = false;

    private UsageLogger() {}

    /**
     * Log a search_knowledge event asynchronously.
     *
     * @param project            manifest project name
     * @param query              the search query string
     * @param resultCount        number of units returned
     * @param manifestTokenTotal sum of hints.token_estimate across all manifest units
     */
    public static void logSearch(String project, String query,
                                  int resultCount, int manifestTokenTotal) {
        CompletableFuture.runAsync(() -> {
            try {
                ensureSchema();
                insert("search", project, query, null, resultCount, null, manifestTokenTotal);
            } catch (Exception ignored) {}
        });
    }

    /**
     * Log a get_unit event asynchronously.
     *
     * @param project            manifest project name
     * @param unitId             the unit id fetched
     * @param tokenEstimate      hints.token_estimate for this unit (null if not declared)
     * @param manifestTokenTotal sum of hints.token_estimate across all manifest units
     */
    public static void logGetUnit(String project, String unitId,
                                   Integer tokenEstimate, int manifestTokenTotal) {
        CompletableFuture.runAsync(() -> {
            try {
                ensureSchema();
                insert("get_unit", project, null, unitId, null, tokenEstimate, manifestTokenTotal);
            } catch (Exception ignored) {}
        });
    }

    private static void ensureSchema() throws Exception {
        if (initialized) return;
        WRITE_LOCK.lock();
        try {
            if (initialized) return;
            Files.createDirectories(dbPath.getParent());
            try (Connection conn = getConnection();
                 Statement st = conn.createStatement()) {
                st.executeUpdate("PRAGMA journal_mode=WAL");
                st.executeUpdate("PRAGMA user_version=1");
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS usage_events (
                        id                   INTEGER PRIMARY KEY AUTOINCREMENT,
                        timestamp            TEXT    NOT NULL,
                        event_type           TEXT    NOT NULL,
                        project              TEXT    NOT NULL,
                        query                TEXT,
                        unit_id              TEXT,
                        result_count         INTEGER,
                        token_estimate       INTEGER,
                        manifest_token_total INTEGER,
                        session_id           TEXT
                    )""");
                st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_usage_timestamp ON usage_events(timestamp)");
                st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_usage_project ON usage_events(project)");
                st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_usage_unit_id ON usage_events(unit_id)");
            }
            initialized = true;
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private static void insert(String eventType, String project,
                                String query, String unitId,
                                Integer resultCount, Integer tokenEstimate,
                                Integer manifestTokenTotal) throws Exception {
        WRITE_LOCK.lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                 INSERT INTO usage_events
                     (timestamp, event_type, project, query, unit_id,
                      result_count, token_estimate, manifest_token_total)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, eventType);
            ps.setString(3, project != null ? project : "unknown");
            ps.setString(4, query);
            ps.setString(5, unitId);
            if (resultCount != null) ps.setInt(6, resultCount); else ps.setNull(6, java.sql.Types.INTEGER);
            if (tokenEstimate != null) ps.setInt(7, tokenEstimate); else ps.setNull(7, java.sql.Types.INTEGER);
            if (manifestTokenTotal != null) ps.setInt(8, manifestTokenTotal); else ps.setNull(8, java.sql.Types.INTEGER);
            ps.executeUpdate();
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private static Connection getConnection() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }
}
