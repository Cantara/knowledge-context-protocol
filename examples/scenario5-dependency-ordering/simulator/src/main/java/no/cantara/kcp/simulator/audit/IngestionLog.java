package no.cantara.kcp.simulator.audit;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structured log for the knowledge ingestion process.
 */
public final class IngestionLog {

    private final PrintStream out;
    private final List<String> messages = new ArrayList<>();

    public IngestionLog(PrintStream out) {
        this.out = out;
    }

    public IngestionLog() {
        this(System.out);
    }

    public void info(String message) {
        messages.add("[INFO]  " + message);
        out.println("[INFO]  " + message);
    }

    public void warn(String message) {
        messages.add("[WARN]  " + message);
        out.println("[WARN]  " + message);
    }

    public void error(String message) {
        messages.add("[ERROR] " + message);
        out.println("[ERROR] " + message);
    }

    public void loaded(String unitId) {
        info("LOADED: " + unitId);
    }

    public void skipped(String unitId, String reason) {
        warn("SKIPPED: " + unitId + " — " + reason);
    }

    public void supersedes(String newUnit, String oldUnit) {
        info("SUPERSEDES: " + newUnit + " supersedes " + oldUnit + " — prefer this version");
    }

    public void contradicts(String unitA, String unitB) {
        warn("CONFLICT: " + unitA + " contradicts " + unitB);
    }

    public List<String> messages() {
        return Collections.unmodifiableList(messages);
    }

    public long warnCount() {
        return messages.stream().filter(m -> m.startsWith("[WARN]")).count();
    }

    public long errorCount() {
        return messages.stream().filter(m -> m.startsWith("[ERROR]")).count();
    }
}
