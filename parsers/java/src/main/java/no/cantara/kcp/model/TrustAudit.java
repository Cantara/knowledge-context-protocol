package no.cantara.kcp.model;

/**
 * Audit requirements within the {@code trust} block. See SPEC.md §3.2.
 *
 * <p>Both fields are advisory. {@code agentMustLog} signals that agents SHOULD
 * record access. {@code requireTraceContext} signals that agents SHOULD attach
 * W3C Trace Context headers (or generate a traceparent for local file access).
 */
public record TrustAudit(
        Boolean agentMustLog,
        Boolean requireTraceContext
) {
}
