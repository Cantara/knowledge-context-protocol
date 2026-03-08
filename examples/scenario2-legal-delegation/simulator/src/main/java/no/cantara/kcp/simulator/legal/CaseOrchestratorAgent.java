package no.cantara.kcp.simulator.legal;

import no.cantara.kcp.model.KnowledgeUnit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * The Case Orchestrator Agent: top-level agent that drives the full A2A + KCP
 * interaction and demonstrates the 3-hop delegation chain.
 *
 * <p>Phase 1: Direct access (CaseOrchestrator at depth=1 requests from LegalResearchAgent)
 * <p>Phase 2: Delegation chain (LegalResearchAgent delegates to ExternalCaseLawAgent at depth=2)
 */
public final class CaseOrchestratorAgent {

    private final ConsoleLog log;
    private final boolean autoApprove;

    private AgentCard agentCard;
    private LegalResearchAgent legalAgent;
    private SimulatedOAuth2 oauth2;
    private SimulatedToken orchestratorToken;
    private HumanApprovalGate approvalGate;
    private AccessDecisionEngine decisionEngine;
    private DelegationChainEngine delegationEngine;

    // Stats
    private int unitsLoaded = 0;
    private int accessDenied = 0;
    private int delegationBlocked = 0;
    private int humanApprovals = 0;
    private int auditEntries = 0;

    public CaseOrchestratorAgent(ConsoleLog log, boolean autoApprove) {
        this.log = log;
        this.autoApprove = autoApprove;
    }

    /** Run the full simulation. */
    public void run(Path agentCardPath, Path manifestPath) throws IOException {
        log.header("SCENARIO 2: Legal Document Review (3-Hop Delegation)");
        log.plain("Domain: Law Firm Document Review | KCP v0.6 | A2A Agent Card v1.0.0");
        log.plain("Agents: CaseOrchestratorAgent -> LegalResearchAgent -> ExternalCaseLawAgent");
        log.blank();

        phase0Setup(agentCardPath, manifestPath);
        log.blank();
        phase1DirectAccess();
        log.blank();
        phase2DelegationChain();
        log.blank();
        printSummary();
    }

    private void phase0Setup(Path agentCardPath, Path manifestPath) throws IOException {
        // --- A2A Discovery ---
        log.section("Setup: Agent Discovery + KCP Discovery + Authentication");
        log.blank();

        agentCard = AgentCardParser.parse(agentCardPath);
        log.a2a("CaseOrchestratorAgent reads agent-card.json");
        log.a2a("Discovered: \"" + agentCard.name() + "\" at " + agentCard.url());

        StringBuilder skillNames = new StringBuilder();
        for (Map<String, Object> skill : agentCard.skills()) {
            if (!skillNames.isEmpty()) skillNames.append(", ");
            skillNames.append(skill.get("id"));
        }
        log.a2a("Skills: " + skillNames);
        log.a2a("Knowledge manifest: " + agentCard.knowledgeManifest());
        log.blank();

        // --- KCP Discovery ---
        legalAgent = new LegalResearchAgent();
        legalAgent.loadManifest(manifestPath);
        legalAgent.printDiscovery(log);
        log.blank();

        // --- Authentication ---
        oauth2 = new SimulatedOAuth2();
        approvalGate = new HumanApprovalGate(autoApprove, log);
        decisionEngine = new AccessDecisionEngine(oauth2, legalAgent.unitDelegation());
        delegationEngine = new DelegationChainEngine(
                legalAgent.rootDelegation(), legalAgent.unitDelegation());

        Set<String> scopes = Set.of("read:precedents", "read:case", "attorney", "court-officer");
        orchestratorToken = oauth2.issueToken("case-orchestrator-agent", scopes);
        log.a2a("Token issued to CaseOrchestratorAgent: " + orchestratorToken.maskedValue());
    }

    /**
     * Phase 1: CaseOrchestratorAgent directly requests units from LegalResearchAgent.
     * The orchestrator acts as a depth=1 delegatee (the resource owner is depth=0).
     */
    private void phase1DirectAccess() {
        log.section("Phase 1: Direct Access (CaseOrchestrator -> LegalResearch, depth=1)");

        // 1a: public-precedents (public, immediate)
        log.blank();
        accessUnit("1a", "public-precedents", orchestratorToken);

        // 1b: case-briefs (authenticated, token validated)
        log.blank();
        accessUnit("1b", "case-briefs", orchestratorToken);

        // 1c: sealed-records (restricted, but max_depth=0 means no delegation allowed)
        // The orchestrator IS at depth=1 (it's a delegatee, not the owner).
        // sealed-records has max_depth=0 -> DENIED because any delegation is forbidden.
        log.blank();
        log.query("1c", "Requesting unit: sealed-records (depth=1, orchestrator is delegatee)");
        KnowledgeUnit sealed = findUnit("sealed-records");
        if (sealed != null) {
            log.kcp("Matched unit: sealed-records | access: restricted | sensitivity: restricted");
            log.kcp("Delegation check: max_depth=0 for this unit");
            var delDecision = delegationEngine.checkDelegation("sealed-records", 1,
                    "court-officer", null);
            if (delDecision instanceof DelegationChainEngine.DelegationDecision.Blocked blocked) {
                log.kcp("Result: DENIED (" + blocked.reason() + ")");
                accessDenied++;
                auditEntries++;
                String trace = TraceContext.newTraceparent();
                log.audit("trace: " + trace);
                log.audit("VIOLATION: delegation attempt on no-delegation unit 'sealed-records'");
            }
        }
    }

    /**
     * Phase 2: LegalResearchAgent delegates knowledge to ExternalCaseLawAgent.
     * The external agent is at depth=2.
     */
    private void phase2DelegationChain() {
        log.section("Phase 2: Delegation Chain (LegalResearch -> ExternalCaseLaw, depth=2)");

        // Issue a narrower token for the external agent
        SimulatedToken externalToken;

        // 2a: Delegate public-precedents (public, no depth constraint)
        log.blank();
        log.query("2a", "Delegating 'public-precedents' to ExternalCaseLawAgent (depth=2)");
        log.kcp("Public unit: no delegation constraint applies");
        log.kcp("Result: DELEGATED (public access, depth irrelevant)");
        unitsLoaded++;
        auditEntries++;
        log.audit("trace: " + TraceContext.newTraceparent());

        // 2b: Attempt to delegate case-briefs with SAME scope (attenuation violation)
        log.blank();
        log.query("2b", "Delegating 'case-briefs' to ExternalCaseLawAgent with scope 'read:case'");
        log.kcp("Checking capability attenuation (require_capability_attenuation=true)");
        var attenuationCheck = delegationEngine.checkDelegation(
                "case-briefs", 2, "read:case", "read:case");
        if (attenuationCheck instanceof DelegationChainEngine.DelegationDecision.Blocked blocked) {
            log.kcp("Result: BLOCKED (" + blocked.reason() + ")");
            delegationBlocked++;
            auditEntries++;
            log.audit("trace: " + TraceContext.newTraceparent());
            log.audit("ATTENUATION VIOLATION: same-scope delegation blocked");
        }

        // 2b-fix: Re-issue with narrowed scope
        log.blank();
        log.query("2b-fix", "Re-delegating 'case-briefs' with narrowed scope 'read:case:external-summary'");
        var narrowedCheck = delegationEngine.checkDelegation(
                "case-briefs", 2, "read:case", "read:case:external-summary");
        if (narrowedCheck instanceof DelegationChainEngine.DelegationDecision.Allowed allowed) {
            externalToken = oauth2.issueToken("external-caselaw-agent",
                    Set.of("read:case:external-summary"));
            log.kcp("Narrowed scope: 'read:case' -> 'read:case:external-summary'");
            log.kcp("Result: DELEGATED (attenuated scope, depth=" + allowed.depth() + ")");
            unitsLoaded++;
            auditEntries++;
            log.audit("trace: " + TraceContext.newTraceparent());
        }

        // 2c: Delegate client-communications at depth=2 (max_depth=2, exactly allowed, HITL)
        log.blank();
        log.query("2c", "Delegating 'client-communications' to ExternalCaseLawAgent (depth=2)");
        var commsCheck = delegationEngine.checkDelegation(
                "client-communications", 2, "attorney", "attorney:read-only");
        if (commsCheck instanceof DelegationChainEngine.DelegationDecision.Allowed allowed) {
            log.kcp("Delegation depth " + allowed.depth() + " <= max_depth=2: OK");
            log.kcp("Capability attenuation: 'attorney' -> 'attorney:read-only': OK");
            // HITL is required for this unit
            DelegationConfig del = legalAgent.unitDelegation().get("client-communications");
            if (del != null && del.humanInTheLoopRequired()) {
                log.kcp("Human-in-the-loop: REQUIRED (approval_mechanism: "
                        + del.approvalMechanismOpt().orElse("manual") + ")");
                String approver = approvalGate.requestApproval(
                        "client-communications", "confidential", "manual");
                if (approver != null) {
                    humanApprovals++;
                    log.kcp("Result: DELEGATED (after human approval, depth=2)");
                    unitsLoaded++;
                    auditEntries++;
                    log.audit("trace: " + TraceContext.newTraceparent());
                    log.audit("unit: client-communications | human_approval: " + approver
                            + " | delegated_to: ExternalCaseLawAgent | depth: 2");
                } else {
                    log.kcp("Result: DENIED (human approval refused)");
                    accessDenied++;
                }
            }
        }

        // 2d: Attempt client-communications at hypothetical depth=3 (exceeds max_depth=2)
        log.blank();
        log.query("2d", "Hypothetical: delegate 'client-communications' to depth=3");
        var depth3Check = delegationEngine.checkDelegation(
                "client-communications", 3, "attorney", "attorney:summary-only");
        if (depth3Check instanceof DelegationChainEngine.DelegationDecision.Blocked blocked) {
            log.kcp("Result: BLOCKED (" + blocked.reason() + ")");
            delegationBlocked++;
            auditEntries++;
            log.audit("trace: " + TraceContext.newTraceparent());
            log.audit("DEPTH VIOLATION: depth=3 exceeds max_depth=2 for 'client-communications'");
        }
    }

    private void accessUnit(String label, String unitId, SimulatedToken token) {
        log.query(label, "Requesting unit: " + unitId);

        KnowledgeUnit unit = findUnit(unitId);
        if (unit == null) {
            log.kcp("Unit not found: " + unitId);
            return;
        }

        String access = unit.access() != null ? unit.access() : "public";
        String sensitivity = unit.sensitivity() != null ? unit.sensitivity() : "public";
        String authScope = unit.authScope() != null ? unit.authScope() : "none";

        log.kcp("Matched unit: " + unit.id() + " | access: " + access
                + " | sensitivity: " + sensitivity);

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
                log.kcp("Access: restricted | auth_scope: " + authScope);
                break;
        }

        if (decision instanceof AccessDecision.Granted granted) {
            logGranted(granted.unitId());
        } else if (decision instanceof AccessDecision.RequiresHumanApproval hitl) {
            log.kcp("Human-in-the-loop: REQUIRED (approval_mechanism: " + hitl.approvalMechanism() + ")");
            String approver = approvalGate.requestApproval(hitl.unitId(), hitl.sensitivity(), hitl.approvalMechanism());
            if (approver != null) {
                humanApprovals++;
                log.kcp("Result: LOADED (after human approval)");
                log.kcp("Content: " + LegalResearchAgent.getContentPreview(hitl.unitId()));
                unitsLoaded++;
                auditEntries++;
                log.audit("trace: " + TraceContext.newTraceparent());
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

    private KnowledgeUnit findUnit(String unitId) {
        return legalAgent.manifest().units().stream()
                .filter(u -> unitId.equals(u.id()))
                .findFirst()
                .orElse(null);
    }

    private void logGranted(String unitId) {
        log.kcp("Result: LOADED");
        log.kcp("Content: " + LegalResearchAgent.getContentPreview(unitId));
        unitsLoaded++;
        auditEntries++;
        log.audit("trace: " + TraceContext.newTraceparent());
    }

    private void printSummary() {
        log.header("SIMULATION SUMMARY");
        log.summary("Units loaded successfully:  " + unitsLoaded);
        log.summary("Access denied (policy):     " + accessDenied);
        log.summary("Access denied (delegation): " + delegationBlocked);
        log.summary("HITL approvals:             " + humanApprovals);
        log.summary("Audit entries:              " + auditEntries);
        log.blank();
        log.summary("Key delegation behaviours demonstrated:");
        log.summary("  - max_depth=0 on sealed-records: absolute no-delegation");
        log.summary("  - Capability attenuation: equal scope blocked, narrowed scope allowed");
        log.summary("  - Depth counting: owner=0, first delegatee=1, second=2");
        log.summary("  - HITL at depth=2 for client-communications (exactly at max_depth)");
    }

    // --- Accessors for testing ---
    public int unitsLoaded() { return unitsLoaded; }
    public int accessDenied() { return accessDenied; }
    public int delegationBlocked() { return delegationBlocked; }
    public int humanApprovals() { return humanApprovals; }
    public int auditEntries() { return auditEntries; }
}
