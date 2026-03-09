package no.cantara.kcp.sim.http.model;

/**
 * OAuth2 client_credentials token request.
 *
 * <p>Sent as form-encoded POST to {@code /oauth2/token}:
 * <pre>
 *   grant_type=client_credentials&scope=read:protocols
 * </pre>
 *
 * <p>We parse this from the form body rather than JSON, matching
 * the real OAuth2 client_credentials flow (RFC 6749 Section 4.4).
 *
 * @param grantType must be "client_credentials"
 * @param scope     space-delimited list of requested scopes
 * @param clientId  the client identifier (from Basic auth or body)
 */
public record TokenRequest(
        String grantType,
        String scope,
        String clientId
) {
    /**
     * Parse from URL-encoded form body.
     * Example: {@code grant_type=client_credentials&scope=read:protocols&client_id=orchestrator}
     */
    public static TokenRequest fromFormBody(String body) {
        String grantType = null;
        String scope = null;
        String clientId = null;

        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) continue;
            switch (kv[0]) {
                case "grant_type" -> grantType = decodeUrl(kv[1]);
                case "scope" -> scope = decodeUrl(kv[1]);
                case "client_id" -> clientId = decodeUrl(kv[1]);
            }
        }
        return new TokenRequest(grantType, scope, clientId);
    }

    private static String decodeUrl(String value) {
        return value.replace("+", " ").replace("%20", " ");
    }
}
