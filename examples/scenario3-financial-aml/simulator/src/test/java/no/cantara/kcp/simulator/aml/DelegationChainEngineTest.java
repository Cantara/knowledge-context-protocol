package no.cantara.kcp.simulator.aml;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DelegationChainEngine in AML context.
 */
class DelegationChainEngineTest {

    private DelegationChainEngine engine;

    @BeforeEach
    void setUp() {
        DelegationConfig root = new DelegationConfig(3, true, true, false, null);

        Map<String, DelegationConfig> unitDelegation = Map.of(
                "customer-profiles", new DelegationConfig(2, true, true, true, "oauth_consent"),
                "sar-drafts", new DelegationConfig(1, true, true, true, "manual"),
                "raw-wire-transfers", new DelegationConfig(0, true, true, false, null)
        );

        engine = new DelegationChainEngine(root, unitDelegation);
    }

    // --- raw-wire-transfers: max_depth=0 ---

    @Test
    void rawWires_blockedAtAnyDepth() {
        var decision = engine.checkDelegation("raw-wire-transfers", 1,
                "compliance-officer", "compliance-officer:read");

        assertInstanceOf(DelegationChainEngine.DelegationDecision.Blocked.class, decision);
        assertTrue(((DelegationChainEngine.DelegationDecision.Blocked) decision).reason()
                .contains("max_depth=0"));
    }

    @Test
    void rawWires_notDelegatable() {
        assertFalse(engine.isDelegatable("raw-wire-transfers"));
    }

    // --- customer-profiles: max_depth=2 ---

    @Test
    void customerProfiles_blockedAtDepth3() {
        var decision = engine.checkDelegation("customer-profiles", 3,
                "aml-analyst", "aml-analyst:external");

        assertInstanceOf(DelegationChainEngine.DelegationDecision.Blocked.class, decision);
    }

    @Test
    void customerProfiles_allowedAtDepth2() {
        var decision = engine.checkDelegation("customer-profiles", 2,
                "aml-analyst", "aml-analyst:summary");

        assertInstanceOf(DelegationChainEngine.DelegationDecision.Allowed.class, decision);
    }

    // --- sar-drafts: max_depth=1 ---

    @Test
    void sarDrafts_allowedAtDepth1() {
        var decision = engine.checkDelegation("sar-drafts", 1,
                "compliance-officer", "compliance-officer:read-only");

        assertInstanceOf(DelegationChainEngine.DelegationDecision.Allowed.class, decision);
    }

    @Test
    void sarDrafts_blockedAtDepth2() {
        var decision = engine.checkDelegation("sar-drafts", 2,
                "compliance-officer", "compliance-officer:read-only");

        assertInstanceOf(DelegationChainEngine.DelegationDecision.Blocked.class, decision);
    }

    // --- Capability attenuation ---

    @Test
    void attenuationBlocked_sameScopeDelegated() {
        var decision = engine.checkDelegation("customer-profiles", 1,
                "aml-analyst", "aml-analyst");

        assertInstanceOf(DelegationChainEngine.DelegationDecision.Blocked.class, decision);
    }

    @Test
    void attenuationBlocked_nullNarrowedScope() {
        var decision = engine.checkDelegation("customer-profiles", 1,
                "aml-analyst", null);

        assertInstanceOf(DelegationChainEngine.DelegationDecision.Blocked.class, decision);
    }

    // --- transaction-patterns: uses root delegation (max_depth=3) ---

    @Test
    void transactionPatterns_allowedAtDepth2() {
        var decision = engine.checkDelegation("transaction-patterns", 2,
                "read:transactions", "read:transactions:risk-signals");

        assertInstanceOf(DelegationChainEngine.DelegationDecision.Allowed.class, decision);
    }

    @Test
    void transactionPatterns_blockedAtDepth4() {
        var decision = engine.checkDelegation("transaction-patterns", 4,
                "read:transactions", "read:transactions:minimal");

        assertInstanceOf(DelegationChainEngine.DelegationDecision.Blocked.class, decision);
    }
}
