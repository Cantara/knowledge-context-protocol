package no.cantara.kcp.simulator.legal;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory OAuth2 token issuer. No real HTTP -- tokens are generated
 * and validated entirely in-process.
 */
public final class SimulatedOAuth2 {

    private final Map<String, SimulatedToken> tokens = new ConcurrentHashMap<>();
    private final int tokenLifetimeSeconds;

    public SimulatedOAuth2(int tokenLifetimeSeconds) {
        this.tokenLifetimeSeconds = tokenLifetimeSeconds;
    }

    public SimulatedOAuth2() {
        this(3600);
    }

    /**
     * Issue a token for the client_credentials flow.
     *
     * @param clientId  the requesting client
     * @param scopes    requested scopes (all granted in simulation)
     * @return the issued token
     */
    public SimulatedToken issueToken(String clientId, Set<String> scopes) {
        String raw = UUID.randomUUID().toString().replace("-", "");
        String tokenValue = "eyJ" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes()).substring(0, 6) + "sim";
        Instant expiresAt = Instant.now().plusSeconds(tokenLifetimeSeconds);
        SimulatedToken token = new SimulatedToken(tokenValue, Set.copyOf(scopes), expiresAt, clientId);
        tokens.put(tokenValue, token);
        return token;
    }

    /**
     * Validate a token and optionally check a required scope.
     *
     * @param tokenValue  the token string to validate
     * @param requiredScope  scope to check, or null to skip scope check
     * @return true if token is valid and (if requiredScope is non-null) has the scope
     */
    public boolean validate(String tokenValue, String requiredScope) {
        SimulatedToken token = tokens.get(tokenValue);
        if (token == null || token.isExpired()) return false;
        if (requiredScope != null) return token.hasScope(requiredScope);
        return true;
    }

    /** Look up a token by its value. */
    public SimulatedToken getToken(String tokenValue) {
        return tokens.get(tokenValue);
    }
}
