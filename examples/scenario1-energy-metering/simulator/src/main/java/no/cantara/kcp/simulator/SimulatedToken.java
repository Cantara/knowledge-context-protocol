package no.cantara.kcp.simulator;

import java.time.Instant;
import java.util.Set;

/**
 * An in-memory simulated OAuth2 access token.
 *
 * @param tokenValue  the opaque token string
 * @param scopes      granted scopes
 * @param expiresAt   expiration instant
 * @param clientId    the client that requested the token
 */
public record SimulatedToken(
        String tokenValue,
        Set<String> scopes,
        Instant expiresAt,
        String clientId
) {
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }

    /** Returns a masked display value for console output. */
    public String maskedValue() {
        if (tokenValue.length() <= 6) return tokenValue;
        return tokenValue.substring(0, 3) + "***" + tokenValue.substring(tokenValue.length() - 3);
    }
}
