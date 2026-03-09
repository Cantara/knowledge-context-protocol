package no.cantara.kcp.simulator.aml;

import no.cantara.kcp.model.KnowledgeUnit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Compliance Orchestrator Agent: drives the full 7-phase AML simulation
 * including legitimate access, delegation chains, adversarial attacks, and
 * compliance enforcement.
 *
 * <p>5 agents participate:
 * <ul>
 *   <li>ComplianceOrchestratorAgent (this class) - top-level coordinator</li>
 *   <li>TransactionIntelligenceAgent - owns 5 knowledge units</li>
 *   <li>SanctionsScreeningAgent - legitimate depth=1 delegatee</li>
 *   <li>MLRiskScoringAgent - legitimate depth=2 delegatee</li>
 *   <li>RogueAgent - adversarial agent attempting various violations</li>
 * </ul>
 */
public final class ComplianceOrchestratorAgent {

    private final ConsoleLog log;
    private final boolean autoApprove;

    private AgentCard agentCard;
    private TransactionIntelligenceAgent txAgent;
    private SimulatedOAuth2 oauth2;
    private SimulatedToken orchestratorToken;
    private HumanApprovalGate approvalGate;
    private AccessDecisionEngine decisionEngine;
    private DelegationChainEngine delegationEngine;
    private ComplianceEngine complianceEngine;
    private RateLimitAdvisor rateLimitAdvisor;

    // Stats
    private int unitsLoaded = 0;
    private int accessDeniedPolicy = 0;
    private int accessDeniedDelegation = 0;
    private int accessDeniedCompliance = 0;
    private int humanApprovals = 0;
    private int auditEntries = 0;
    private int violationsDetected = 0;
    private int rateLimitAdvisoryViolations = 0;

    public ComplianceOrchestratorAgent(ConsoleLog log, boolean autoApprove) {
        this.log = log;
        this.autoApprove = autoApprove;
    }

    /** Run the full 8-phase simulation. */
    public void run(Path agentCardPath, Path manifestPath) throws IOException {
        log.header("SCENARIO 3: Financial AML Intelligence (Adversarial)");
        log.plain("Domain: Anti-Money-Laundering Compliance | KCP v0.8 | A2A Agent Card v1.0.0");
        log.plain("Agents: ComplianceOrchestrator, TransactionIntelligence, SanctionsScreening, MLRiskScoring, RogueAgent");
        log.blank();

        setup(agentCardPath, manifestPath);
        log.blank();

        phase1HappyPath();
        log.blank();

        phase2HitlChain();
        log.blank();

        phase3LegitDelegation();
        log.blank();

        phase4RogueDepthViolation();
        log.blank();

        phase5RogueScopeElevation();
        log.blank();

        phase6RogueNoDelegation();
        log.blank();

        phase7ComplianceBlock();
        log.blank();

        phase8RogueRateLimitBurst();
        log.blank();

        printSummary();
    }

    private void setup(Path agentCardPath, Path manifestPath) throws IOException {
        log.section("Setup: Discovery + Authentication");
        log.blank();

        agentCard = AgentCardParser.parse(agentCardPath);
        log.a2a("ComplianceOrchestratorAgent reads agent-card.json");
        log.a2a("Discovered: \"" + agentCard.name() + "\" at " + agentCard.url());

        StringBuilder skillNames = new StringBuilder();
        for (Map<String, Object> skill : agentCard.skills()) {
            if (!skillNames.isEmpty()) skillNames.append(", ");
            skillNames.append(skill.get("id"));
        }
        log.a2a("Skills: " + skillNames);
        log.blank();

        txAgent = new TransactionIntelligenceAgent();
        txAgent.loadManifest(manifestPath);
        txAgent.printDiscovery(log);
        log.blank();

        oauth2 = new SimulatedOAuth2();
        approvalGate = new HumanApprovalGate(autoApprove, log);
        decisionEngine = new AccessDecisionEngine(oauth2, txAgent.unitDelegation());
        delegationEngine = new DelegationChainEngine(txAgent.rootDelegation(), txAgent.unitDelegation());
        complianceEngine = new ComplianceEngine(txAgent.unitCompliance());
        rateLimitAdvisor = buildRateLimitAdvisor(manifestPath);

        orchestratorToken = oauth2.issueToken("compliance-orchestrator",
                Set.of("read:sanctions", "read:transactions", "aml-analyst", "compliance-officer"));
        log.a2a("Token issued to ComplianceOrchestratorAgent: " + orchestratorToken.maskedValue());
    }

    /**
     * Phase 1: Happy path -- load public + authenticated units.
     */
    private void phase1HappyPath() {
        log.section("Phase 1: Happy Path (public + authenticated access)");

        log.blank();
        accessUnit("P1-a", "sanctions-lists", orchestratorToken);

        log.blank();
        accessUnit("P1-b", "transaction-patterns", orchestratorToken);
    }

    /**
     * Phase 2: HITL chain -- restricted units requiring human approval.
     */
    private void phase2HitlChain() {
        log.section("Phase 2: HITL Chain (restricted units, human approval required)");

        log.blank();
        accessUnit("P2-a", "customer-profiles", orchestratorToken);

        log.blank();
        accessUnit("P2-b", "sar-drafts", orchestratorToken);
    }

    /**
     * Phase 3: Legitimate delegation chain with capability attenuation.
     * TransactionIntelligence -> SanctionsScreening (depth=1) -> MLRiskScoring (depth=2)
     */
    private void phase3LegitDelegation() {
        log.section("Phase 3: Legitimate Delegation Chain (depth=1 -> depth=2)");

        // Delegate transaction-patterns to SanctionsScreeningAgent with narrowed scope
        log.blank();
        log.query("P3-a", "Delegating 'transaction-patterns' to SanctionsScreeningAgent (depth=1)");
        var check1 = delegationEngine.checkDelegation("transaction-patterns", 1,
                "read:transactions", "read:transactions:sanctions-only");
        if (check1 instanceof DelegationChainEngine.DelegationDecision.Allowed allowed) {
            SimulatedToken sanctionsToken = oauth2.issueToken("sanctions-screening-agent",
                    Set.of("read:transactions:sanctions-only"));
            log.kcp("Scope narrowed: 'read:transactions' -> '" + allowed.effectiveScope() + "'");
            log.kcp("Result: DELEGATED to SanctionsScreeningAgent (depth=1)");
            unitsLoaded++;
            auditEntries++;
            log.audit("trace: " + TraceContext.newTraceparent());

            // SanctionsScreening delegates to MLRiskScoring (depth=2)
            log.blank();
            log.query("P3-b", "SanctionsScreeningAgent delegates to MLRiskScoringAgent (depth=2)");
            var check2 = delegationEngine.checkDelegation("transaction-patterns", 2,
                    "read:transactions:sanctions-only", "read:transactions:risk-signals");
            if (check2 instanceof DelegationChainEngine.DelegationDecision.Allowed allowed2) {
                log.kcp("Scope narrowed: 'read:transactions:sanctions-only' -> '" + allowed2.effectiveScope() + "'");
                log.kcp("Result: DELEGATED to MLRiskScoringAgent (depth=2)");
                unitsLoaded++;
                auditEntries++;
                log.audit("trace: " + TraceContext.newTraceparent());
            }
        }
    }

    /**
     * Phase 4: RogueAgent attempts delegation depth violation on customer-profiles.
     */
    private void phase4RogueDepthViolation() {
        log.section("Phase 4: RogueAgent -- Delegation Depth Violation");

        log.blank();
        log.query("P4", "RogueAgent claims depth=3 delegatee for 'customer-profiles' (max_depth=2)");
        var check = delegationEngine.checkDelegation("customer-profiles", 3,
                "aml-analyst", "aml-analyst:external");
        if (check instanceof DelegationChainEngine.DelegationDecision.Blocked blocked) {
            log.kcp("Result: BLOCKED (" + blocked.reason() + ")");
            accessDeniedDelegation++;
            violationsDetected++;
            auditEntries++;
            log.audit("trace: " + TraceContext.newTraceparent());
            log.audit("VIOLATION: RogueAgent depth=3 exceeds max_depth=2 for 'customer-profiles'");
        }
    }

    /**
     * Phase 5: RogueAgent attempts scope elevation -- holds read:transactions but
     * requests customer-profiles which requires aml-analyst scope.
     */
    private void phase5RogueScopeElevation() {
        log.section("Phase 5: RogueAgent -- Scope Elevation Attempt");

        log.blank();
        log.query("P5", "RogueAgent has 'read:transactions' token, requests 'customer-profiles' (requires 'aml-analyst')");

        // Issue a token with only read:transactions scope
        SimulatedToken rogueToken = oauth2.issueToken("rogue-agent", Set.of("read:transactions"));

        KnowledgeUnit unit = findUnit("customer-profiles");
        if (unit != null) {
            AccessDecision decision = decisionEngine.evaluate(unit, rogueToken.tokenValue());
            if (decision instanceof AccessDecision.Denied denied) {
                log.kcp("Token scope: [read:transactions]");
                log.kcp("Required scope: aml-analyst");
                log.kcp("Result: BLOCKED (" + denied.reason() + ")");
                accessDeniedPolicy++;
                violationsDetected++;
                auditEntries++;
                log.audit("trace: " + TraceContext.newTraceparent());
                log.audit("VIOLATION: RogueAgent scope elevation attempt -- 'read:transactions' cannot access 'aml-analyst' unit");
            }
        }
    }

    /**
     * Phase 6: RogueAgent attempts to access raw-wire-transfers as a delegatee.
     * max_depth=0 -> no delegation at all.
     */
    private void phase6RogueNoDelegation() {
        log.section("Phase 6: RogueAgent -- max_depth=0 Violation Attempt");

        log.blank();
        log.query("P6", "RogueAgent attempts 'raw-wire-transfers' as delegatee (max_depth=0)");
        var check = delegationEngine.checkDelegation("raw-wire-transfers", 1,
                "compliance-officer", "compliance-officer:read");
        if (check instanceof DelegationChainEngine.DelegationDecision.Blocked blocked) {
            log.kcp("Result: BLOCKED (" + blocked.reason() + ")");
            accessDeniedDelegation++;
            violationsDetected++;
            auditEntries++;
            log.audit("trace: " + TraceContext.newTraceparent());
            log.audit("VIOLATION: RogueAgent delegation attempt on no-delegation unit 'raw-wire-transfers'");
        }
    }

    /**
     * Phase 7: Compliance block -- customer-profiles request from US region
     * when data_residency restricts to [EU].
     */
    private void phase7ComplianceBlock() {
        log.section("Phase 7: Compliance Block (GDPR Data Residency)");

        log.blank();
        log.query("P7", "Request for 'customer-profiles' with data_residency claim: US");
        log.kcp("Unit 'customer-profiles' has compliance.data_residency.regions: [EU]");

        var decision = complianceEngine.checkDataResidency("customer-profiles", "US");
        if (decision instanceof ComplianceEngine.ComplianceDecision.Violation violation) {
            log.kcp("Result: BLOCKED (" + violation.reason() + ")");
            log.kcp("Regulation: " + violation.regulation());
            accessDeniedCompliance++;
            violationsDetected++;
            auditEntries++;
            log.audit("trace: " + TraceContext.newTraceparent());
            log.audit("COMPLIANCE VIOLATION: GDPR data residency -- US request blocked for EU-only unit");
        }

        // Also note the restrictions on raw-wire-transfers
        log.blank();
        log.query("P7-note", "Checking restrictions on 'raw-wire-transfers'");
        ComplianceConfig wireConfig = complianceEngine.getConfig("raw-wire-transfers");
        log.kcp("Regulations: " + wireConfig.regulations());
        log.kcp("Restrictions: " + wireConfig.restrictions());
        log.kcp("Data residency: " + wireConfig.dataResidencyRegions());
        log.kcp("Note: 'no_ai_training' restriction is declared but enforcement is application-defined (v0.7 gap)");
        auditEntries++;
        log.audit("trace: " + TraceContext.newTraceparent());
    }

    /**
     * Phase 8: RogueAgent burst-requests customer-profiles beyond rate_limits advisory.
     * Advisory: requests succeed (rate_limits not enforced) but violations are logged.
     */
    private void phase8RogueRateLimitBurst() {
        log.section("Phase 8: RogueAgent -- Rate Limit Advisory Burst");

        log.blank();
        log.query("P8", "RogueAgent burst-requesting 'customer-profiles' (advisory limit: 5/min)");

        RateLimitAdvisor.AdvisoryLimit limit = rateLimitAdvisor.getLimit("customer-profiles");
        log.kcp("Advisory rate_limits for 'customer-profiles': "
                + (limit.requestsPerMinute() != null ? limit.requestsPerMinute() + "/min" : "unlimited")
                + ", " + (limit.requestsPerDay() != null ? limit.requestsPerDay() + "/day" : "unlimited"));
        log.kcp("Advisory: rate_limits is not enforced — requests will succeed but violations are logged");

        // RogueAgent bursts 10 requests in rapid succession
        int burstCount = 10;
        for (int i = 1; i <= burstCount; i++) {
            boolean withinLimit = rateLimitAdvisor.recordRequest("customer-profiles", "RogueAgent");
            if (!withinLimit) {
                rateLimitAdvisoryViolations++;
                log.kcp("Request #" + i + ": ADVISORY_VIOLATION (exceeded rate_limits)");
            } else {
                log.kcp("Request #" + i + ": within advisory limit");
            }
        }

        long violations = rateLimitAdvisor.violationCount("customer-profiles");
        log.kcp("Result: " + burstCount + " requests completed, " + violations + " advisory violations");
        log.kcp("RogueAgent exceeded rate_limits advisory for customer-profiles");
        auditEntries++;
        log.audit("trace: " + TraceContext.newTraceparent());
        log.audit("RATE_LIMIT_ADVISORY: RogueAgent burst " + burstCount
                + " requests to 'customer-profiles' — " + violations + " exceeded advisory " + limit.requestsPerMinute() + "/min");
    }

    @SuppressWarnings("unchecked")
    private RateLimitAdvisor buildRateLimitAdvisor(Path manifestPath) throws IOException {
        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml(
                new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()));
        Map<String, Object> rawData;
        try (java.io.InputStream is = java.nio.file.Files.newInputStream(manifestPath)) {
            rawData = yaml.load(is);
        }

        // Extract root default
        Integer rootRpm = null;
        Integer rootRpd = null;
        Map<String, Object> rootLimits = (Map<String, Object>) rawData.get("rate_limits");
        if (rootLimits != null) {
            Map<String, Object> rootDefault = (Map<String, Object>) rootLimits.get("default");
            if (rootDefault != null) {
                rootRpm = rootDefault.get("requests_per_minute") instanceof Number n ? n.intValue() : null;
                rootRpd = rootDefault.get("requests_per_day") instanceof Number n ? n.intValue() : null;
            }
        }

        Map<String, RateLimitAdvisor.AdvisoryLimit> unitLimits = new HashMap<>();
        List<Map<String, Object>> rawUnits = (List<Map<String, Object>>) rawData.getOrDefault("units", List.of());
        for (Map<String, Object> rawUnit : rawUnits) {
            String id = (String) rawUnit.get("id");
            if (id == null) continue;

            Map<String, Object> unitRateLimits = (Map<String, Object>) rawUnit.get("rate_limits");
            if (unitRateLimits != null) {
                Map<String, Object> unitDefault = (Map<String, Object>) unitRateLimits.get("default");
                if (unitDefault != null) {
                    Integer rpm = unitDefault.get("requests_per_minute") instanceof Number n ? n.intValue() : null;
                    Integer rpd = unitDefault.get("requests_per_day") instanceof Number n ? n.intValue() : null;
                    unitLimits.put(id, new RateLimitAdvisor.AdvisoryLimit(rpm, rpd));
                    continue;
                }
            }
            // Fall back to root default
            unitLimits.put(id, new RateLimitAdvisor.AdvisoryLimit(rootRpm, rootRpd));
        }

        return new RateLimitAdvisor(unitLimits);
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
                log.kcp("Access: restricted | auth_scope: " + authScope + " | sensitivity: " + sensitivity);
                DelegationConfig del = txAgent.unitDelegation().get(unitId);
                if (del != null) {
                    log.kcp("Delegation: max_depth=" + del.maxDepth());
                }
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
                log.kcp("Content: " + TransactionIntelligenceAgent.getContentPreview(hitl.unitId()));
                unitsLoaded++;
                auditEntries++;
                String trace = TraceContext.newTraceparent();
                log.audit("trace: " + trace);
                log.audit("unit: " + hitl.unitId() + " | human_approval: " + approver);
            } else {
                log.kcp("Result: DENIED (human approval refused)");
                accessDeniedPolicy++;
            }
        } else if (decision instanceof AccessDecision.Denied denied) {
            log.kcp("Result: DENIED (" + denied.reason() + ")");
            accessDeniedPolicy++;
        }
    }

    private KnowledgeUnit findUnit(String unitId) {
        return txAgent.manifest().units().stream()
                .filter(u -> unitId.equals(u.id()))
                .findFirst()
                .orElse(null);
    }

    private void logGranted(String unitId) {
        log.kcp("Result: LOADED");
        log.kcp("Content: " + TransactionIntelligenceAgent.getContentPreview(unitId));
        unitsLoaded++;
        auditEntries++;
        log.audit("trace: " + TraceContext.newTraceparent());
    }

    private void printSummary() {
        log.header("SIMULATION SUMMARY");
        log.summary("Units loaded successfully:    " + unitsLoaded);
        log.summary("Access denied (policy):       " + accessDeniedPolicy);
        log.summary("Access denied (delegation):   " + accessDeniedDelegation);
        log.summary("Access denied (compliance):   " + accessDeniedCompliance);
        log.summary("HITL approvals:               " + humanApprovals);
        log.summary("Rate limit advisory violations: " + rateLimitAdvisoryViolations);
        log.summary("Audit entries:                " + auditEntries);
        log.summary("Violations detected:          " + violationsDetected);
    }

    // --- Accessors for testing ---
    public int unitsLoaded() { return unitsLoaded; }
    public int accessDeniedPolicy() { return accessDeniedPolicy; }
    public int accessDeniedDelegation() { return accessDeniedDelegation; }
    public int accessDeniedCompliance() { return accessDeniedCompliance; }
    public int humanApprovals() { return humanApprovals; }
    public int auditEntries() { return auditEntries; }
    public int violationsDetected() { return violationsDetected; }
    public int rateLimitAdvisoryViolations() { return rateLimitAdvisoryViolations; }
    public RateLimitAdvisor rateLimitAdvisor() { return rateLimitAdvisor; }
}
