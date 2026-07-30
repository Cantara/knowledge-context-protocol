package no.cantara.kcp.model;

import java.util.List;

/**
 * Explicit negative scope on a {@code kind: skill} action_scope. See SPEC.md §4.3a (v0.31, RFC-0029).
 *
 * <p>Same {@code {tools, paths, capabilities}} shape as the allowlist, but every entry is a
 * PROHIBITION: a token listed here is denied even when the allowlist grants it. {@code deny}
 * is checked in addition to — and overrides — the allowlist, fail-closed. Mirrors
 * {@code shared/src/parser.ts} {@code parseDenyScope}.
 */
public record DenyScope(
        List<String> tools,        // tool names the procedure MUST NOT invoke, even if allowlisted
        List<String> paths,        // paths (globs permitted) it MUST NOT touch, even if allowlisted
        List<String> capabilities  // named capabilities it MUST NOT exercise, even if allowlisted
) {
    public DenyScope {
        tools = tools != null ? List.copyOf(tools) : null;
        paths = paths != null ? List.copyOf(paths) : null;
        capabilities = capabilities != null ? List.copyOf(capabilities) : null;
    }
}
