package no.cantara.kcp.simulator;

import no.cantara.kcp.model.KnowledgeUnit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The Orchestrator Agent: drives the full A2A + KCP interaction.
 * <ol>
 *   <li>Phase 1: Agent Discovery (A2A) -- reads agent-card.json</li>
 *   <li>Phase 2: Knowledge Discovery (KCP) -- reads knowledge.yaml</li>
 *   <li>Phase 3: Authentication (OAuth2)</li>
 *   <li>Phase 4: Knowledge Access -- executes queries against units</li>
 * </ol>
 */
public final class OrchestratorAgent {

    private final ConsoleLog log;
    private final boolean autoApprove;

    private AgentCard agentCard;
    private ResearchAgent researchAgent;
    private SimulatedOAuth2 oauth2;
    private SimulatedToken token;
    private HumanApprovalGate approvalGate;
    private AccessDecisionEngine decisionEngine;

    // Stats
    private int queryCount = 0;
    private int unitsAccessed = 0;
    private int humanApprovals = 0;
    private int auditTraces = 0;

    public OrchestratorAgent(ConsoleLog log, boolean autoApprove) {
        this.log = log;
        this.autoApprove = autoApprove;
    }

    /** Run the full simulation. */
    public void run(Path agentCardPath, Path manifestPath) throws IOException {
        log.header("A2A + KCP Composition Simulator");
        log.plain("Domain: Clinical Research | KCP v0.9 | A2A Agent Card v1.0.0");
        log.blank();

        phase1AgentDiscovery(agentCardPath);
        log.blank();
        phase2KnowledgeDiscovery(manifestPath);
        log.blank();
        phase3Authentication();
        log.blank();
        phase4KnowledgeAccess();
        log.blank();
        printSummary();
    }

    private void phase1AgentDiscovery(Path agentCardPath) throws IOException {
        log.section("Phase 1: Agent Discovery (A2A Layer)");
        log.blank();

        agentCard = AgentCardParser.parse(agentCardPath);

        log.a2a("Orchestrator reads agent-card.json");
        log.a2a("Discovered: \"" + agentCard.name() + "\" at " + agentCard.url());

        // Skills
        StringBuilder skillNames = new StringBuilder();
        for (Map<String, Object> skill : agentCard.skills()) {
            if (!skillNames.isEmpty()) skillNames.append(", ");
            skillNames.append(skill.get("id"));
        }
        log.a2a("Skills: " + skillNames);

        // Security
        log.a2a("Security: OAuth2 (client_credentials) at " + agentCard.oauth2TokenUrl());

        // Knowledge manifest link
        log.a2a("Knowledge manifest: " + agentCard.knowledgeManifest());
    }

    private void phase2KnowledgeDiscovery(Path manifestPath) throws IOException {
        log.section("Phase 2: Knowledge Discovery (KCP Layer)");
        log.blank();

        researchAgent = new ResearchAgent();
        researchAgent.loadManifest(manifestPath);
        researchAgent.printDiscovery(log);
    }

    private void phase3Authentication() {
        log.section("Phase 3: Authentication (A2A + OAuth2)");
        log.blank();

        oauth2 = new SimulatedOAuth2();
        approvalGate = new HumanApprovalGate(autoApprove, log);
        decisionEngine = new AccessDecisionEngine(oauth2, researchAgent.unitDelegation());

        log.a2a("Orchestrator requests OAuth2 token (client_credentials flow)");

        Set<String> scopes = Set.of("read:guidelines", "read:protocols", "read:cohort");
        log.a2a("Scopes requested: " + String.join(", ", scopes));

        token = oauth2.issueToken("orchestrator-agent", scopes);
        log.a2a("Token issued: " + token.maskedValue() + " (expires in 3600s)");
    }

    private void phase4KnowledgeAccess() {
        log.section("Phase 4: Knowledge Access (KCP Access Decisions)");

        // The three queries matching the three units
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("What are the research guidelines?", "public-guidelines");
        queries.put("What are the active trial protocols?", "trial-protocols");
        queries.put("Show the patient cohort demographics", "patient-cohort");

        int qNum = 0;
        for (Map.Entry<String, String> entry : queries.entrySet()) {
            qNum++;
            String question = entry.getKey();
            String expectedUnitId = entry.getValue();
            log.blank();
            executeQuery("Q" + qNum, question, expectedUnitId);
        }
    }

    private void executeQuery(String label, String question, String unitId) {
        queryCount++;
        log.query(label, "\"" + question + "\"");

        // Find the unit
        KnowledgeUnit unit = researchAgent.manifest().units().stream()
                .filter(u -> unitId.equals(u.id()))
                .findFirst()
                .orElse(null);

        if (unit == null) {
            log.kcp("Unit not found: " + unitId);
            return;
        }

        log.kcp("Matched unit: " + unit.id());

        String access = unit.access() != null ? unit.access() : "public";

        // Access decision
        AccessDecision decision = decisionEngine.evaluate(unit, token.tokenValue());

        switch (access) {
            case "public":
                log.kcp("Access: public \u2192 no credential required");
                break;
            case "authenticated":
                log.kcp("Access: authenticated \u2192 checking credential");
                log.kcp("Token valid: yes | Scope '" + AccessDecisionEngine.scopeFromId(unitId) + "': yes");
                break;
            case "restricted":
                String authScope = unit.authScope() != null ? unit.authScope() : "none";
                String sensitivity = unit.sensitivity() != null ? unit.sensitivity() : "restricted";
                log.kcp("Access: restricted | auth_scope: " + authScope + " | sensitivity: " + sensitivity);
                log.kcp("Token valid: yes | Scope '" + AccessDecisionEngine.scopeFromId(unitId) + "': yes");
                // Delegation info
                DelegationConfig del = researchAgent.unitDelegation().get(unitId);
                if (del != null && del.maxDepth() != researchAgent.rootDelegation().maxDepth()) {
                    log.kcp("Delegation: max_depth=" + del.maxDepth() + " (unit override)");
                }
                break;
        }

        // Process the decision
        if (decision instanceof AccessDecision.Granted granted) {
            logGranted(granted.unitId());
        } else if (decision instanceof AccessDecision.RequiresHumanApproval hitl) {
            log.kcp("Human-in-the-loop: REQUIRED (approval_mechanism: " + hitl.approvalMechanism() + ")");
            String approver = approvalGate.requestApproval(hitl.unitId(), hitl.sensitivity(), hitl.approvalMechanism());
            if (approver != null) {
                humanApprovals++;
                log.kcp("Result: LOADED (after human approval)");
                log.kcp("Content: " + ResearchAgent.getContentPreview(hitl.unitId()));
                unitsAccessed++;
                String trace = TraceContext.newTraceparent();
                auditTraces++;
                log.audit("trace: " + trace);
                log.audit("human_approval: " + approver);
            } else {
                log.kcp("Result: DENIED (human approval refused)");
            }
        } else if (decision instanceof AccessDecision.Denied denied) {
            log.kcp("Result: DENIED (" + denied.reason() + ")");
        }
    }

    private void logGranted(String unitId) {
        log.kcp("Result: LOADED");
        log.kcp("Content: " + ResearchAgent.getContentPreview(unitId));
        unitsAccessed++;
        String trace = TraceContext.newTraceparent();
        auditTraces++;
        log.audit("trace: " + trace);
    }

    private void printSummary() {
        log.section("Summary");
        log.blank();
        log.summary(queryCount + " queries | " + unitsAccessed + " units accessed | "
                + humanApprovals + " human approval | " + auditTraces + " audit traces");
        log.summary("The A2A Agent Card handled discovery and authentication.");
        log.summary("The KCP manifest enforced per-unit access at " + unitsAccessed + " levels.");
        log.summary("Together: informed, auditable, policy-aware knowledge access.");
    }
}
