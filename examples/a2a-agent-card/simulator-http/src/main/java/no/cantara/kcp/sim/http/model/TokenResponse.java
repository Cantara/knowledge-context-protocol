package no.cantara.kcp.sim.http.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OAuth2 token response (RFC 6749 Section 5.1).
 *
 * <p>Returned as JSON from {@code POST /oauth2/token}:
 * <pre>
 * {
 *   "access_token": "eyJ...",
 *   "token_type": "Bearer",
 *   "expires_in": 3600,
 *   "scope": "read:protocols"
 * }
 * </pre>
 *
 * @param accessToken the issued bearer token
 * @param tokenType   always "Bearer"
 * @param expiresIn   token lifetime in seconds
 * @param scope       space-delimited granted scopes
 */
public record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") int expiresIn,
        @JsonProperty("scope") String scope
) {}
