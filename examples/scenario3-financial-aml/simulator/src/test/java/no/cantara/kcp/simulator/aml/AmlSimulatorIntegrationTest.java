package no.cantara.kcp.simulator.aml;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: runs the full 7-phase AML simulation with --auto-approve
 * and verifies all phases, violations, and summary counts.
 */
class AmlSimulatorIntegrationTest {

    private Path agentCardPath() {
        Path relative = Path.of("../agent-card.json");
        if (relative.toFile().exists()) return relative;
        return Path.of("/src/cantara/knowledge-context-protocol/examples/scenario3-financial-aml/agent-card.json");
    }

    private Path manifestPath() {
        Path relative = Path.of("../knowledge.yaml");
        if (relative.toFile().exists()) return relative;
        return Path.of("/src/cantara/knowledge-context-protocol/examples/scenario3-financial-aml/knowledge.yaml");
    }

    private String runSimulator() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(baos);
        ConsoleLog log = new ConsoleLog(capture);

        ComplianceOrchestratorAgent orchestrator = new ComplianceOrchestratorAgent(log, true);
        orchestrator.run(agentCardPath(), manifestPath());

        return baos.toString();
    }

    // --- Setup ---

    @Test
    void outputContainsAgentDiscovery() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("Discovered: \"Transaction Intelligence Agent\""),
                "Should discover agent");
    }

    @Test
    void outputContainsAllFiveUnits() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("sanctions-lists"), "Should list sanctions-lists");
        assertTrue(output.contains("transaction-patterns"), "Should list transaction-patterns");
        assertTrue(output.contains("customer-profiles"), "Should list customer-profiles");
        assertTrue(output.contains("sar-drafts"), "Should list sar-drafts");
        assertTrue(output.contains("raw-wire-transfers"), "Should list raw-wire-transfers");
    }

    @Test
    void outputContainsComplianceRegulations() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("GDPR"), "Should mention GDPR");
        assertTrue(output.contains("AML5D"), "Should mention AML5D");
    }

    // --- Phase 1: Happy path ---

    @Test
    void phase1_sanctionsListsLoaded() throws IOException {
        String output = runSimulator();
        int posP1a = output.indexOf("[P1-a]");
        int posP1b = output.indexOf("[P1-b]");
        assertTrue(posP1a >= 0 && posP1b > posP1a, "Should have P1-a and P1-b in order");
        String block = output.substring(posP1a, posP1b);
        assertTrue(block.contains("sanctions-lists"), "P1-a should access sanctions-lists");
        assertTrue(block.contains("Result: LOADED"), "P1-a should be LOADED");
    }

    @Test
    void phase1_transactionPatternsLoaded() throws IOException {
        String output = runSimulator();
        int posP1b = output.indexOf("[P1-b]");
        int posP2 = output.indexOf("Phase 2", posP1b);
        assertTrue(posP1b >= 0 && posP2 > posP1b, "Should have P1-b before Phase 2");
        String block = output.substring(posP1b, posP2);
        assertTrue(block.contains("transaction-patterns"), "P1-b should access transaction-patterns");
        assertTrue(block.contains("Result: LOADED"), "P1-b should be LOADED");
    }

    // --- Phase 2: HITL chain ---

    @Test
    void phase2_customerProfilesHitlApproved() throws IOException {
        String output = runSimulator();
        int posP2a = output.indexOf("[P2-a]");
        int posP2b = output.indexOf("[P2-b]");
        assertTrue(posP2a >= 0 && posP2b > posP2a, "Should have P2-a and P2-b");
        String block = output.substring(posP2a, posP2b);
        assertTrue(block.contains("customer-profiles"), "P2-a should access customer-profiles");
        assertTrue(block.contains("Human-in-the-loop: REQUIRED"), "P2-a should require HITL");
        assertTrue(block.contains("Result: LOADED"), "P2-a should be LOADED (after approval)");
    }

    @Test
    void phase2_sarDraftsHitlApproved() throws IOException {
        String output = runSimulator();
        int posP2b = output.indexOf("[P2-b]");
        int posP3 = output.indexOf("Phase 3", posP2b);
        assertTrue(posP2b >= 0 && posP3 > posP2b, "Should have P2-b before Phase 3");
        String block = output.substring(posP2b, posP3);
        assertTrue(block.contains("sar-drafts"), "P2-b should access sar-drafts");
        assertTrue(block.contains("Human-in-the-loop: REQUIRED"), "P2-b should require HITL");
        assertTrue(block.contains("Result: LOADED"), "P2-b should be LOADED (after approval)");
    }

    // --- Phase 3: Legitimate delegation ---

    @Test
    void phase3_legitimateDelegationChain() throws IOException {
        String output = runSimulator();
        int posP3a = output.indexOf("[P3-a]");
        int posP3b = output.indexOf("[P3-b]");
        assertTrue(posP3a >= 0 && posP3b > posP3a, "Should have P3-a and P3-b");

        String block3a = output.substring(posP3a, posP3b);
        assertTrue(block3a.contains("SanctionsScreeningAgent"), "P3-a should reference SanctionsScreeningAgent");
        assertTrue(block3a.contains("sanctions-only"), "P3-a should show narrowed scope");
        assertTrue(block3a.contains("DELEGATED"), "P3-a should be DELEGATED");

        int posP4 = output.indexOf("Phase 4", posP3b);
        String block3b = output.substring(posP3b, posP4);
        assertTrue(block3b.contains("MLRiskScoringAgent"), "P3-b should reference MLRiskScoringAgent");
        assertTrue(block3b.contains("risk-signals"), "P3-b should show further narrowed scope");
        assertTrue(block3b.contains("DELEGATED"), "P3-b should be DELEGATED");
    }

    // --- Phase 4: Rogue depth violation ---

    @Test
    void phase4_rogueDepthViolation() throws IOException {
        String output = runSimulator();
        int posP4 = output.indexOf("[P4]");
        int posP5 = output.indexOf("Phase 5", posP4);
        assertTrue(posP4 >= 0 && posP5 > posP4, "Should have P4 before Phase 5");
        String block = output.substring(posP4, posP5);
        assertTrue(block.contains("RogueAgent"), "P4 should reference RogueAgent");
        assertTrue(block.contains("depth=3"), "P4 should mention depth=3");
        assertTrue(block.contains("BLOCKED"), "P4 should be BLOCKED");
        assertTrue(block.contains("VIOLATION"), "P4 should log VIOLATION");
    }

    // --- Phase 5: Rogue scope elevation ---

    @Test
    void phase5_rogueScopeElevation() throws IOException {
        String output = runSimulator();
        int posP5 = output.indexOf("[P5]");
        int posP6 = output.indexOf("Phase 6", posP5);
        assertTrue(posP5 >= 0 && posP6 > posP5, "Should have P5 before Phase 6");
        String block = output.substring(posP5, posP6);
        assertTrue(block.contains("RogueAgent"), "P5 should reference RogueAgent");
        assertTrue(block.contains("read:transactions"), "P5 should show rogue scope");
        assertTrue(block.contains("aml-analyst"), "P5 should show required scope");
        assertTrue(block.contains("BLOCKED"), "P5 should be BLOCKED");
        assertTrue(block.contains("VIOLATION"), "P5 should log VIOLATION");
    }

    // --- Phase 6: Rogue no-delegation ---

    @Test
    void phase6_rogueNoDelegation() throws IOException {
        String output = runSimulator();
        int posP6 = output.indexOf("[P6]");
        int posP7 = output.indexOf("Phase 7", posP6);
        assertTrue(posP6 >= 0 && posP7 > posP6, "Should have P6 before Phase 7");
        String block = output.substring(posP6, posP7);
        assertTrue(block.contains("raw-wire-transfers"), "P6 should reference raw-wire-transfers");
        assertTrue(block.contains("max_depth=0") || block.contains("cannot be delegated"),
                "P6 should mention no-delegation");
        assertTrue(block.contains("BLOCKED"), "P6 should be BLOCKED");
    }

    // --- Phase 7: Compliance block ---

    @Test
    void phase7_complianceDataResidencyBlock() throws IOException {
        String output = runSimulator();
        int posP7 = output.indexOf("[P7]");
        assertTrue(posP7 >= 0, "Should have P7");
        int posNote = output.indexOf("[P7-note]", posP7);
        String block = output.substring(posP7, posNote > 0 ? posNote : output.length());
        assertTrue(block.contains("data_residency"), "P7 should mention data residency");
        assertTrue(block.contains("US"), "P7 should mention US request");
        assertTrue(block.contains("BLOCKED"), "P7 should be BLOCKED");
        assertTrue(block.contains("GDPR"), "P7 should mention GDPR");
    }

    @Test
    void phase7_restrictionsNoted() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("no_ai_training"), "Should mention no_ai_training restriction");
        assertTrue(output.contains("no_cross_border"), "Should mention no_cross_border restriction");
    }

    // --- Audit ---

    @Test
    void outputContainsAuditTraces() throws IOException {
        String output = runSimulator();
        long traceCount = output.lines()
                .filter(line -> line.contains("[AUDIT] trace: 00-"))
                .count();
        assertTrue(traceCount >= 10,
                "Should have at least 10 audit traces, got: " + traceCount);
    }

    @Test
    void outputContainsViolationAuditEntries() throws IOException {
        String output = runSimulator();
        long violationCount = output.lines()
                .filter(line -> line.contains("VIOLATION"))
                .count();
        assertTrue(violationCount >= 4,
                "Should have at least 4 violation entries, got: " + violationCount);
    }

    // --- Summary ---

    @Test
    void summaryShowsCorrectUnitCount() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("Units loaded successfully:  6"),
                "Should show 6 units loaded (2 happy + 2 HITL + 2 delegated): " + extractSummary(output));
    }

    @Test
    void summaryShowsPolicyDenials() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("Access denied (policy):     1"),
                "Should show 1 policy denial (scope elevation): " + extractSummary(output));
    }

    @Test
    void summaryShowsDelegationDenials() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("Access denied (delegation): 2"),
                "Should show 2 delegation denials (depth + no-delegation): " + extractSummary(output));
    }

    @Test
    void summaryShowsComplianceDenials() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("Access denied (compliance): 1"),
                "Should show 1 compliance denial (data residency): " + extractSummary(output));
    }

    @Test
    void summaryShowsHitlApprovals() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("HITL approvals:             2"),
                "Should show 2 HITL approvals: " + extractSummary(output));
    }

    @Test
    void summaryShowsViolationsDetected() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("Violations detected:        4"),
                "Should show 4 violations (depth + scope + no-delegation + compliance): "
                        + extractSummary(output));
    }

    @Test
    void outputContainsAllPhases() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("Phase 1"), "Should contain Phase 1");
        assertTrue(output.contains("Phase 2"), "Should contain Phase 2");
        assertTrue(output.contains("Phase 3"), "Should contain Phase 3");
        assertTrue(output.contains("Phase 4"), "Should contain Phase 4");
        assertTrue(output.contains("Phase 5"), "Should contain Phase 5");
        assertTrue(output.contains("Phase 6"), "Should contain Phase 6");
        assertTrue(output.contains("Phase 7"), "Should contain Phase 7");
        assertTrue(output.contains("SIMULATION SUMMARY"), "Should contain summary");
    }

    // --- Stats accessors ---

    @Test
    void orchestratorStatsAreCorrect() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ConsoleLog log = new ConsoleLog(new PrintStream(baos));
        ComplianceOrchestratorAgent orchestrator = new ComplianceOrchestratorAgent(log, true);
        orchestrator.run(agentCardPath(), manifestPath());

        assertEquals(6, orchestrator.unitsLoaded());
        assertEquals(1, orchestrator.accessDeniedPolicy());
        assertEquals(2, orchestrator.accessDeniedDelegation());
        assertEquals(1, orchestrator.accessDeniedCompliance());
        assertEquals(2, orchestrator.humanApprovals());
        assertEquals(4, orchestrator.violationsDetected());
    }

    private String extractSummary(String output) {
        int idx = output.indexOf("SIMULATION SUMMARY");
        return idx >= 0 ? output.substring(idx) : "(summary not found)";
    }
}
