package no.cantara.kcp.simulator;

import no.cantara.kcp.model.KnowledgeUnit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * The Grid Orchestrator Agent: discovers the Energy Metering Agent via A2A,
 * then requests all 4 knowledge units in escalating access order.
 * <ol>
 *   <li>Phase 1: Agent Discovery (A2A) -- reads agent-card.json</li>
 *   <li>Phase 2: Knowledge Discovery (KCP) -- reads knowledge.yaml</li>
 *   <li>Phase 3: Authentication (OAuth2)</li>
 *   <li>Phase 4: Knowledge Access -- requests all 4 units</li>
 * </ol>
 */
public final class GridOrchestratorAgent {

    private final ConsoleLog log;
    private final boolean autoApprove;

    private AgentCard agentCard;
    private EnergyMeteringAgent energyAgent;
    private SimulatedOAuth2 oauth2;
    private SimulatedToken token;
    private HumanApprovalGate approvalGate;
    private AccessDecisionEngine decisionEngine;

    // Stats
    private int unitsLoaded = 0;
    private int accessDenied = 0;
    private int humanApprovals = 0;
    private int auditEntries = 0;

    public GridOrchestratorAgent(ConsoleLog log, boolean autoApprove) {
        this.log = log;
        this.autoApprove = autoApprove;
    }

    /** Run the full simulation. */
    public void run(Path agentCardPath, Path manifestPath) throws IOException {
        log.header("SCENARIO 1: Smart Energy Metering");
        log.plain("Domain: Utility Grid Operations | KCP v0.9 | A2A Agent Card v1.0.0");
        log.plain("Agents: GridOrchestratorAgent -> EnergyMeteringAgent");
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

        log.a2a("GridOrchestratorAgent reads agent-card.json");
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

        energyAgent = new EnergyMeteringAgent();
        energyAgent.loadManifest(manifestPath);
        energyAgent.printDiscovery(log);
    }

    private void phase3Authentication() {
        log.section("Phase 3: Authentication (A2A + OAuth2)");
        log.blank();

        oauth2 = new SimulatedOAuth2();
        approvalGate = new HumanApprovalGate(autoApprove, log);
        decisionEngine = new AccessDecisionEngine(oauth2, energyAgent.unitDelegation());

        log.a2a("GridOrchestratorAgent requests OAuth2 token (client_credentials flow)");

        Set<String> scopes = Set.of("read:tariff", "read:meter", "read:billing", "grid-engineer");
        log.a2a("Scopes requested: " + String.join(", ", scopes));

        token = oauth2.issueToken("grid-orchestrator-agent", scopes);
        log.a2a("Token issued: " + token.maskedValue() + " (expires in 3600s)");
    }

    private void phase4KnowledgeAccess() {
        log.section("Phase 4: Knowledge Access (Escalating Access Levels)");

        // Request all 4 units in order of escalating access
        String[] unitIds = {"tariff-schedule", "meter-readings", "billing-history", "smart-meter-raw"};

        int qNum = 0;
        for (String unitId : unitIds) {
            qNum++;
            log.blank();
            accessUnit("U" + qNum, unitId);
        }
    }

    private void accessUnit(String label, String unitId) {
        log.query(label, "Requesting unit: " + unitId);

        // Find the unit
        KnowledgeUnit unit = energyAgent.manifest().units().stream()
                .filter(u -> unitId.equals(u.id()))
                .findFirst()
                .orElse(null);

        if (unit == null) {
            log.kcp("Unit not found: " + unitId);
            return;
        }

        String access = unit.access() != null ? unit.access() : "public";
        String sensitivity = unit.sensitivity() != null ? unit.sensitivity() : "public";
        String authScope = unit.authScope() != null ? unit.authScope() : "none";

        log.kcp("Matched unit: " + unit.id() + " | access: " + access
                + " | sensitivity: " + sensitivity);

        // Access decision
        AccessDecision decision = decisionEngine.evaluate(unit, token.tokenValue());

        switch (access) {
            case "public":
                log.kcp("Access: public -> no credential required");
                break;
            case "authenticated":
                log.kcp("Access: authenticated -> checking credential");
                log.kcp("Token valid: yes | Scope '" + authScope + "': yes");
                break;
            case "restricted":
                log.kcp("Access: restricted | auth_scope: " + authScope + " | sensitivity: " + sensitivity);
                log.kcp("Token valid: yes | Scope '" + authScope + "': yes");
                DelegationConfig del = energyAgent.unitDelegation().get(unitId);
                if (del != null && del.maxDepth() != energyAgent.rootDelegation().maxDepth()) {
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
                log.kcp("Content: " + EnergyMeteringAgent.getContentPreview(hitl.unitId()));
                unitsLoaded++;
                String trace = TraceContext.newTraceparent();
                auditEntries++;
                log.audit("trace: " + trace);
                log.audit("unit: " + hitl.unitId() + " | human_approval: " + approver);
            } else {
                log.kcp("Result: DENIED (human approval refused)");
                accessDenied++;
            }
        } else if (decision instanceof AccessDecision.Denied denied) {
            log.kcp("Result: DENIED (" + denied.reason() + ")");
            accessDenied++;
        }
    }

    private void logGranted(String unitId) {
        log.kcp("Result: LOADED");
        log.kcp("Content: " + EnergyMeteringAgent.getContentPreview(unitId));
        unitsLoaded++;
        String trace = TraceContext.newTraceparent();
        auditEntries++;
        log.audit("trace: " + trace);
    }

    private void printSummary() {
        log.header("SIMULATION SUMMARY");
        log.summary("Units loaded successfully:  " + unitsLoaded);
        log.summary("Access denied:              " + accessDenied);
        log.summary("HITL approvals:             " + humanApprovals);
        log.summary("Audit entries:              " + auditEntries);
        log.blank();
        log.summary("The A2A Agent Card handled agent discovery and OAuth2 authentication.");
        log.summary("The KCP manifest enforced per-unit access at 4 escalating levels.");
        log.summary("Together: informed, auditable, policy-aware knowledge access.");
    }

    // --- Accessors for testing ---
    public int unitsLoaded() { return unitsLoaded; }
    public int accessDenied() { return accessDenied; }
    public int humanApprovals() { return humanApprovals; }
    public int auditEntries() { return auditEntries; }
}
