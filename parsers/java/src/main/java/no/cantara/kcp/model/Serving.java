package no.cantara.kcp.model;

import java.util.List;

/** Signed declaration of authoritative serving endpoints. See SPEC.md §3.12 (v0.26). */
public record Serving(
        List<String> manifest,   // HTTPS URLs authoritatively serving this manifest
        List<String> mcp         // HTTPS URLs of authorized MCP endpoints
) {}
