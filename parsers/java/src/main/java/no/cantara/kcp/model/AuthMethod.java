package no.cantara.kcp.model;

import java.util.List;

/**
 * A single authentication method declaration within the {@code auth.methods} list.
 *
 * <p>Types defined by the spec (v0.22): {@code none}, {@code oauth2}, {@code api_key},
 * {@code bearer_token}, {@code spiffe}, {@code did}, {@code http_signature}.
 * Unknown types MUST be silently ignored per SPEC.md §7.
 */
public record AuthMethod(
        String type,
        String issuer,
        List<String> scopes,
        String header,
        String registrationUrl,
        String trustDomain,          // spiffe
        List<String> supportedMethods, // did
        String keyId,                // http_signature
        String algorithm             // http_signature
) {
    public AuthMethod {
        scopes = scopes != null ? List.copyOf(scopes) : List.of();
        supportedMethods = supportedMethods != null ? List.copyOf(supportedMethods) : List.of();
    }
}
