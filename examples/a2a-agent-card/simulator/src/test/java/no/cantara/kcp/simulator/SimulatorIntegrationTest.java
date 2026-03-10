package no.cantara.kcp.simulator;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: runs the full simulator with --auto-approve and verifies console output.
 */
class SimulatorIntegrationTest {

    private Path agentCardPath() {
        Path relative = Path.of("../agent-card.json");
        if (relative.toFile().exists()) return relative;
        return Path.of("/src/cantara/knowledge-context-protocol/examples/a2a-agent-card/agent-card.json");
    }

    private Path manifestPath() {
        Path relative = Path.of("../knowledge.yaml");
        if (relative.toFile().exists()) return relative;
        return Path.of("/src/cantara/knowledge-context-protocol/examples/a2a-agent-card/knowledge.yaml");
    }

    private String runSimulator() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(baos);
        ConsoleLog log = new ConsoleLog(capture);

        OrchestratorAgent orchestrator = new OrchestratorAgent(log, true); // auto-approve
        orchestrator.run(agentCardPath(), manifestPath());

        return baos.toString();
    }

    @Test
    void outputContainsAgentDiscovery() throws IOException {
        String output = runSimulator();

        assertTrue(output.contains("[A2A]  Discovered: \"Clinical Research Agent\""),
                "Should contain A2A discovery line");
    }

    @Test
    void outputContainsUnitsLoaded() throws IOException {
        String output = runSimulator();

        assertTrue(output.contains("[KCP]  Units loaded:"),
                "Should contain KCP units loaded header");
        assertTrue(output.contains("public-guidelines"),
                "Should list public-guidelines unit");
        assertTrue(output.contains("trial-protocols"),
                "Should list trial-protocols unit");
        assertTrue(output.contains("patient-cohort"),
                "Should list patient-cohort unit");
    }

    @Test
    void q1PublicGuidelinesLoaded() throws IOException {
        String output = runSimulator();

        assertTrue(output.contains("[Q1]   \"What are the research guidelines?\""),
                "Should contain Q1 query");
        assertTrue(output.contains("Matched unit: public-guidelines"),
                "Should match public-guidelines");
        // The Q1 block should show LOADED
        int q1Pos = output.indexOf("[Q1]");
        int q2Pos = output.indexOf("[Q2]");
        String q1Block = output.substring(q1Pos, q2Pos);
        assertTrue(q1Block.contains("Result: LOADED"),
                "Q1 should result in LOADED");
    }

    @Test
    void q2TrialProtocolsLoaded() throws IOException {
        String output = runSimulator();

        assertTrue(output.contains("[Q2]   \"What are the active trial protocols?\""),
                "Should contain Q2 query");
        int q2Pos = output.indexOf("[Q2]");
        int q3Pos = output.indexOf("[Q3]");
        String q2Block = output.substring(q2Pos, q3Pos);
        assertTrue(q2Block.contains("Matched unit: trial-protocols"),
                "Q2 should match trial-protocols");
        assertTrue(q2Block.contains("Result: LOADED"),
                "Q2 should result in LOADED");
    }

    @Test
    void q3PatientCohortRequiresHitlThenLoaded() throws IOException {
        String output = runSimulator();

        assertTrue(output.contains("[Q3]   \"Show the patient cohort demographics\""),
                "Should contain Q3 query");
        int q3Pos = output.indexOf("[Q3]");
        String q3Block = output.substring(q3Pos);
        assertTrue(q3Block.contains("Human-in-the-loop: REQUIRED"),
                "Q3 should require HITL");
        assertTrue(q3Block.contains("Result: LOADED (after human approval)"),
                "Q3 should be LOADED after human approval");
        assertTrue(q3Block.contains("human_approval: researcher@example.com"),
                "Q3 should log human approval");
    }

    @Test
    void summaryContainsCorrectCounts() throws IOException {
        String output = runSimulator();

        assertTrue(output.contains("3 queries | 3 units accessed | 1 human approval"),
                "Summary should contain correct counts: " + output);
    }

    @Test
    void outputContainsAuditTraces() throws IOException {
        String output = runSimulator();

        // Count [AUDIT] trace lines
        long traceCount = output.lines()
                .filter(line -> line.contains("[AUDIT] trace: 00-"))
                .count();
        assertEquals(3, traceCount, "Should have 3 audit trace lines");
    }

    @Test
    void outputContainsAllPhases() throws IOException {
        String output = runSimulator();

        assertTrue(output.contains("Phase 1: Agent Discovery"),
                "Should contain Phase 1");
        assertTrue(output.contains("Phase 2: Knowledge Discovery"),
                "Should contain Phase 2");
        assertTrue(output.contains("Phase 3: Authentication"),
                "Should contain Phase 3");
        assertTrue(output.contains("Phase 4: Knowledge Access"),
                "Should contain Phase 4");
        assertTrue(output.contains("Summary"),
                "Should contain Summary");
    }

    @Test
    void outputContainsKcpVersion() throws IOException {
        String output = runSimulator();

        assertTrue(output.contains("KCP v0.9"),
                "Should mention KCP version");
    }

    @Test
    void outputContainsOAuth2TokenIssuance() throws IOException {
        String output = runSimulator();

        assertTrue(output.contains("Token issued:"),
                "Should contain token issuance");
        assertTrue(output.contains("expires in 3600s"),
                "Should show token lifetime");
    }

    @Test
    void outputContainsDelegationInfo() throws IOException {
        String output = runSimulator();

        assertTrue(output.contains("Delegation: max_depth=2"),
                "Should contain root delegation config");
        assertTrue(output.contains("Delegation: max_depth=1 (unit override)"),
                "Should contain unit delegation override");
    }
}
