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
 * Unit tests for AccessDecisionEngine. No file I/O -- all test data is programmatic.
 */
class AccessDecisionEngineTest {

    private SimulatedOAuth2 oauth2;
    private SimulatedToken validToken;

    @BeforeEach
    void setUp() {
        oauth2 = new SimulatedOAuth2();
        validToken = oauth2.issueToken("test-client",
                Set.of("read:guidelines", "read:protocols", "read:cohort"));
    }

    // --- Helpers ---

    private KnowledgeUnit unit(String id, String access, String sensitivity, String authScope) {
        return new KnowledgeUnit(
                id, "path/" + id + ".md", "reference", "test intent", "markdown",
                null, "en", "global", List.of("agent"), null, LocalDate.now(),
                null, null, List.of(), null, List.of(), null,
                access, authScope, sensitivity, null, null
        );
    }

    // --- Case 1: Public unit grants without credential ---

    @Test
    void publicUnit_grantsWithoutToken() {
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit pub = unit("public-guidelines", "public", "public", null);

        AccessDecision decision = engine.evaluate(pub, null);

        assertInstanceOf(AccessDecision.Granted.class, decision);
        assertEquals("public-guidelines", ((AccessDecision.Granted) decision).unitId());
    }

    @Test
    void publicUnit_grantsWithToken() {
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit pub = unit("public-guidelines", "public", "public", null);

        AccessDecision decision = engine.evaluate(pub, validToken.tokenValue());

        assertInstanceOf(AccessDecision.Granted.class, decision);
    }

    // --- Case 2: Authenticated unit requires valid token ---

    @Test
    void authenticatedUnit_deniedWithoutToken() {
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit auth = unit("trial-protocols", "authenticated", "internal", null);

        AccessDecision decision = engine.evaluate(auth, null);

        assertInstanceOf(AccessDecision.Denied.class, decision);
        assertTrue(((AccessDecision.Denied) decision).reason().contains("No credential"));
    }

    @Test
    void authenticatedUnit_grantsWithValidToken() {
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit auth = unit("trial-protocols", "authenticated", "internal", null);

        AccessDecision decision = engine.evaluate(auth, validToken.tokenValue());

        assertInstanceOf(AccessDecision.Granted.class, decision);
    }

    // --- Case 3: Restricted unit with valid token but no HITL ---

    @Test
    void restrictedUnit_grantsWhenNoHitlRequired() {
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit restricted = unit("patient-cohort", "restricted", "restricted", "clinical-staff");

        AccessDecision decision = engine.evaluate(restricted, validToken.tokenValue());

        assertInstanceOf(AccessDecision.Granted.class, decision);
    }

    // --- Case 4: Restricted unit with HITL required ---

    @Test
    void restrictedUnit_requiresHumanApproval() {
        DelegationConfig hitlConfig = new DelegationConfig(1, true, true, true, "oauth_consent");
        var engine = new AccessDecisionEngine(oauth2, Map.of("patient-cohort", hitlConfig));
        KnowledgeUnit restricted = unit("patient-cohort", "restricted", "restricted", "clinical-staff");

        AccessDecision decision = engine.evaluate(restricted, validToken.tokenValue());

        assertInstanceOf(AccessDecision.RequiresHumanApproval.class, decision);
        var hitl = (AccessDecision.RequiresHumanApproval) decision;
        assertEquals("patient-cohort", hitl.unitId());
        assertEquals("restricted", hitl.sensitivity());
        assertEquals("oauth_consent", hitl.approvalMechanism());
    }

    // --- Case 5: Restricted unit denied with missing scope ---

    @Test
    void restrictedUnit_deniedWithMissingScope() {
        SimulatedToken limitedToken = oauth2.issueToken("test-client", Set.of("read:guidelines"));
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit restricted = unit("patient-cohort", "restricted", "restricted", "clinical-staff");

        AccessDecision decision = engine.evaluate(restricted, limitedToken.tokenValue());

        assertInstanceOf(AccessDecision.Denied.class, decision);
        assertTrue(((AccessDecision.Denied) decision).reason().contains("missing scope"));
    }

    // --- Case 6: Unknown access level ---

    @Test
    void unknownAccessLevel_denied() {
        var engine = new AccessDecisionEngine(oauth2, Map.of());
        KnowledgeUnit weird = unit("weird-unit", "top-secret", "classified", null);

        AccessDecision decision = engine.evaluate(weird, validToken.tokenValue());

        assertInstanceOf(AccessDecision.Denied.class, decision);
        assertTrue(((AccessDecision.Denied) decision).reason().contains("Unknown access level"));
    }

    // --- Scope derivation ---

    @Test
    void scopeFromId_extractsLastSegment() {
        assertEquals("read:guidelines", AccessDecisionEngine.scopeFromId("public-guidelines"));
        assertEquals("read:protocols", AccessDecisionEngine.scopeFromId("trial-protocols"));
        assertEquals("read:cohort", AccessDecisionEngine.scopeFromId("patient-cohort"));
    }

    @Test
    void scopeFromId_handlesNoHyphen() {
        assertEquals("read:guidelines", AccessDecisionEngine.scopeFromId("guidelines"));
    }

    @Test
    void scopeFromId_handlesNull() {
        assertEquals("read:unknown", AccessDecisionEngine.scopeFromId(null));
    }
}
