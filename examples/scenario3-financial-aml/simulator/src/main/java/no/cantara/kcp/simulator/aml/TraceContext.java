package no.cantara.kcp.simulator.aml;

import java.util.UUID;

/**
 * Generates W3C Trace Context traceparent headers for audit logging.
 * Format: {version}-{trace-id}-{parent-id}-{trace-flags}
 *
 * @see <a href="https://www.w3.org/TR/trace-context/">W3C Trace Context</a>
 */
public final class TraceContext {

    private TraceContext() {}

    /**
     * Generate a new traceparent value.
     * Format: 00-{32 hex trace-id}-{16 hex span-id}-01
     */
    public static String newTraceparent() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "00-" + traceId + "-" + spanId + "-01";
    }
}
