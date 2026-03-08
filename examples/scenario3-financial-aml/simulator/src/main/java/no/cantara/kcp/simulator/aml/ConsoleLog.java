package no.cantara.kcp.simulator.aml;

import java.io.PrintStream;

/**
 * Structured console output with prefixed tags for each layer.
 * All output goes through this class so tests can capture it.
 */
public final class ConsoleLog {

    private final PrintStream out;

    public ConsoleLog(PrintStream out) {
        this.out = out;
    }

    public ConsoleLog() {
        this(System.out);
    }

    public void a2a(String message) {
        out.println("[A2A]  " + message);
    }

    public void kcp(String message) {
        out.println("[KCP]  " + message);
    }

    public void query(String label, String message) {
        out.println("[" + label + "]   " + message);
    }

    public void audit(String message) {
        out.println("[AUDIT] " + message);
    }

    public void hitl(String message) {
        out.println("[HITL] " + message);
    }

    public void blank() {
        out.println();
    }

    public void header(String title) {
        out.println("=== " + title + " ===");
    }

    public void section(String title) {
        String pad = "\u2500".repeat(Math.max(1, 60 - title.length()));
        out.println("\u2500\u2500 " + title + " " + pad);
    }

    public void plain(String message) {
        out.println(message);
    }

    public void summary(String message) {
        out.println("  " + message);
    }
}
