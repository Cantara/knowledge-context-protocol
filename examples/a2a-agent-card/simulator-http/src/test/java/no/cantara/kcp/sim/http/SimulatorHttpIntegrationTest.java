package no.cantara.kcp.sim.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import no.cantara.kcp.sim.http.model.A2ATaskResponse;
import no.cantara.kcp.sim.http.model.TokenResponse;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the A2A + KCP HTTP simulation.
 *
 * <p>Starts both servers on fixed ports before all tests, then exercises
 * every KCP policy path over real HTTP:
 * <ul>
 *   <li>Public unit accessible without token</li>
 *   <li>Restricted unit rejected without token (401)</li>
 *   <li>Restricted unit accessible with valid token + correct scope</li>
 *   <li>Wrong scope returns 403</li>
 *   <li>Delegation max_depth enforced</li>
 * </ul>
 *
 * <p>Uses ports 19000/19001 to avoid conflicts with other services.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SimulatorHttpIntegrationTest {

    private static final int AUTH_PORT = 19000;
    private static final int AGENT_PORT = 19001;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static AuthServer authServer;
    private static ResearchAgentServer agentServer;
    private static HttpClient httpClient;

    @BeforeAll
    static void startServers() throws IOException {
        httpClient = HttpClient.newHttpClient();

        Path manifestPath = resolveManifest();
        assertTrue(Files.exists(manifestPath),
                "knowledge.yaml must exist at " + manifestPath.toAbsolutePath());

        authServer = new AuthServer(AUTH_PORT);
        authServer.start();

        agentServer = new ResearchAgentServer(AGENT_PORT, AUTH_PORT, manifestPath);
        agentServer.start();
    }

    @AfterAll
    static void stopServers() {
        if (agentServer != null) agentServer.stop();
        if (authServer != null) authServer.stop();
    }

    // ── Agent Discovery ─────────────────────────────────────────────────────

    @Test
    @Order(1)
    void agentCardIsDiscoverable() throws Exception {
        HttpResponse<String> resp = get("http://localhost:" + AGENT_PORT + "/.well-known/agent.json");

        assertEquals(200, resp.statusCode(), "Agent card should be accessible");

        JsonNode card = JSON.readTree(resp.body());
        assertEquals("Clinical Research Agent", card.path("name").asText());
        assertNotNull(card.path("knowledgeManifest").asText(null),
                "Agent card must include knowledgeManifest link");
        assertTrue(card.path("securitySchemes").path("oauth2").has("flows"),
                "Agent card must declare OAuth2 security");
    }

    @Test
    @Order(2)
    void kcpDiscoveryReturnsManifestUrl() throws Exception {
        HttpResponse<String> resp = get("http://localhost:" + AGENT_PORT + "/.well-known/kcp.json");

        assertEquals(200, resp.statusCode());

        JsonNode discovery = JSON.readTree(resp.body());
        assertEquals("0.9", discovery.path("kcp_version").asText());
        assertTrue(discovery.path("manifest_url").asText("").contains("/knowledge.yaml"),
                "KCP discovery must point to the manifest");
    }

    @Test
    @Order(3)
    void manifestIsServable() throws Exception {
        HttpResponse<String> resp = get("http://localhost:" + AGENT_PORT + "/knowledge.yaml");

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("kcp_version:"), "Should contain KCP version");
        assertTrue(resp.body().contains("public-guidelines"), "Should contain unit IDs");
        assertTrue(resp.body().contains("trial-protocols"), "Should contain unit IDs");
        assertTrue(resp.body().contains("patient-cohort"), "Should contain unit IDs");
    }

    // ── Public Access ───────────────────────────────────────────────────────

    @Test
    @Order(10)
    void publicUnitAccessibleWithoutToken() throws Exception {
        A2ATaskResponse resp = postTask("public-guidelines", null, 0);

        assertTrue(resp.allowed(), "Public unit should be accessible without token");
        assertEquals("public-guidelines", resp.unitId());
        assertNotNull(resp.contentPreview(), "Should include content preview");
        assertTrue(resp.reason().contains("public"), "Reason should mention public access");
    }

    // ── Authentication Required ─────────────────────────────────────────────

    @Test
    @Order(20)
    void authenticatedUnitRejectedWithoutToken() throws Exception {
        HttpResponse<String> resp = postTaskRaw("trial-protocols", null, 0);

        assertEquals(401, resp.statusCode(),
                "Authenticated unit without token should return 401");

        A2ATaskResponse body = JSON.readValue(resp.body(), A2ATaskResponse.class);
        assertFalse(body.allowed());
        assertTrue(body.reason().contains("Bearer token required"),
                "Should explain that a token is needed");
    }

    @Test
    @Order(21)
    void restrictedUnitRejectedWithoutToken() throws Exception {
        HttpResponse<String> resp = postTaskRaw("patient-cohort", null, 0);

        assertEquals(401, resp.statusCode(),
                "Restricted unit without token should return 401");
    }

    // ── Valid Token + Correct Scope ─────────────────────────────────────────

    @Test
    @Order(30)
    void authenticatedUnitAccessibleWithValidToken() throws Exception {
        String token = obtainToken("read:protocols");

        A2ATaskResponse resp = postTask("trial-protocols", token, 0);

        assertTrue(resp.allowed(), "Should be granted with valid token and correct scope");
        assertEquals("trial-protocols", resp.unitId());
        assertNotNull(resp.contentPreview());
        assertNotNull(resp.delegation(), "Should include delegation metadata");
        assertNotNull(resp.compliance(), "Should include compliance metadata");
        assertTrue(resp.compliance().containsKey("trace_id"),
                "Compliance should include a W3C trace ID");
    }

    @Test
    @Order(31)
    void restrictedUnitAccessibleWithValidToken() throws Exception {
        String token = obtainToken("read:cohort");

        A2ATaskResponse resp = postTask("patient-cohort", token, 0);

        assertTrue(resp.allowed(), "Should be granted with valid token and correct scope");
        assertEquals("patient-cohort", resp.unitId());
        // Verify delegation metadata shows the per-unit override
        assertEquals(1, ((Number) resp.delegation().get("max_depth")).intValue(),
                "patient-cohort should have max_depth=1 (unit override)");
        assertTrue((Boolean) resp.delegation().get("human_in_the_loop"),
                "patient-cohort should require human-in-the-loop");
    }

    // ── Wrong Scope ─────────────────────────────────────────────────────────

    @Test
    @Order(40)
    void wrongScopeReturns403() throws Exception {
        // Get a token with only read:guidelines scope
        String token = obtainToken("read:guidelines");

        // Try to access trial-protocols which needs read:protocols
        HttpResponse<String> resp = postTaskRaw("trial-protocols", token, 0);

        assertEquals(403, resp.statusCode(),
                "Wrong scope should return 403 Forbidden");

        A2ATaskResponse body = JSON.readValue(resp.body(), A2ATaskResponse.class);
        assertFalse(body.allowed());
        assertTrue(body.reason().contains("Missing required scope"),
                "Should explain missing scope: " + body.reason());
    }

    // ── Delegation Depth ────────────────────────────────────────────────────

    @Test
    @Order(50)
    void delegationMaxDepthEnforced() throws Exception {
        String token = obtainToken("read:cohort");

        // patient-cohort has max_depth=1, send depth=3 to exceed it
        HttpResponse<String> resp = postTaskRaw("patient-cohort", token, 3);

        assertEquals(403, resp.statusCode(),
                "Exceeding delegation max_depth should return 403");

        A2ATaskResponse body = JSON.readValue(resp.body(), A2ATaskResponse.class);
        assertFalse(body.allowed());
        assertTrue(body.reason().contains("Delegation depth"),
                "Should explain delegation depth violation: " + body.reason());
        assertTrue(body.reason().contains("exceeds max_depth"),
                "Should mention max_depth in reason");
    }

    @Test
    @Order(51)
    void delegationWithinLimitSucceeds() throws Exception {
        String token = obtainToken("read:cohort");

        // patient-cohort has max_depth=1, send depth=1 (at limit, should pass)
        A2ATaskResponse resp = postTask("patient-cohort", token, 1);

        assertTrue(resp.allowed(),
                "Delegation depth at max_depth should be allowed");
    }

    @Test
    @Order(52)
    void rootDelegationDepthEnforced() throws Exception {
        String token = obtainToken("read:protocols");

        // trial-protocols uses root delegation max_depth=2, send depth=5
        HttpResponse<String> resp = postTaskRaw("trial-protocols", token, 5);

        assertEquals(403, resp.statusCode(),
                "Exceeding root delegation max_depth should return 403");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Obtain a token with a single scope. */
    private String obtainToken(String scope) throws Exception {
        String formBody = "grant_type=client_credentials&scope=" + scope + "&client_id=test-client";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + AUTH_PORT + "/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Token request should succeed");

        TokenResponse tokenResp = JSON.readValue(response.body(), TokenResponse.class);
        assertNotNull(tokenResp.accessToken(), "Token should not be null");
        return tokenResp.accessToken();
    }

    /** POST a task and return the parsed response (asserts 200). */
    private A2ATaskResponse postTask(String unitId, String token, int depth) throws Exception {
        HttpResponse<String> resp = postTaskRaw(unitId, token, depth);
        assertEquals(200, resp.statusCode(),
                "Expected 200 but got " + resp.statusCode() + ": " + resp.body());
        return JSON.readValue(resp.body(), A2ATaskResponse.class);
    }

    /** POST a task and return the raw HTTP response (does not assert status). */
    private HttpResponse<String> postTaskRaw(String unitId, String token, int depth)
            throws Exception {

        String jsonBody = JSON.writeValueAsString(new no.cantara.kcp.sim.http.model.A2ATaskRequest(
                "load_knowledge_unit", unitId, depth));

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + AGENT_PORT + "/a2a/tasks"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Simple GET helper. */
    private HttpResponse<String> get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Resolve the knowledge.yaml manifest path. */
    private static Path resolveManifest() {
        Path relative = Path.of("../knowledge.yaml");
        if (Files.exists(relative)) return relative;
        Path repoPath = Path.of("/src/cantara/knowledge-context-protocol/examples/a2a-agent-card/knowledge.yaml");
        if (Files.exists(repoPath)) return repoPath;
        return relative;
    }
}
