package no.cantara.kcp.simulator.legal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: runs the full legal delegation simulation with --auto-approve
 * and verifies console output for all delegation behaviours.
 */
class LegalSimulatorIntegrationTest {

    private Path agentCardPath() {
        Path relative = Path.of("../agent-card.json");
        if (relative.toFile().exists()) return relative;
        return Path.of("/src/cantara/knowledge-context-protocol/examples/scenario2-legal-delegation/agent-card.json");
    }

    private Path manifestPath() {
        Path relative = Path.of("../knowledge.yaml");
        if (relative.toFile().exists()) return relative;
        return Path.of("/src/cantara/knowledge-context-protocol/examples/scenario2-legal-delegation/knowledge.yaml");
    }

    private String runSimulator() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(baos);
        ConsoleLog log = new ConsoleLog(capture);

        CaseOrchestratorAgent orchestrator = new CaseOrchestratorAgent(log, true);
        orchestrator.run(agentCardPath(), manifestPath());

        return baos.toString();
    }

    // --- Setup / Discovery ---

    @Test
    void outputContainsAgentDiscovery() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("Discovered: \"Legal Research Agent\""),
                "Should discover Legal Research Agent");
    }

    @Test
    void outputContainsAllFourUnits() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("public-precedents"), "Should list public-precedents");
        assertTrue(output.contains("case-briefs"), "Should list case-briefs");
        assertTrue(output.contains("client-communications"), "Should list client-communications");
        assertTrue(output.contains("sealed-records"), "Should list sealed-records");
    }

    @Test
    void outputShowsMaxDepthPerUnit() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("max_depth=0"), "Should show max_depth=0 for sealed-records");
        assertTrue(output.contains("max_depth=2"), "Should show max_depth=2 for client-communications");
    }

    // --- Phase 1: Direct access ---

    @Test
    void phase1_publicPrecedentsLoaded() throws IOException {
        String output = runSimulator();
        int pos1a = output.indexOf("[1a]");
        int pos1b = output.indexOf("[1b]");
        assertTrue(pos1a >= 0 && pos1b > pos1a, "Should have 1a and 1b in order");
        String block1a = output.substring(pos1a, pos1b);
        assertTrue(block1a.contains("public-precedents"), "1a should match public-precedents");
        assertTrue(block1a.contains("Result: LOADED"), "1a should be LOADED");
    }

    @Test
    void phase1_caseBriefsLoaded() throws IOException {
        String output = runSimulator();
        int pos1b = output.indexOf("[1b]");
        int pos1c = output.indexOf("[1c]");
        assertTrue(pos1b >= 0 && pos1c > pos1b, "Should have 1b and 1c in order");
        String block1b = output.substring(pos1b, pos1c);
        assertTrue(block1b.contains("case-briefs"), "1b should match case-briefs");
        assertTrue(block1b.contains("Result: LOADED"), "1b should be LOADED");
    }

    @Test
    void phase1_sealedRecordsDenied_maxDepthZero() throws IOException {
        String output = runSimulator();
        int pos1c = output.indexOf("[1c]");
        assertTrue(pos1c >= 0, "Should have 1c");
        // Find next section or end
        int nextSection = output.indexOf("Phase 2", pos1c);
        String block1c = output.substring(pos1c, nextSection > 0 ? nextSection : output.length());
        assertTrue(block1c.contains("sealed-records"), "1c should reference sealed-records");
        assertTrue(block1c.contains("max_depth=0"), "1c should mention max_depth=0");
        assertTrue(block1c.contains("DENIED") || block1c.contains("VIOLATION"),
                "1c should be DENIED or show VIOLATION");
    }

    // --- Phase 2: Delegation chain ---

    @Test
    void phase2_publicPrecedentsDelegated() throws IOException {
        String output = runSimulator();
        int pos2a = output.indexOf("[2a]");
        assertTrue(pos2a >= 0, "Should have 2a delegation");
        int pos2b = output.indexOf("[2b]", pos2a);
        String block2a = output.substring(pos2a, pos2b);
        assertTrue(block2a.contains("DELEGATED"), "2a should be DELEGATED (public)");
    }

    @Test
    void phase2_caseBriefs_attenuationBlocked() throws IOException {
        String output = runSimulator();
        int pos2b = output.indexOf("[2b]");
        int pos2bfix = output.indexOf("[2b-fix]");
        assertTrue(pos2b >= 0 && pos2bfix > pos2b, "Should have 2b and 2b-fix");
        String block2b = output.substring(pos2b, pos2bfix);
        assertTrue(block2b.contains("BLOCKED"), "2b should be BLOCKED (attenuation)");
        assertTrue(block2b.contains("attenuation") || block2b.contains("ATTENUATION"),
                "2b should mention attenuation");
    }

    @Test
    void phase2_caseBriefs_narrowedScopeAllowed() throws IOException {
        String output = runSimulator();
        int pos2bfix = output.indexOf("[2b-fix]");
        int pos2c = output.indexOf("[2c]");
        assertTrue(pos2bfix >= 0 && pos2c > pos2bfix, "Should have 2b-fix and 2c");
        String block = output.substring(pos2bfix, pos2c);
        assertTrue(block.contains("read:case:external-summary"),
                "2b-fix should show narrowed scope");
        assertTrue(block.contains("DELEGATED"), "2b-fix should be DELEGATED");
    }

    @Test
    void phase2_clientComms_delegatedWithHitl() throws IOException {
        String output = runSimulator();
        int pos2c = output.indexOf("[2c]");
        int pos2d = output.indexOf("[2d]");
        assertTrue(pos2c >= 0 && pos2d > pos2c, "Should have 2c and 2d");
        String block = output.substring(pos2c, pos2d);
        assertTrue(block.contains("client-communications"), "2c should reference client-communications");
        assertTrue(block.contains("Human-in-the-loop: REQUIRED"), "2c should require HITL");
        assertTrue(block.contains("DELEGATED"), "2c should be DELEGATED (after approval)");
    }

    @Test
    void phase2_clientComms_depth3Blocked() throws IOException {
        String output = runSimulator();
        int pos2d = output.indexOf("[2d]");
        assertTrue(pos2d >= 0, "Should have 2d");
        String block = output.substring(pos2d);
        assertTrue(block.contains("BLOCKED"), "2d should be BLOCKED");
        assertTrue(block.contains("exceeds max_depth=2") || block.contains("DEPTH VIOLATION"),
                "2d should mention depth exceeded");
    }

    // --- Audit ---

    @Test
    void outputContainsAuditTraces() throws IOException {
        String output = runSimulator();
        long traceCount = output.lines()
                .filter(line -> line.contains("[AUDIT] trace: 00-"))
                .count();
        assertTrue(traceCount >= 7, "Should have at least 7 audit traces, got: " + traceCount);
    }

    @Test
    void outputContainsAttenuationViolationAudit() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("ATTENUATION VIOLATION"),
                "Should log attenuation violation in audit");
    }

    @Test
    void outputContainsDepthViolationAudit() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("DEPTH VIOLATION"),
                "Should log depth violation in audit");
    }

    // --- Summary ---

    @Test
    void summaryShowsCorrectCounts() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("Units loaded successfully:  5"),
                "Should show 5 units loaded: " + output);
        assertTrue(output.contains("Access denied (policy):     1"),
                "Should show 1 policy denial (sealed-records)");
        assertTrue(output.contains("Access denied (delegation): 2"),
                "Should show 2 delegation blocks (attenuation + depth)");
        assertTrue(output.contains("HITL approvals:             1"),
                "Should show 1 HITL approval");
    }

    @Test
    void outputContainsScenarioHeader() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("SCENARIO 2: Legal Document Review"),
                "Should contain scenario header");
        assertTrue(output.contains("3-Hop Delegation"),
                "Should mention 3-hop delegation");
    }

    // --- Stats accessors ---

    @Test
    void orchestratorStatsAreCorrect() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ConsoleLog log = new ConsoleLog(new PrintStream(baos));
        CaseOrchestratorAgent orchestrator = new CaseOrchestratorAgent(log, true);
        orchestrator.run(agentCardPath(), manifestPath());

        assertEquals(5, orchestrator.unitsLoaded());
        assertEquals(1, orchestrator.accessDenied());
        assertEquals(2, orchestrator.delegationBlocked());
        assertEquals(1, orchestrator.humanApprovals());
    }
}
