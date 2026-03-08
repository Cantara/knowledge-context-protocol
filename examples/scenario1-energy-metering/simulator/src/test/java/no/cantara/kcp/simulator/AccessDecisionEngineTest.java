package no.cantara.kcp.simulator;

import no.cantara.kcp.model.KnowledgeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AccessDecisionEngine in the energy metering context.
 */
class AccessDecisionEngineTest {

    private SimulatedOAuth2 oauth2;
    private SimulatedToken validToken;

    @BeforeEach
    void setUp() {
        oauth2 = new SimulatedOAuth2();
        validToken = oauth2.issueToken("test-client",
                Set.of("read:tariff", "read:meter", "read:billing", "grid-engineer"));
    }

    private KnowledgeUnit unit(String id, String access, String sensitivity, String authScope) {
        return new KnowledgeUnit(
                id, "path/" + id + ".md", "reference", "test intent", "markdown",
                null, "en", "global", List.of("agent"), null, LocalDate.now(),
                null, null, List.of(), null, List.of(), null,
                access, authScope, sensitivity, null, null
        );
    }

    // --- Public unit: tariff-schedule ---

    @Test
    void publicUnit_grantsWithoutToken() {
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit pub = unit("tariff-schedule", "public", "public", null);

        AccessDecision decision = engine.evaluate(pub, null);

        assertInstanceOf(AccessDecision.Granted.class, decision);
        assertEquals("tariff-schedule", ((AccessDecision.Granted) decision).unitId());
    }

    @Test
    void publicUnit_grantsWithToken() {
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit pub = unit("tariff-schedule", "public", "public", null);

        AccessDecision decision = engine.evaluate(pub, validToken.tokenValue());

        assertInstanceOf(AccessDecision.Granted.class, decision);
    }

    // --- Authenticated unit: meter-readings ---

    @Test
    void authenticatedUnit_deniedWithoutToken() {
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit auth = unit("meter-readings", "authenticated", "internal", "read:meter");

        AccessDecision decision = engine.evaluate(auth, null);

        assertInstanceOf(AccessDecision.Denied.class, decision);
        assertTrue(((AccessDecision.Denied) decision).reason().contains("No credential"));
    }

    @Test
    void authenticatedUnit_grantsWithValidScope() {
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit auth = unit("meter-readings", "authenticated", "internal", "read:meter");

        AccessDecision decision = engine.evaluate(auth, validToken.tokenValue());

        assertInstanceOf(AccessDecision.Granted.class, decision);
    }

    @Test
    void authenticatedUnit_deniedWithWrongScope() {
        SimulatedToken limitedToken = oauth2.issueToken("test-client", Set.of("read:tariff"));
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit auth = unit("meter-readings", "authenticated", "internal", "read:meter");

        AccessDecision decision = engine.evaluate(auth, limitedToken.tokenValue());

        assertInstanceOf(AccessDecision.Denied.class, decision);
        assertTrue(((AccessDecision.Denied) decision).reason().contains("missing scope"));
    }

    // --- Authenticated unit: billing-history ---

    @Test
    void billingHistory_grantsWithBillingScope() {
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit billing = unit("billing-history", "authenticated", "internal", "read:billing");

        AccessDecision decision = engine.evaluate(billing, validToken.tokenValue());

        assertInstanceOf(AccessDecision.Granted.class, decision);
    }

    @Test
    void billingHistory_deniedWithMeterOnlyScope() {
        SimulatedToken meterOnly = oauth2.issueToken("test-client", Set.of("read:meter"));
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit billing = unit("billing-history", "authenticated", "internal", "read:billing");

        AccessDecision decision = engine.evaluate(billing, meterOnly.tokenValue());

        assertInstanceOf(AccessDecision.Denied.class, decision);
    }

    // --- Restricted unit: smart-meter-raw ---

    @Test
    void restrictedUnit_requiresHumanApproval() {
        DelegationConfig hitlConfig = new DelegationConfig(1, true, true, true, "oauth_consent");
        var engine = new AccessDecisionEngine(oauth2, Map.of("smart-meter-raw", hitlConfig));
        KnowledgeUnit restricted = unit("smart-meter-raw", "restricted", "restricted", "grid-engineer");

        AccessDecision decision = engine.evaluate(restricted, validToken.tokenValue());

        assertInstanceOf(AccessDecision.RequiresHumanApproval.class, decision);
        var hitl = (AccessDecision.RequiresHumanApproval) decision;
        assertEquals("smart-meter-raw", hitl.unitId());
        assertEquals("restricted", hitl.sensitivity());
        assertEquals("oauth_consent", hitl.approvalMechanism());
    }

    @Test
    void restrictedUnit_deniedWithoutToken() {
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit restricted = unit("smart-meter-raw", "restricted", "restricted", "grid-engineer");

        AccessDecision decision = engine.evaluate(restricted, null);

        assertInstanceOf(AccessDecision.Denied.class, decision);
    }

    @Test
    void restrictedUnit_deniedWithWrongScope() {
        SimulatedToken noEngineer = oauth2.issueToken("test-client", Set.of("read:meter", "read:billing"));
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit restricted = unit("smart-meter-raw", "restricted", "restricted", "grid-engineer");

        AccessDecision decision = engine.evaluate(restricted, noEngineer.tokenValue());

        assertInstanceOf(AccessDecision.Denied.class, decision);
        assertTrue(((AccessDecision.Denied) decision).reason().contains("grid-engineer"));
    }

    @Test
    void restrictedUnit_grantsWithoutHitlConfig() {
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit restricted = unit("smart-meter-raw", "restricted", "restricted", "grid-engineer");

        AccessDecision decision = engine.evaluate(restricted, validToken.tokenValue());

        // No HITL config in map -> grants directly
        assertInstanceOf(AccessDecision.Granted.class, decision);
    }

    // --- Scope derivation ---

    @Test
    void scopeForUnit_usesAuthScopeWhenPresent() {
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit withScope = unit("meter-readings", "authenticated", "internal", "read:meter");

        assertEquals("read:meter", engine.scopeForUnit(withScope));
    }

    @Test
    void scopeForUnit_derivesFromIdWhenNoAuthScope() {
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit noScope = unit("tariff-schedule", "public", "public", null);

        assertEquals("read:schedule", engine.scopeForUnit(noScope));
    }

    @Test
    void unknownAccessLevel_denied() {
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit weird = unit("weird-unit", "top-secret", "classified", null);

        AccessDecision decision = engine.evaluate(weird, validToken.tokenValue());

        assertInstanceOf(AccessDecision.Denied.class, decision);
        assertTrue(((AccessDecision.Denied) decision).reason().contains("Unknown access level"));
    }
}
