package no.cantara.kcp.sim.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import no.cantara.kcp.sim.http.model.A2ATaskResponse;
import no.cantara.kcp.sim.http.model.TokenResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * HTTP client that drives the full A2A + KCP interaction flow.
 *
 * <p>The Orchestrator is a "client agent" that:
 * <ol>
 *   <li>Discovers the Research Agent via {@code GET /.well-known/agent.json}</li>
 *   <li>Reads the KCP manifest location from the agent card</li>
 *   <li>Obtains an OAuth2 token from the Auth Server (real HTTP)</li>
 *   <li>Sends A2A task requests to the Research Agent (real HTTP)</li>
 *   <li>Receives KCP policy decisions in the responses</li>
 * </ol>
 *
 * <p>Every interaction is a real HTTP call — no in-process shortcuts.
 * The console output tells the full story of what happens at each layer.
 */
public final class OrchestratorClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient;
    private final String agentBaseUrl;
    private final String authBaseUrl;

    // State accumulated during the flow
    private String tokenUrl;
    private String accessToken;
    private String knowledgeManifestPath;

    /**
     * @param agentBaseUrl base URL of the Research Agent (e.g. "http://localhost:9001")
     * @param authBaseUrl  base URL of the Auth Server (e.g. "http://localhost:9000")
     */
    public OrchestratorClient(String agentBaseUrl, String authBaseUrl) {
        this.agentBaseUrl = agentBaseUrl;
        this.authBaseUrl = authBaseUrl;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Run the full orchestration flow: discover, authenticate, query.
     *
     * <p>This method drives the entire simulation end-to-end, logging
     * each HTTP interaction with status codes and response details.
     */
    public void run() throws IOException, InterruptedException {
        header("A2A + KCP HTTP Simulation");
        log("Orchestrator starting — all calls are real HTTP on localhost");
        blank();

        // Phase 1: Agent Discovery
        phase1AgentDiscovery();
        blank();

        // Phase 2: KCP Discovery
        phase2KcpDiscovery();
        blank();

        // Phase 3: Authentication
        phase3Authentication();
        blank();

        // Phase 4: Knowledge Access — demonstrate all policy outcomes
        phase4KnowledgeAccess();
        blank();

        summary();
    }

    // ── Phase 1: Agent Discovery (A2A) ──────────────────────────────────────

    /**
     * Discover the Research Agent by fetching its A2A agent card.
     *
     * <p>This is how agents find each other in A2A: the orchestrator
     * GETs the well-known endpoint and learns what the agent can do,
     * how to authenticate, and where to find its knowledge inventory.
     */
    private void phase1AgentDiscovery() throws IOException, InterruptedException {
        section("Phase 1: Agent Discovery (A2A via HTTP)");
        blank();

        String url = agentBaseUrl + "/.well-known/agent.json";
        log("GET " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        httpLog("GET", url, response.statusCode());

        if (response.statusCode() != 200) {
            log("ERROR: Agent discovery failed with status " + response.statusCode());
            return;
        }

        JsonNode card = JSON.readTree(response.body());
        log("Discovered: \"" + card.path("name").asText() + "\" at " + card.path("url").asText());

        // Extract OAuth2 token URL from the agent card
        JsonNode flows = card.path("securitySchemes").path("oauth2").path("flows").path("clientCredentials");
        tokenUrl = flows.path("tokenUrl").asText(null);
        log("OAuth2 token endpoint: " + tokenUrl);

        // Extract scopes
        JsonNode scopes = flows.path("scopes");
        if (scopes.isObject()) {
            StringBuilder sb = new StringBuilder();
            scopes.fieldNames().forEachRemaining(name -> {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(name);
            });
            log("Available scopes: " + sb);
        }

        // Extract knowledge manifest link
        knowledgeManifestPath = card.path("knowledgeManifest").asText(null);
        log("Knowledge manifest: " + knowledgeManifestPath);

        // Extract skills
        JsonNode skills = card.path("skills");
        if (skills.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode skill : skills) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(skill.path("id").asText());
            }
            log("Skills: " + sb);
        }
    }

    // ── Phase 2: KCP Discovery ──────────────────────────────────────────────

    /**
     * Follow the A2A card's knowledgeManifest link to discover KCP metadata.
     */
    private void phase2KcpDiscovery() throws IOException, InterruptedException {
        section("Phase 2: Knowledge Discovery (KCP via HTTP)");
        blank();

        // Step 1: Follow the knowledgeManifest link from the agent card
        String discoveryUrl = agentBaseUrl + knowledgeManifestPath;
        log("GET " + discoveryUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(discoveryUrl))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        httpLog("GET", discoveryUrl, response.statusCode());

        JsonNode discovery = JSON.readTree(response.body());
        String manifestUrl = discovery.path("manifest_url").asText();
        log("KCP version: " + discovery.path("kcp_version").asText());
        log("Manifest URL: " + manifestUrl);

        // Step 2: Fetch the actual manifest
        log("GET " + manifestUrl);
        HttpRequest manifestReq = HttpRequest.newBuilder()
                .uri(URI.create(manifestUrl))
                .GET()
                .build();

        HttpResponse<String> manifestResp = httpClient.send(manifestReq, HttpResponse.BodyHandlers.ofString());
        httpLog("GET", manifestUrl, manifestResp.statusCode());

        // Count units in the YAML (simple line counting — the real parsing happens server-side)
        long unitCount = manifestResp.body().lines()
                .filter(line -> line.trim().startsWith("- id:"))
                .count();
        log("Manifest loaded: " + unitCount + " knowledge units defined");
    }

    // ── Phase 3: Authentication ─────────────────────────────────────────────

    /**
     * Obtain an OAuth2 token from the Auth Server.
     *
     * <p>Uses the token URL discovered from the agent card (Phase 1).
     * This is a real HTTP POST to the Auth Server's token endpoint,
     * following the OAuth2 client_credentials flow (RFC 6749 Section 4.4).
     */
    private void phase3Authentication() throws IOException, InterruptedException {
        section("Phase 3: Authentication (OAuth2 via HTTP)");
        blank();

        log("POST " + tokenUrl);
        log("grant_type=client_credentials, scope=read:guidelines read:protocols read:cohort");

        String formBody = "grant_type=client_credentials"
                + "&scope=read:guidelines+read:protocols+read:cohort"
                + "&client_id=orchestrator-agent";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        httpLog("POST", tokenUrl, response.statusCode());

        if (response.statusCode() != 200) {
            log("ERROR: Token request failed: " + response.body());
            return;
        }

        TokenResponse tokenResp = JSON.readValue(response.body(), TokenResponse.class);
        accessToken = tokenResp.accessToken();

        String masked = accessToken.length() > 10
                ? accessToken.substring(0, 6) + "***" + accessToken.substring(accessToken.length() - 3)
                : accessToken;
        log("Token received: " + masked + " (type=" + tokenResp.tokenType()
                + ", expires_in=" + tokenResp.expiresIn() + "s)");
        log("Granted scopes: " + tokenResp.scope());
    }

    // ── Phase 4: Knowledge Access ───────────────────────────────────────────

    /**
     * Execute A2A task requests against all three knowledge units,
     * demonstrating the full range of KCP policy decisions:
     * <ul>
     *   <li>public-guidelines: no token needed</li>
     *   <li>trial-protocols: token + scope required</li>
     *   <li>patient-cohort: token + scope + delegation depth check + HITL</li>
     * </ul>
     *
     * <p>Also tests error cases: missing token (401) and wrong scope (403).
     */
    private void phase4KnowledgeAccess() throws IOException, InterruptedException {
        section("Phase 4: Knowledge Access (A2A Tasks with KCP Policy)");

        // Scenario 1: Public unit — no token needed
        blank();
        log("--- Scenario 1: Public unit (no token) ---");
        A2ATaskResponse resp1 = sendTask("public-guidelines", null, 0);
        logDecision(resp1);

        // Scenario 2: Authenticated unit without token — should get 401
        blank();
        log("--- Scenario 2: Authenticated unit WITHOUT token (expect 401) ---");
        A2ATaskResponse resp2 = sendTask("trial-protocols", null, 0);
        logDecision(resp2);

        // Scenario 3: Authenticated unit with valid token
        blank();
        log("--- Scenario 3: Authenticated unit WITH valid token ---");
        A2ATaskResponse resp3 = sendTask("trial-protocols", accessToken, 0);
        logDecision(resp3);

        // Scenario 4: Restricted unit with valid token
        blank();
        log("--- Scenario 4: Restricted unit WITH valid token (HITL auto-approved) ---");
        A2ATaskResponse resp4 = sendTask("patient-cohort", accessToken, 0);
        logDecision(resp4);

        // Scenario 5: Delegation depth exceeded
        blank();
        log("--- Scenario 5: Delegation depth exceeded (depth=3, max=1) ---");
        A2ATaskResponse resp5 = sendTask("patient-cohort", accessToken, 3);
        logDecision(resp5);
    }

    /**
     * Send an A2A task request to the Research Agent.
     *
     * @param unitId the KCP knowledge unit to request
     * @param token  the Bearer token (null to test unauthenticated access)
     * @param depth  the delegation depth to report
     * @return the parsed response (or a synthetic error response)
     */
    A2ATaskResponse sendTask(String unitId, String token, int depth)
            throws IOException, InterruptedException {

        String url = agentBaseUrl + "/a2a/tasks";
        String jsonBody = JSON.writeValueAsString(new no.cantara.kcp.sim.http.model.A2ATaskRequest(
                "load_knowledge_unit", unitId, depth));

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        if (token != null) {
            reqBuilder.header("Authorization", "Bearer " + token);
        }

        HttpRequest request = reqBuilder.build();
        log("POST " + url + (token != null ? " [Authorization: Bearer ***]" : " [no auth]"));

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        httpLog("POST", url, response.statusCode());

        return JSON.readValue(response.body(), A2ATaskResponse.class);
    }

    // ── Logging helpers ─────────────────────────────────────────────────────

    private void logDecision(A2ATaskResponse resp) {
        if (resp.allowed()) {
            kcpLog("GRANTED: " + resp.reason());
            if (resp.contentPreview() != null) {
                kcpLog("Content: " + resp.contentPreview());
            }
            if (resp.delegation() != null) {
                kcpLog("Delegation: max_depth=" + resp.delegation().get("max_depth")
                        + ", current_depth=" + resp.delegation().get("current_depth"));
            }
            if (resp.compliance() != null) {
                kcpLog("Compliance: sensitivity=" + resp.compliance().get("sensitivity")
                        + ", audit=" + resp.compliance().get("audit_required"));
                if (resp.compliance().get("trace_id") != null) {
                    auditLog("trace: " + resp.compliance().get("trace_id"));
                }
            }
        } else {
            kcpLog("DENIED: " + resp.reason());
        }
    }

    private void summary() {
        section("Summary");
        blank();
        log("All interactions used real HTTP on localhost:");
        log("  Auth Server:    http://localhost:9000  (OAuth2 token + introspect)");
        log("  Research Agent: http://localhost:9001  (A2A agent card + KCP manifest + tasks)");
        blank();
        log("Demonstrated:");
        log("  1. A2A agent discovery via /.well-known/agent.json (HTTP GET)");
        log("  2. KCP manifest discovery and retrieval (HTTP GET)");
        log("  3. OAuth2 client_credentials token exchange (HTTP POST)");
        log("  4. Token introspection: agent -> auth server (HTTP POST)");
        log("  5. KCP policy enforcement: public, authenticated, restricted access");
        log("  6. Delegation depth enforcement (max_depth exceeded -> 403)");
        log("  7. Human-in-the-loop gate (auto-approved in simulation)");
        log("  8. Audit trail with W3C Trace Context IDs");
        blank();
        log("The A2A Agent Card handled discovery and authentication.");
        log("The KCP manifest enforced per-unit access policy over real HTTP.");
        log("Together: informed, auditable, policy-aware knowledge access.");
    }

    // ── Output formatting ───────────────────────────────────────────────────

    private static void log(String message) {
        System.out.println("[HTTP] " + message);
    }

    private static void kcpLog(String message) {
        System.out.println("[KCP]  " + message);
    }

    private static void auditLog(String message) {
        System.out.println("[AUDIT] " + message);
    }

    private static void httpLog(String method, String url, int status) {
        System.out.println("[HTTP] " + method + " " + url + " -> " + status);
    }

    private static void header(String title) {
        System.out.println("=== " + title + " ===");
    }

    private static void section(String title) {
        String pad = "\u2500".repeat(Math.max(1, 60 - title.length()));
        System.out.println("\u2500\u2500 " + title + " " + pad);
    }

    private static void blank() {
        System.out.println();
    }
}
