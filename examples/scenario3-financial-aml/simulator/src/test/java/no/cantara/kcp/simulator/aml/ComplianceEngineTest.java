package no.cantara.kcp.simulator.aml;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ComplianceEngine: data residency checks and restriction queries.
 */
class ComplianceEngineTest {

    private ComplianceEngine engine;

    @BeforeEach
    void setUp() {
        Map<String, ComplianceConfig> configs = Map.of(
                "customer-profiles", new ComplianceConfig(
                        List.of("GDPR", "AML5D"), List.of("EU"), List.of()),
                "raw-wire-transfers", new ComplianceConfig(
                        List.of("GDPR", "AML5D", "NIS2"), List.of("EU"),
                        List.of("no_ai_training", "no_cross_border")),
                "sanctions-lists", ComplianceConfig.EMPTY
        );
        engine = new ComplianceEngine(configs);
    }

    @Test
    void customerProfiles_euRegionCompliant() {
        var decision = engine.checkDataResidency("customer-profiles", "EU");
        assertInstanceOf(ComplianceEngine.ComplianceDecision.Compliant.class, decision);
    }

    @Test
    void customerProfiles_usRegionViolation() {
        var decision = engine.checkDataResidency("customer-profiles", "US");
        assertInstanceOf(ComplianceEngine.ComplianceDecision.Violation.class, decision);
        var violation = (ComplianceEngine.ComplianceDecision.Violation) decision;
        assertTrue(violation.reason().contains("US"));
        assertTrue(violation.reason().contains("[EU]"));
        assertEquals("GDPR", violation.regulation());
    }

    @Test
    void sanctionsList_noResidencyConstraint() {
        var decision = engine.checkDataResidency("sanctions-lists", "US");
        assertInstanceOf(ComplianceEngine.ComplianceDecision.Compliant.class, decision);
    }

    @Test
    void rawWireTransfers_hasNoAiTrainingRestriction() {
        assertTrue(engine.hasRestriction("raw-wire-transfers", "no_ai_training"));
    }

    @Test
    void rawWireTransfers_hasNoCrossBorderRestriction() {
        assertTrue(engine.hasRestriction("raw-wire-transfers", "no_cross_border"));
    }

    @Test
    void customerProfiles_noRestrictions() {
        assertFalse(engine.hasRestriction("customer-profiles", "no_ai_training"));
    }

    @Test
    void unknownUnit_compliant() {
        var decision = engine.checkDataResidency("unknown-unit", "US");
        assertInstanceOf(ComplianceEngine.ComplianceDecision.Compliant.class, decision);
    }

    @Test
    void unknownUnit_noRestrictions() {
        assertFalse(engine.hasRestriction("unknown-unit", "no_ai_training"));
    }

    @Test
    void rawWireTransfers_configHasAllRegulations() {
        ComplianceConfig config = engine.getConfig("raw-wire-transfers");
        assertEquals(3, config.regulations().size());
        assertTrue(config.hasRegulation("GDPR"));
        assertTrue(config.hasRegulation("AML5D"));
        assertTrue(config.hasRegulation("NIS2"));
    }
}
