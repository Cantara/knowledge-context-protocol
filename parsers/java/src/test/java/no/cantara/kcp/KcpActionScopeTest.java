package no.cantara.kcp;

import no.cantara.kcp.model.KnowledgeUnit;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * action_scope parsing — §4.3a (v0.26.1), the envelope that gives {@code kind: skill}
 * its meaning.
 *
 * <p>The Java model carried v0.27 in full (authorityLevel, GrantCeiling, TaskType, Agent)
 * but never gained {@code action_scope} from v0.26.1. A unit parsed without it is
 * indistinguishable from one that declares none — and per §4.3a those mean opposite
 * things: an absent envelope authorizes nothing.
 *
 * <p>Mirrors parsers/python/tests/test_action_scope.py and shared/src/parser.ts
 * {@code parseActionScope}, so the three stay in cross-language parity.
 */
class KcpActionScopeTest {

    private static KnowledgeUnit unitWith(Object actionScope) {
        Map<String, Object> unit = new HashMap<>();
        unit.put("id", "rotate-key");
        unit.put("path", "skills/rotate-key.md");
        unit.put("intent", "How do I rotate the signing key?");
        unit.put("scope", "project");
        unit.put("audience", List.of("agent"));
        unit.put("kind", "skill");
        if (actionScope != null) unit.put("action_scope", actionScope);

        Map<String, Object> manifest = new HashMap<>();
        manifest.put("project", "example");
        manifest.put("version", "1.0.0");
        manifest.put("kcp_version", "0.28");
        manifest.put("units", List.of(unit));
        return KcpParser.fromMap(manifest).units().get(0);
    }

    @Test
    void parsesToolsPathsCapabilities() {
        var u = unitWith(Map.of(
                "tools", List.of("kcp-sign", "git"),
                "paths", List.of("schema/**", ".well-known/kcp-signing-key"),
                "capabilities", List.of("key-management")));
        assertNotNull(u.actionScope());
        assertEquals(List.of("kcp-sign", "git"), u.actionScope().tools());
        assertEquals(List.of("schema/**", ".well-known/kcp-signing-key"), u.actionScope().paths());
        assertEquals(List.of("key-management"), u.actionScope().capabilities());
    }

    @Test
    void parsesSpend() {
        // §4.3a.1 — the money corner. Governs the buy decision; a runtime wallet settles.
        var u = unitWith(Map.of(
                "tools", List.of("http"),
                "spend", Map.of(
                        "max_spend", 2.0,
                        "currency", "USD",
                        "allowed_vendors", List.of("registry.example.com"))));
        assertNotNull(u.actionScope().spend());
        assertEquals(2.0, u.actionScope().spend().maxSpend());
        assertEquals("USD", u.actionScope().spend().currency());
        assertEquals(List.of("registry.example.com"), u.actionScope().spend().allowedVendors());
    }

    @Test
    void integerMaxSpendIsAccepted() {
        // YAML yields an Integer for `max_spend: 2`; the field is a Double. Widening here
        // rather than at every call site keeps `2` and `2.0` equivalent, as a reader expects.
        var u = unitWith(Map.of("spend", Map.of("max_spend", 2, "currency", "USD")));
        assertEquals(2.0, u.actionScope().spend().maxSpend());
    }

    @Test
    void absentActionScopeIsNullNotEmpty() {
        // An empty instance would read as "declares a scope permitting nothing", a
        // different statement from "declares no scope". Keep them distinguishable.
        assertNull(unitWith(null).actionScope());
    }

    @Test
    void partialDeclarationLeavesOtherFieldsNull() {
        var u = unitWith(Map.of("tools", List.of("bash")));
        assertEquals(List.of("bash"), u.actionScope().tools());
        assertNull(u.actionScope().paths());
        assertNull(u.actionScope().capabilities());
        assertNull(u.actionScope().spend());
    }

    @Test
    void nonObjectActionScopeIsIgnored() {
        // A malformed envelope must not fail the whole parse; null correctly reads as
        // "declares nothing", which authorizes nothing.
        assertNull(unitWith("tools").actionScope());
        assertNull(unitWith(List.of("tools")).actionScope());
        assertNull(unitWith(42).actionScope());
    }
}
