package no.cantara.kcp.sim.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import no.cantara.kcp.sim.http.model.TokenRequest;
import no.cantara.kcp.sim.http.model.TokenResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedded OAuth2 Authorization Server.
 *
 * <p>Runs on port 9000 (configurable) and provides two endpoints:
 * <ul>
 *   <li>{@code POST /oauth2/token} — issues bearer tokens (client_credentials flow)</li>
 *   <li>{@code POST /oauth2/introspect} — validates a token and returns its metadata</li>
 * </ul>
 *
 * <p>This is a minimal but real HTTP implementation of the OAuth2 flows needed
 * for the A2A + KCP simulation. Tokens are stored in-memory and validated
 * by the Research Agent via the introspect endpoint — just like a real
 * microservice architecture where the resource server checks tokens with
 * the authorization server.
 */
public final class AuthServer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TOKEN_LIFETIME_SECONDS = 3600;

    /** Known scopes that this auth server recognises. */
    private static final Set<String> KNOWN_SCOPES = Set.of(
            "read:guidelines", "read:protocols", "read:cohort", "research.read"
    );

    private final int port;
    private HttpServer server;

    // In-memory token store: tokenValue -> TokenRecord
    private final Map<String, TokenRecord> tokens = new ConcurrentHashMap<>();

    /** Internal record of an issued token. */
    record TokenRecord(String tokenValue, Set<String> scopes, Instant expiresAt, String clientId) {
        boolean isExpired() { return Instant.now().isAfter(expiresAt); }
        boolean hasScope(String scope) { return scopes.contains(scope); }
    }

    public AuthServer(int port) {
        this.port = port;
    }

    /**
     * Start the HTTP server. Binds to the configured port on localhost.
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", port), 0);

        // POST /oauth2/token — client_credentials token issuance
        server.createContext("/oauth2/token", this::handleToken);

        // POST /oauth2/introspect — token introspection (RFC 7662)
        server.createContext("/oauth2/introspect", this::handleIntrospect);

        server.setExecutor(null); // default single-threaded executor
        server.start();
        log("Auth Server started on http://localhost:" + port);
    }

    /**
     * Stop the server gracefully.
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            log("Auth Server stopped");
        }
    }

    /**
     * Handle POST /oauth2/token — OAuth2 client_credentials flow.
     *
     * <p>Expects form-encoded body:
     * {@code grant_type=client_credentials&scope=read:protocols&client_id=orchestrator}
     *
     * <p>Returns JSON token response per RFC 6749 Section 5.1.
     */
    private void handleToken(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        TokenRequest req = TokenRequest.fromFormBody(body);

        // Validate grant_type
        if (!"client_credentials".equals(req.grantType())) {
            log("Token request rejected: unsupported grant_type=" + req.grantType());
            sendJson(exchange, 400, Map.of(
                    "error", "unsupported_grant_type",
                    "error_description", "Only client_credentials is supported"
            ));
            return;
        }

        // Parse requested scopes
        Set<String> requestedScopes = req.scope() != null
                ? Set.of(req.scope().split("\\s+"))
                : Set.of();

        // Validate scopes — only grant known scopes
        Set<String> grantedScopes = new java.util.HashSet<>();
        for (String scope : requestedScopes) {
            if (KNOWN_SCOPES.contains(scope)) {
                grantedScopes.add(scope);
            }
        }

        if (grantedScopes.isEmpty() && !requestedScopes.isEmpty()) {
            log("Token request rejected: no valid scopes in [" + req.scope() + "]");
            sendJson(exchange, 400, Map.of(
                    "error", "invalid_scope",
                    "error_description", "None of the requested scopes are valid"
            ));
            return;
        }

        // Issue the token
        String tokenValue = generateTokenValue();
        String clientId = req.clientId() != null ? req.clientId() : "anonymous";
        Instant expiresAt = Instant.now().plusSeconds(TOKEN_LIFETIME_SECONDS);

        tokens.put(tokenValue, new TokenRecord(tokenValue, Set.copyOf(grantedScopes), expiresAt, clientId));

        String scopeStr = String.join(" ", grantedScopes);
        TokenResponse response = new TokenResponse(tokenValue, "Bearer", TOKEN_LIFETIME_SECONDS, scopeStr);

        log("Token issued to client=" + clientId + " scopes=[" + scopeStr + "]");
        sendJson(exchange, 200, response);
    }

    /**
     * Handle POST /oauth2/introspect — Token introspection (RFC 7662).
     *
     * <p>The Research Agent calls this to validate a bearer token before
     * granting access to a knowledge unit. This is how real microservice
     * architectures validate tokens: the resource server asks the auth server.
     *
     * <p>Expects form-encoded body: {@code token=<token_value>}
     *
     * <p>Returns JSON with {@code active: true/false} plus token metadata.
     */
    private void handleIntrospect(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String tokenValue = null;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && "token".equals(kv[0])) {
                tokenValue = kv[1];
            }
        }

        if (tokenValue == null || tokenValue.isBlank()) {
            sendJson(exchange, 200, Map.of("active", false));
            return;
        }

        TokenRecord record = tokens.get(tokenValue);
        if (record == null || record.isExpired()) {
            log("Introspect: token not found or expired");
            sendJson(exchange, 200, Map.of("active", false));
            return;
        }

        log("Introspect: token valid, client=" + record.clientId() + " scopes=" + record.scopes());
        sendJson(exchange, 200, Map.of(
                "active", true,
                "client_id", record.clientId(),
                "scope", String.join(" ", record.scopes()),
                "token_type", "Bearer",
                "exp", record.expiresAt().getEpochSecond()
        ));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Generate a token value that looks plausibly like a real JWT prefix. */
    private String generateTokenValue() {
        String raw = UUID.randomUUID().toString().replace("-", "");
        return "sim_" + raw.substring(0, 24);
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] json = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, json.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json);
        }
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", message));
    }

    private static void log(String message) {
        System.out.println("[AUTH]  " + message);
    }

    /** Expose the port for testing. */
    public int port() { return port; }
}
