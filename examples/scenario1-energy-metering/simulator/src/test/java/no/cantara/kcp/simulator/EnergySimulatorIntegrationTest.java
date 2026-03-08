package no.cantara.kcp.simulator;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: runs the full energy metering simulation with --auto-approve
 * and verifies console output structure and correctness.
 */
class EnergySimulatorIntegrationTest {

    private Path agentCardPath() {
        Path relative = Path.of("../agent-card.json");
        if (relative.toFile().exists()) return relative;
        return Path.of("/src/cantara/knowledge-context-protocol/examples/scenario1-energy-metering/agent-card.json");
    }

    private Path manifestPath() {
        Path relative = Path.of("../knowledge.yaml");
        if (relative.toFile().exists()) return relative;
        return Path.of("/src/cantara/knowledge-context-protocol/examples/scenario1-energy-metering/knowledge.yaml");
    }

    private String runSimulator() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(baos);
        ConsoleLog log = new ConsoleLog(capture);

        GridOrchestratorAgent orchestrator = new GridOrchestratorAgent(log, true);
        orchestrator.run(agentCardPath(), manifestPath());

        return baos.toString();
    }

    // --- Phase 1: A2A Discovery ---

    @Test
    void outputContainsAgentDiscovery() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("[A2A]  Discovered: \"Energy Metering Agent\""),
                "Should discover Energy Metering Agent via A2A");
    }

    @Test
    void outputContainsSkillDiscovery() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("tariff-lookup"), "Should list tariff-lookup skill");
        assertTrue(output.contains("consumption-analysis"), "Should list consumption-analysis skill");
        assertTrue(output.contains("grid-diagnostics"), "Should list grid-diagnostics skill");
    }

    // --- Phase 2: KCP Discovery ---

    @Test
    void outputContainsAllFourUnits() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("[KCP]  Units loaded:"), "Should contain units loaded header");
        assertTrue(output.contains("tariff-schedule"), "Should list tariff-schedule");
        assertTrue(output.contains("meter-readings"), "Should list meter-readings");
        assertTrue(output.contains("billing-history"), "Should list billing-history");
        assertTrue(output.contains("smart-meter-raw"), "Should list smart-meter-raw");
    }

    @Test
    void outputContainsKcpVersion() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("KCP v0.6"), "Should mention KCP v0.6");
    }

    @Test
    void outputContainsDelegationConfig() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("Delegation: max_depth=2"),
                "Should show root delegation config");
    }

    // --- Phase 3: Authentication ---

    @Test
    void outputContainsTokenIssuance() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("Token issued:"), "Should show token issuance");
        assertTrue(output.contains("expires in 3600s"), "Should show token lifetime");
    }

    // --- Phase 4: Knowledge Access ---

    @Test
    void u1TariffScheduleLoadedAsPublic() throws IOException {
        String output = runSimulator();
        int u1Pos = output.indexOf("[U1]");
        int u2Pos = output.indexOf("[U2]");
        assertTrue(u1Pos >= 0, "Should contain U1 request");
        String u1Block = output.substring(u1Pos, u2Pos);

        assertTrue(u1Block.contains("tariff-schedule"), "U1 should match tariff-schedule");
        assertTrue(u1Block.contains("access: public"), "U1 should show public access");
        assertTrue(u1Block.contains("Result: LOADED"), "U1 should be LOADED");
    }

    @Test
    void u2MeterReadingsLoadedWithAuth() throws IOException {
        String output = runSimulator();
        int u2Pos = output.indexOf("[U2]");
        int u3Pos = output.indexOf("[U3]");
        assertTrue(u2Pos >= 0, "Should contain U2 request");
        String u2Block = output.substring(u2Pos, u3Pos);

        assertTrue(u2Block.contains("meter-readings"), "U2 should match meter-readings");
        assertTrue(u2Block.contains("authenticated"), "U2 should show authenticated access");
        assertTrue(u2Block.contains("Result: LOADED"), "U2 should be LOADED");
    }

    @Test
    void u3BillingHistoryLoadedWithAuth() throws IOException {
        String output = runSimulator();
        int u3Pos = output.indexOf("[U3]");
        int u4Pos = output.indexOf("[U4]");
        assertTrue(u3Pos >= 0, "Should contain U3 request");
        String u3Block = output.substring(u3Pos, u4Pos);

        assertTrue(u3Block.contains("billing-history"), "U3 should match billing-history");
        assertTrue(u3Block.contains("authenticated"), "U3 should show authenticated access");
        assertTrue(u3Block.contains("Result: LOADED"), "U3 should be LOADED");
    }

    @Test
    void u4SmartMeterRawRequiresHitlThenLoaded() throws IOException {
        String output = runSimulator();
        int u4Pos = output.indexOf("[U4]");
        assertTrue(u4Pos >= 0, "Should contain U4 request");
        String u4Block = output.substring(u4Pos);

        assertTrue(u4Block.contains("smart-meter-raw"), "U4 should match smart-meter-raw");
        assertTrue(u4Block.contains("restricted"), "U4 should show restricted access");
        assertTrue(u4Block.contains("Human-in-the-loop: REQUIRED"), "U4 should require HITL");
        assertTrue(u4Block.contains("oauth_consent"), "U4 should show oauth_consent mechanism");
        assertTrue(u4Block.contains("Result: LOADED (after human approval)"),
                "U4 should be LOADED after approval");
    }

    // --- Audit ---

    @Test
    void outputContainsAuditTraces() throws IOException {
        String output = runSimulator();
        long traceCount = output.lines()
                .filter(line -> line.contains("[AUDIT] trace: 00-"))
                .count();
        assertEquals(4, traceCount, "Should have 4 audit traces (one per loaded unit)");
    }

    @Test
    void u4AuditContainsHumanApproval() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("human_approval:"), "Should log human approval identity");
    }

    // --- Summary ---

    @Test
    void summaryContainsCorrectCounts() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("Units loaded successfully:  4"),
                "Should show 4 units loaded");
        assertTrue(output.contains("Access denied:              0"),
                "Should show 0 denied");
        assertTrue(output.contains("HITL approvals:             1"),
                "Should show 1 HITL approval");
        assertTrue(output.contains("Audit entries:              4"),
                "Should show 4 audit entries");
    }

    @Test
    void outputContainsAllPhases() throws IOException {
        String output = runSimulator();
        assertTrue(output.contains("Phase 1: Agent Discovery"), "Should contain Phase 1");
        assertTrue(output.contains("Phase 2: Knowledge Discovery"), "Should contain Phase 2");
        assertTrue(output.contains("Phase 3: Authentication"), "Should contain Phase 3");
        assertTrue(output.contains("Phase 4: Knowledge Access"), "Should contain Phase 4");
        assertTrue(output.contains("SIMULATION SUMMARY"), "Should contain summary");
    }

    // --- Stats accessors ---

    @Test
    void orchestratorStatsAreCorrect() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ConsoleLog log = new ConsoleLog(new PrintStream(baos));
        GridOrchestratorAgent orchestrator = new GridOrchestratorAgent(log, true);
        orchestrator.run(agentCardPath(), manifestPath());

        assertEquals(4, orchestrator.unitsLoaded());
        assertEquals(0, orchestrator.accessDenied());
        assertEquals(1, orchestrator.humanApprovals());
        assertEquals(4, orchestrator.auditEntries());
    }
}
