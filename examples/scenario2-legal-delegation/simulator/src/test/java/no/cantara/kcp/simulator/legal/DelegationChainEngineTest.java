package no.cantara.kcp.simulator.legal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DelegationChainEngine: depth limits, capability attenuation, no-delegation.
 */
class DelegationChainEngineTest {

    private DelegationConfig rootDelegation;
    private DelegationChainEngine engine;

    @BeforeEach
    void setUp() {
        rootDelegation = new DelegationConfig(3, true, true, false, null);

        Map<String, DelegationConfig> unitDelegation = Map.of(
                "sealed-records", new DelegationConfig(0, true, true, false, null),
                "client-communications", new DelegationConfig(2, true, true, true, "manual"),
                "case-briefs", new DelegationConfig(3, true, true, false, null)
        );

        engine = new DelegationChainEngine(rootDelegation, unitDelegation);
    }

    // --- max_depth=0: absolute no-delegation ---

    @Test
    void sealedRecords_blockedAtDepth1() {
        var decision = engine.checkDelegation("sealed-records", 1, "court-officer", null);

        assertInstanceOf(DelegationChainEngine.DelegationDecision.Blocked.class, decision);
        var blocked = (DelegationChainEngine.DelegationDecision.Blocked) decision;
        assertTrue(blocked.reason().contains("max_depth=0"));
    }

    @Test
    void sealedRecords_blockedAtDepth0() {
        // Even depth=0 is technically a delegation check; max_depth=0 means "no delegation"
        // but the owner (depth=0) doesn't delegate -- this tests the boundary
        var decision = engine.checkDelegation("sealed-records", 0, "court-officer", "court-officer:read");

        // depth=0 but max_depth=0 -> blocked because max_depth=0 means NO delegation at all
        assertInstanceOf(DelegationChainEngine.DelegationDecision.Blocked.class, decision);
    }

    @Test
    void sealedRecords_isDelegatableFalse() {
        assertFalse(engine.isDelegatable("sealed-records"));
    }

    @Test
    void sealedRecords_maxDepthIsZero() {
        assertEquals(0, engine.maxDepthFor("sealed-records"));
    }

    // --- Depth exceeded ---

    @Test
    void clientComms_blockedAtDepth3() {
        var decision = engine.checkDelegation("client-communications", 3,
                "attorney", "attorney:summary-only");

        assertInstanceOf(DelegationChainEngine.DelegationDecision.Blocked.class, decision);
        var blocked = (DelegationChainEngine.DelegationDecision.Blocked) decision;
        assertTrue(blocked.reason().contains("exceeds max_depth=2"));
    }

    @Test
    void clientComms_allowedAtDepth2() {
        var decision = engine.checkDelegation("client-communications", 2,
                "attorney", "attorney:read-only");

        assertInstanceOf(DelegationChainEngine.DelegationDecision.Allowed.class, decision);
        var allowed = (DelegationChainEngine.DelegationDecision.Allowed) decision;
        assertEquals(2, allowed.depth());
        assertEquals("attorney:read-only", allowed.effectiveScope());
    }

    @Test
    void clientComms_allowedAtDepth1() {
        var decision = engine.checkDelegation("client-communications", 1,
                "attorney", "attorney:limited");

        assertInstanceOf(DelegationChainEngine.DelegationDecision.Allowed.class, decision);
    }

    // --- Capability attenuation ---

    @Test
    void caseBriefs_blockedWhenSameScopeDelegated() {
        var decision = engine.checkDelegation("case-briefs", 2,
                "read:case", "read:case");

        assertInstanceOf(DelegationChainEngine.DelegationDecision.Blocked.class, decision);
        var blocked = (DelegationChainEngine.DelegationDecision.Blocked) decision;
        assertTrue(blocked.reason().contains("attenuation"));
    }

    @Test
    void caseBriefs_blockedWhenNullNarrowedScope() {
        var decision = engine.checkDelegation("case-briefs", 1,
                "read:case", null);

        assertInstanceOf(DelegationChainEngine.DelegationDecision.Blocked.class, decision);
    }

    @Test
    void caseBriefs_allowedWithNarrowedScope() {
        var decision = engine.checkDelegation("case-briefs", 2,
                "read:case", "read:case:external-summary");

        assertInstanceOf(DelegationChainEngine.DelegationDecision.Allowed.class, decision);
        var allowed = (DelegationChainEngine.DelegationDecision.Allowed) decision;
        assertEquals("read:case:external-summary", allowed.effectiveScope());
    }

    // --- Root delegation fallback ---

    @Test
    void unknownUnit_usesRootDelegation() {
        var decision = engine.checkDelegation("unknown-unit", 2,
                "some:scope", "some:scope:narrowed");

        assertInstanceOf(DelegationChainEngine.DelegationDecision.Allowed.class, decision);
    }

    @Test
    void unknownUnit_blockedAtExcessiveDepth() {
        var decision = engine.checkDelegation("unknown-unit", 4,
                "some:scope", "some:scope:narrowed");

        assertInstanceOf(DelegationChainEngine.DelegationDecision.Blocked.class, decision);
        var blocked = (DelegationChainEngine.DelegationDecision.Blocked) decision;
        assertTrue(blocked.reason().contains("exceeds max_depth=3"));
    }

    // --- isDelegatable ---

    @Test
    void clientComms_isDelegatable() {
        assertTrue(engine.isDelegatable("client-communications"));
    }

    @Test
    void caseBriefs_isDelegatable() {
        assertTrue(engine.isDelegatable("case-briefs"));
    }
}
