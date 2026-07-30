package no.cantara.kcp.model;

import java.util.List;

/**
 * The envelope bounding a {@code kind: skill} unit. See SPEC.md §4.3a.
 *
 * <p>Absent is not the same as empty. A unit with no {@code action_scope} authorizes
 * nothing, so the parser yields {@code null} rather than an instance with empty lists —
 * "declares no scope" and "declares a scope permitting nothing" are different statements
 * and must stay distinguishable.
 *
 * <p>Sub-fields mirror {@code shared/src/parser.ts} {@code parseActionScope}.
 */
public record ActionScope(
        List<String> tools,        // tool names the procedure may invoke
        List<String> paths,        // paths (globs permitted) it may read or write
        List<String> capabilities, // named capabilities it requires or exercises
        Spend spend,               // purchases it may make (§4.3a.1)
        DenyScope deny             // §4.3a (v0.31, RFC-0029) — explicit prohibitions; override the allowlist, fail-closed
) {
    public ActionScope {
        tools = tools != null ? List.copyOf(tools) : null;
        paths = paths != null ? List.copyOf(paths) : null;
        capabilities = capabilities != null ? List.copyOf(capabilities) : null;
    }
}
