package no.cantara.kcp.simulator.aml;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ComplianceConfig parsing and checks.
 */
class ComplianceConfigTest {

    @Test
    void parsesRegulations() {
        Map<String, Object> unitData = Map.of("compliance", Map.of(
                "regulations", List.of("GDPR", "AML5D")));

        ComplianceConfig config = ComplianceConfig.fromUnitYaml(unitData);

        assertEquals(2, config.regulations().size());
        assertTrue(config.hasRegulation("GDPR"));
        assertTrue(config.hasRegulation("AML5D"));
        assertFalse(config.hasRegulation("NIS2"));
    }

    @Test
    void parsesDataResidency() {
        Map<String, Object> unitData = Map.of("compliance", Map.of(
                "data_residency", Map.of("regions", List.of("EU"))));

        ComplianceConfig config = ComplianceConfig.fromUnitYaml(unitData);

        assertEquals(List.of("EU"), config.dataResidencyRegions());
        assertTrue(config.isRegionPermitted("EU"));
        assertFalse(config.isRegionPermitted("US"));
    }

    @Test
    void parsesRestrictions() {
        Map<String, Object> unitData = Map.of("compliance", Map.of(
                "restrictions", List.of("no_ai_training", "no_cross_border")));

        ComplianceConfig config = ComplianceConfig.fromUnitYaml(unitData);

        assertEquals(2, config.restrictions().size());
        assertTrue(config.hasRestriction("no_ai_training"));
        assertTrue(config.hasRestriction("no_cross_border"));
        assertFalse(config.hasRestriction("no_export"));
    }

    @Test
    void emptyComplianceReturnsEmpty() {
        Map<String, Object> unitData = Map.of("id", "test");

        ComplianceConfig config = ComplianceConfig.fromUnitYaml(unitData);

        assertEquals(ComplianceConfig.EMPTY, config);
        assertTrue(config.regulations().isEmpty());
        assertTrue(config.dataResidencyRegions().isEmpty());
        assertTrue(config.restrictions().isEmpty());
    }

    @Test
    void emptyRegionsPermitsAnyRegion() {
        ComplianceConfig config = ComplianceConfig.EMPTY;
        assertTrue(config.isRegionPermitted("US"));
        assertTrue(config.isRegionPermitted("EU"));
        assertTrue(config.isRegionPermitted("anywhere"));
    }

    @Test
    void parsesFullComplianceBlock() {
        Map<String, Object> unitData = Map.of("compliance", Map.of(
                "regulations", List.of("GDPR", "AML5D", "NIS2"),
                "data_residency", Map.of("regions", List.of("EU")),
                "restrictions", List.of("no_ai_training", "no_cross_border")));

        ComplianceConfig config = ComplianceConfig.fromUnitYaml(unitData);

        assertEquals(3, config.regulations().size());
        assertEquals(List.of("EU"), config.dataResidencyRegions());
        assertEquals(2, config.restrictions().size());
    }
}
