package no.cantara.kcp.model;

import java.util.List;

/**
 * A single authentication method declaration within the {@code auth.methods} list.
 *
 * <p>Core types defined by the spec: {@code none}, {@code oauth2}, {@code api_key}.
 * Unknown types MUST be silently ignored per SPEC.md §7.
 */
public record AuthMethod(
        String type,
        String issuer,
        List<String> scopes,
        String header,
        String registrationUrl
) {
    public AuthMethod {
        scopes = scopes != null ? List.copyOf(scopes) : List.of();
    }
}
