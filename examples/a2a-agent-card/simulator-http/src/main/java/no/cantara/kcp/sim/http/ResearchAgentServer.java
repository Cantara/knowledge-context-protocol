package no.cantara.kcp.sim.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import no.cantara.kcp.KcpParser;
import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;
import no.cantara.kcp.sim.http.model.A2ATaskRequest;
import no.cantara.kcp.sim.http.model.A2ATaskResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Embedded HTTP server for the Research Agent.
 *
 * <p>Runs on port 9001 (configurable) and serves four endpoints:
 * <ul>
 *   <li>{@code GET /.well-known/agent.json} — A2A agent card (discovery)</li>
 *   <li>{@code GET /.well-known/kcp.json} — KCP discovery redirect</li>
 *   <li>{@code GET /knowledge.yaml} — the full KCP manifest</li>
 *   <li>{@code POST /a2a/tasks} — execute an A2A task with KCP policy enforcement</li>
 * </ul>
 *
 * <h3>Architecture: what this demonstrates</h3>
 * <p>The Research Agent is a real HTTP server that an Orchestrator discovers
 * via the standard A2A {@code .well-known} endpoint. When a task request
 * arrives, the agent:
 * <ol>
 *   <li>Extracts the Bearer token from the Authorization header</li>
 *   <li>Validates it with the Auth Server via HTTP introspection (real network call)</li>
 *   <li>Evaluates KCP policy (access level, delegation depth, sensitivity)</li>
 *   <li>Returns a structured response with the policy decision</li>
 * </ol>
 *
 * <p>This is the same flow a production agent would use, but running on localhost
 * with no external dependencies.
 */
public final class ResearchAgentServer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Yaml RAW_YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    /** Hardcoded content for simulated units (matches the in-process simulator). */
    private static final Map<String, String> SIMULATED_CONTENT = Map.of(
            "public-guidelines",
            "ICH Good Clinical Practice (ICH E6 R2) guidelines, including ethical standards, " +
                    "informed consent requirements, and regulatory submission criteria.",
            "trial-protocols",
            "BEACON-3 Phase III Study \u2014 Inclusion criteria: age 18\u201375, confirmed diagnosis. " +
                    "Exclusion: prior systemic therapy. Current enrollment: 342 participants.",
            "patient-cohort",
            "BEACON-3 cohort demographics: 342 enrolled, 58% female, median age 47. " +
                    "Primary endpoint: progression-free survival at 24 months. " +
                    "Adverse events: grade 3+ in 12% of participants."
    );

    private final int port;
    private final int authServerPort;
    private final Path manifestPath;
    private HttpServer server;

    // Loaded on startup
    private KnowledgeManifest manifest;
    private String manifestYamlContent;
    private Map<String, DelegationInfo> unitDelegation;
    private DelegationInfo rootDelegation;

    /** Delegation metadata extracted from raw YAML (not part of KcpParser model). */
    record DelegationInfo(int maxDepth, boolean requireCapabilityAttenuation,
                          boolean auditChain, boolean humanInTheLoopRequired,
                          String approvalMechanism) {}

    /**
     * @param port           the port for this agent server
     * @param authServerPort the port where the Auth Server is running (for token introspection)
     * @param manifestPath   path to knowledge.yaml
     */
    public ResearchAgentServer(int port, int authServerPort, Path manifestPath) {
        this.port = port;
        this.authServerPort = authServerPort;
        this.manifestPath = manifestPath;
    }

    /**
     * Start the HTTP server. Loads the KCP manifest and binds all endpoints.
     */
    public void start() throws IOException {
        // Load the KCP manifest once at startup
        loadManifest();

        server = HttpServer.create(new InetSocketAddress("localhost", port), 0);

        // A2A discovery: the front door for agent-to-agent communication
        server.createContext("/.well-known/agent.json", this::handleAgentCard);

        // KCP discovery: redirect from A2A card's knowledgeManifest field
        server.createContext("/.well-known/kcp.json", this::handleKcpDiscovery);

        // KCP manifest: the full knowledge inventory
        server.createContext("/knowledge.yaml", this::handleManifest);

        // A2A task execution: where policy enforcement happens
        server.createContext("/a2a/tasks", this::handleTask);

        server.setExecutor(null);
        server.start();
        log("Research Agent started on http://localhost:" + port);
        log("KCP manifest loaded: " + manifest.project() + " v" + manifest.version()
                + " (" + manifest.units().size() + " units)");
    }

    /**
     * Stop the server gracefully.
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            log("Research Agent stopped");
        }
    }

    // ── Endpoint handlers ───────────────────────────────────────────────────

    /**
     * GET /.well-known/agent.json — A2A Agent Card.
     *
     * <p>This is what an orchestrator fetches first to discover this agent's
     * capabilities, security requirements, and knowledge manifest location.
     * The card references localhost URLs so the simulation works end-to-end.
     */
    private void handleAgentCard(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        // Build the agent card dynamically with localhost URLs
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", "Clinical Research Agent");
        card.put("description", "Assists researchers with protocol lookup, guideline retrieval, "
                + "and patient cohort analysis for active clinical trials.");
        card.put("url", "http://localhost:" + port);
        card.put("version", "1.0.0");

        // Provider
        card.put("provider", Map.of(
                "organization", "Example Health Research",
                "url", "http://localhost:" + port
        ));

        // Security schemes — pointing to the real Auth Server
        card.put("securitySchemes", Map.of(
                "oauth2", Map.of(
                        "type", "oauth2",
                        "flows", Map.of(
                                "clientCredentials", Map.of(
                                        "tokenUrl", "http://localhost:" + authServerPort + "/oauth2/token",
                                        "scopes", Map.of(
                                                "read:guidelines", "Read publicly available research guidelines",
                                                "read:protocols", "Read active trial protocols",
                                                "read:cohort", "Read patient cohort data (requires human approval)"
                                        )
                                )
                        )
                )
        ));

        // Skills
        card.put("skills", List.of(
                Map.of(
                        "id", "protocol-lookup",
                        "name", "Protocol Lookup",
                        "description", "Retrieve and summarise active clinical trial protocols."
                ),
                Map.of(
                        "id", "cohort-analysis",
                        "name", "Cohort Analysis",
                        "description", "Analyse patient cohort demographics and outcomes."
                )
        ));

        // The key link: A2A card points to KCP manifest
        card.put("knowledgeManifest", "/.well-known/kcp.json");

        log("Served agent card to " + exchange.getRemoteAddress());
        sendJson(exchange, 200, card);
    }

    /**
     * GET /.well-known/kcp.json — KCP discovery redirect.
     *
     * <p>This endpoint bridges A2A and KCP: the agent card's {@code knowledgeManifest}
     * field points here, and this returns the location of the actual KCP manifest.
     */
    private void handleKcpDiscovery(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        Map<String, Object> discovery = Map.of(
                "kcp_version", manifest.kcpVersion(),
                "manifest_url", "http://localhost:" + port + "/knowledge.yaml",
                "project", manifest.project()
        );

        log("Served KCP discovery to " + exchange.getRemoteAddress());
        sendJson(exchange, 200, discovery);
    }

    /**
     * GET /knowledge.yaml — serve the raw KCP manifest.
     *
     * <p>Returns the YAML content so the orchestrator can inspect the full
     * knowledge inventory, including access levels, delegation constraints,
     * and compliance requirements for each unit.
     */
    private void handleManifest(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        byte[] yamlBytes = manifestYamlContent.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/yaml; charset=utf-8");
        exchange.sendResponseHeaders(200, yamlBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(yamlBytes);
        }
        log("Served KCP manifest (" + manifest.units().size() + " units)");
    }

    /**
     * POST /a2a/tasks — execute an A2A task with KCP policy enforcement.
     *
     * <p>This is where the three layers compose:
     * <ol>
     *   <li><strong>A2A layer:</strong> receives the task request over HTTP</li>
     *   <li><strong>OAuth2 layer:</strong> validates the Bearer token with the Auth Server</li>
     *   <li><strong>KCP layer:</strong> evaluates per-unit access policy (access level,
     *       delegation depth, sensitivity, compliance)</li>
     * </ol>
     *
     * <p>The response includes the full policy decision so the orchestrator
     * can see exactly why access was granted or denied.
     */
    private void handleTask(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        // Parse the task request
        byte[] body = exchange.getRequestBody().readAllBytes();
        A2ATaskRequest request;
        try {
            request = JSON.readValue(body, A2ATaskRequest.class);
        } catch (Exception e) {
            sendJson(exchange, 400, A2ATaskResponse.denied(null, "Invalid request body: " + e.getMessage()));
            return;
        }

        log("Task request: task=" + request.task() + " unit=" + request.unitId()
                + " depth=" + request.delegationDepth());

        // Find the requested knowledge unit
        KnowledgeUnit unit = manifest.units().stream()
                .filter(u -> request.unitId().equals(u.id()))
                .findFirst()
                .orElse(null);

        if (unit == null) {
            log("Unit not found: " + request.unitId());
            sendJson(exchange, 404, A2ATaskResponse.denied(request.unitId(), "Unit not found"));
            return;
        }

        String accessLevel = unit.access() != null ? unit.access() : "public";
        String sensitivity = unit.sensitivity() != null ? unit.sensitivity() : "public";
        DelegationInfo delegation = unitDelegation.getOrDefault(unit.id(), rootDelegation);

        // ── Step 1: Check delegation depth ──────────────────────────────────
        if (request.delegationDepth() > delegation.maxDepth()) {
            String reason = "Delegation depth " + request.delegationDepth()
                    + " exceeds max_depth " + delegation.maxDepth();
            log("DENIED: " + reason);
            sendJson(exchange, 403, A2ATaskResponse.denied(request.unitId(), reason));
            return;
        }

        // ── Step 2: Check access level ──────────────────────────────────────

        if ("public".equals(accessLevel)) {
            // Public units need no authentication
            log("Access: public -> no credential required");
            A2ATaskResponse response = buildGrantedResponse(unit, request, delegation, "public: no credential required");
            sendJson(exchange, 200, response);
            return;
        }

        // Authenticated or restricted: need a Bearer token
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log("DENIED: no Bearer token for " + accessLevel + " unit");
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"kcp\"");
            sendJson(exchange, 401, A2ATaskResponse.denied(request.unitId(),
                    "Bearer token required for " + accessLevel + " access"));
            return;
        }

        String token = authHeader.substring("Bearer ".length());

        // ── Step 3: Validate token with Auth Server (real HTTP call) ────────
        TokenIntrospection introspection = introspectToken(token);

        if (!introspection.active()) {
            log("DENIED: token invalid or expired");
            sendJson(exchange, 401, A2ATaskResponse.denied(request.unitId(), "Token invalid or expired"));
            return;
        }

        // ── Step 4: Check scope ─────────────────────────────────────────────
        String requiredScope = scopeForUnit(unit);
        if (!introspection.hasScope(requiredScope)) {
            log("DENIED: missing scope " + requiredScope + " (has: " + introspection.scope() + ")");
            sendJson(exchange, 403, A2ATaskResponse.denied(request.unitId(),
                    "Missing required scope: " + requiredScope));
            return;
        }

        // ── Step 5: Check human-in-the-loop (for restricted units) ──────────
        if (delegation.humanInTheLoopRequired()) {
            // In this simulation, we auto-approve with a logged decision.
            // A real system would redirect to an approval UI or queue.
            log("Human-in-the-loop: REQUIRED (approval_mechanism: " + delegation.approvalMechanism() + ")");
            log("Auto-approved for simulation (real system would gate here)");
        }

        // ── Access granted ──────────────────────────────────────────────────
        String reason = accessLevel + ": valid token with scope " + requiredScope;
        A2ATaskResponse response = buildGrantedResponse(unit, request, delegation, reason);
        log("GRANTED: " + reason);
        sendJson(exchange, 200, response);
    }

    // ── Token introspection ─────────────────────────────────────────────────

    /** Result of introspecting a token with the Auth Server. */
    record TokenIntrospection(boolean active, String clientId, String scope) {
        boolean hasScope(String requiredScope) {
            if (scope == null) return false;
            for (String s : scope.split("\\s+")) {
                if (s.equals(requiredScope)) return true;
            }
            return false;
        }
    }

    /**
     * Validate a Bearer token by calling the Auth Server's introspect endpoint.
     *
     * <p>This is a real HTTP call from the Research Agent to the Auth Server,
     * demonstrating how resource servers validate tokens in a microservice
     * architecture. The token never needs to be a self-contained JWT —
     * introspection provides the authoritative answer.
     */
    private TokenIntrospection introspectToken(String tokenValue) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + authServerPort + "/oauth2/introspect"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("token=" + tokenValue))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = JSON.readTree(response.body());

            boolean active = json.path("active").asBoolean(false);
            String clientId = json.path("client_id").asText(null);
            String scope = json.path("scope").asText(null);

            return new TokenIntrospection(active, clientId, scope);
        } catch (Exception e) {
            log("Token introspection failed: " + e.getMessage());
            return new TokenIntrospection(false, null, null);
        }
    }

    // ── Response builders ───────────────────────────────────────────────────

    /** Build a granted response with content preview, delegation, and compliance metadata. */
    private A2ATaskResponse buildGrantedResponse(KnowledgeUnit unit, A2ATaskRequest request,
                                                  DelegationInfo delegation, String reason) {
        String content = SIMULATED_CONTENT.getOrDefault(unit.id(), "(no content)");
        String preview = content.length() > 80 ? content.substring(0, 77) + "..." : content;

        Map<String, Object> delegationMap = new LinkedHashMap<>();
        delegationMap.put("max_depth", delegation.maxDepth());
        delegationMap.put("current_depth", request.delegationDepth());
        delegationMap.put("capability_attenuation", delegation.requireCapabilityAttenuation());
        delegationMap.put("audit_chain", delegation.auditChain());
        if (delegation.humanInTheLoopRequired()) {
            delegationMap.put("human_in_the_loop", true);
            delegationMap.put("approval_mechanism", delegation.approvalMechanism());
        }

        String sensitivity = unit.sensitivity() != null ? unit.sensitivity() : "public";
        Map<String, Object> complianceMap = new LinkedHashMap<>();
        complianceMap.put("sensitivity", sensitivity);
        complianceMap.put("audit_required", manifest.trust() != null
                && manifest.trust().audit() != null
                && Boolean.TRUE.equals(manifest.trust().audit().agentMustLog()));
        complianceMap.put("trace_id", "00-" + UUID.randomUUID().toString().replace("-", "")
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "-01");

        return A2ATaskResponse.granted(unit.id(), reason, preview, delegationMap, complianceMap);
    }

    // ── Manifest loading ────────────────────────────────────────────────────

    /**
     * Load and parse the KCP manifest.
     * Parses twice: KcpParser for typed model, raw YAML for delegation fields.
     */
    @SuppressWarnings("unchecked")
    private void loadManifest() throws IOException {
        // Load raw YAML content for serving
        manifestYamlContent = Files.readString(manifestPath);

        // Typed parse via KcpParser
        manifest = KcpParser.parse(manifestPath);

        // Raw parse for delegation fields (not in KcpParser model)
        Map<String, Object> rawData;
        try (InputStream is = Files.newInputStream(manifestPath)) {
            rawData = RAW_YAML.load(is);
        }

        // Root delegation
        rootDelegation = parseDelegationInfo(
                (Map<String, Object>) rawData.get("delegation"), null);

        // Per-unit delegation overrides
        unitDelegation = new LinkedHashMap<>();
        List<Map<String, Object>> rawUnits =
                (List<Map<String, Object>>) rawData.getOrDefault("units", List.of());
        for (Map<String, Object> rawUnit : rawUnits) {
            String id = (String) rawUnit.get("id");
            if (id != null) {
                DelegationInfo unitDel = parseDelegationInfo(
                        (Map<String, Object>) rawUnit.get("delegation"), rootDelegation);
                unitDelegation.put(id, unitDel);
            }
        }
    }

    /**
     * Parse delegation info from raw YAML, falling back to parent if not overridden.
     */
    @SuppressWarnings("unchecked")
    private DelegationInfo parseDelegationInfo(Map<String, Object> delegationMap,
                                               DelegationInfo parent) {
        if (delegationMap == null) {
            return parent != null ? parent
                    : new DelegationInfo(0, false, false, false, null);
        }

        int maxDepth = toInt(delegationMap.get("max_depth"),
                parent != null ? parent.maxDepth() : 0);
        boolean attenuation = toBool(delegationMap.get("require_capability_attenuation"),
                parent != null && parent.requireCapabilityAttenuation());
        boolean audit = toBool(delegationMap.get("audit_chain"),
                parent != null && parent.auditChain());

        boolean hitlRequired = false;
        String mechanism = null;
        Map<String, Object> hitl = (Map<String, Object>) delegationMap.get("human_in_the_loop");
        if (hitl != null) {
            hitlRequired = toBool(hitl.get("required"), false);
            Object mech = hitl.get("approval_mechanism");
            mechanism = mech != null ? mech.toString() : null;
        }

        return new DelegationInfo(maxDepth, attenuation, audit, hitlRequired, mechanism);
    }

    // ── Scope mapping ───────────────────────────────────────────────────────

    /**
     * Map a KCP unit to its required OAuth2 scope.
     * Convention: read:{unit-id-last-segment}
     * E.g. "trial-protocols" -> "read:protocols", "patient-cohort" -> "read:cohort"
     */
    private String scopeForUnit(KnowledgeUnit unit) {
        String id = unit.id();
        if (id == null) return "read:unknown";
        int dash = id.indexOf('-');
        if (dash >= 0 && dash < id.length() - 1) {
            return "read:" + id.substring(dash + 1);
        }
        return "read:" + id;
    }

    // ── Utilities ───────────────────────────────────────────────────────────

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

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try { return Integer.parseInt(value.toString()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private static boolean toBool(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value != null) return Boolean.parseBoolean(value.toString());
        return fallback;
    }

    private static void log(String message) {
        System.out.println("[A2A]  " + message);
    }

    /** Expose the port for testing. */
    public int port() { return port; }
}
