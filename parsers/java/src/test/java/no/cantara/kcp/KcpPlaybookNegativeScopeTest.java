package no.cantara.kcp;

import no.cantara.kcp.model.ActionScope;
import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §4.3b playbook-level prohibitions (v0.32, RFC-0030).
 *
 * <p>Mirrors cli/src/playbook-negative-scope.test.ts and
 * parsers/python/tests/test_playbook_negative_scope.py, so the three stay in
 * cross-language parity. A {@code kind: playbook} unit's {@code action_scope.deny} is a
 * blanket prohibition over EVERY step — the one normative sub-object of the otherwise
 * declarative playbook {@code action_scope} envelope. The effective denylist for a step
 * is the UNION of the playbook's deny and the used skill's deny: a match in either
 * denies, overriding any allow, fail-closed. Union is the only sound composition —
 * adding a source can only refuse more (the scope-axis mirror of the §3.13 lowest-of).
 */
class KcpPlaybookNegativeScopeTest {

    private static Map<String, Object> sletteagent() {
        Map<String, Object> u = new HashMap<>();
        u.put("id", "sletteagent");
        u.put("kind", "skill");
        u.put("path", "skills/sletteagent.md");
        u.put("intent", "How do I delete customer data compliantly?");
        u.put("scope", "project");
        u.put("audience", List.of("agent"));
        u.put("load_eligible", true);
        Map<String, Object> scope = new HashMap<>();
        scope.put("tools", List.of("delete", "read"));
        scope.put("paths", List.of("customers/**", "legal/hold/2025/**"));
        scope.put("deny", Map.of("tools", List.of("transfer_ownership")));
        u.put("action_scope", scope);
        return u;
    }

    private static Map<String, Object> basePlaybook() {
        Map<String, Object> u = new HashMap<>();
        u.put("id", "pb-002-gdpr-sletting");
        u.put("kind", "playbook");
        u.put("path", "playbooks/gdpr-sletting.md");
        u.put("intent", "How is a GDPR Art.17 deletion request executed?");
        u.put("scope", "project");
        u.put("audience", List.of("agent"));
        u.put("load_eligible", true);
        u.put("authority_level", "commit");
        u.put("steps", List.of(Map.of("id", "slett", "uses", "sletteagent", "authority_level", "commit")));
        return u;
    }

    private static KnowledgeManifest manifest(List<Map<String, Object>> units) {
        Map<String, Object> m = new HashMap<>();
        m.put("project", "example");
        m.put("version", "1.0.0");
        m.put("kcp_version", "0.32");
        m.put("authority_level_scale", List.of("observe", "explain", "suggest", "prepare", "commit"));
        m.put("units", units);
        return KcpParser.fromMap(m);
    }

    private static KnowledgeUnit unit(KnowledgeManifest m, String id) {
        return m.units().stream().filter(u -> id.equals(u.id())).findFirst().orElseThrow();
    }

    @Test
    void roundTripsDenyOnAPlaybook() {
        Map<String, Object> pb = basePlaybook();
        Map<String, Object> scope = new HashMap<>();
        scope.put("deny", Map.of(
                "paths", List.of("legal/hold/**"),
                "tools", List.of("transfer_ownership")));
        pb.put("action_scope", scope);

        KnowledgeManifest m = manifest(List.of(sletteagent(), pb));
        ActionScope pbScope = unit(m, "pb-002-gdpr-sletting").actionScope();
        assertNotNull(pbScope.deny());
        assertEquals(List.of("legal/hold/**"), pbScope.deny().paths());
        assertEquals(List.of("transfer_ownership"), pbScope.deny().tools());
    }

    @Test
    void effectiveDenyIsTheUnion() {
        Map<String, Object> pb = basePlaybook();
        Map<String, Object> scope = new HashMap<>();
        scope.put("deny", Map.of(
                "paths", List.of("legal/hold/**"),
                "tools", List.of("set_billing")));
        pb.put("action_scope", scope);

        KnowledgeManifest m = manifest(List.of(sletteagent(), pb));
        ActionScope pbScope = unit(m, "pb-002-gdpr-sletting").actionScope();
        ActionScope skillScope = unit(m, "sletteagent").actionScope();
        List<ActionScope> scopes = Arrays.asList(pbScope, skillScope);

        // playbook-only match
        assertTrue(KcpValidator.effectiveDeniesToken(scopes, "paths", "legal/hold/**"));
        assertFalse(KcpValidator.deniesToken(skillScope, "paths", "legal/hold/**"));

        // skill-only match
        assertTrue(KcpValidator.effectiveDeniesToken(scopes, "tools", "transfer_ownership"));
        assertFalse(KcpValidator.deniesToken(pbScope, "tools", "transfer_ownership"));

        // neither — allowed tokens pass through
        assertFalse(KcpValidator.effectiveDeniesToken(scopes, "tools", "read"));
    }

    @Test
    void addingADenySourceNeverUnDenies() {
        KnowledgeManifest m = manifest(List.of(sletteagent(), basePlaybook()));
        ActionScope skillScope = unit(m, "sletteagent").actionScope();
        assertTrue(KcpValidator.deniesToken(skillScope, "tools", "transfer_ownership"));
        // a playbook that denies nothing cannot relax the skill's deny
        assertTrue(KcpValidator.effectiveDeniesToken(
                Arrays.asList(null, skillScope), "tools", "transfer_ownership"));
    }

    @Test
    void warnsWhenAStepIsSelfNullified() {
        Map<String, Object> pb = basePlaybook();
        Map<String, Object> scope = new HashMap<>();
        scope.put("deny", Map.of("tools", List.of("delete", "read"))); // everything the skill allows
        pb.put("action_scope", scope);

        var result = KcpValidator.validate(manifest(List.of(sletteagent(), pb)), null);
        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.contains("self-nullified") && w.contains("'tools'")));
        // paths dimension is not fully denied — no warning there
        assertFalse(result.warnings().stream()
                .anyMatch(w -> w.contains("self-nullified") && w.contains("'paths'")));
    }

    @Test
    void doesNotWarnWhenTheDenyOnlyCarvesAHole() {
        Map<String, Object> pb = basePlaybook();
        Map<String, Object> scope = new HashMap<>();
        scope.put("deny", Map.of("paths", List.of("legal/hold/**")));
        pb.put("action_scope", scope);

        var result = KcpValidator.validate(manifest(List.of(sletteagent(), pb)), null);
        assertFalse(result.warnings().stream().anyMatch(w -> w.contains("self-nullified")));
    }

    @Test
    void skillDenyAloneCanSelfNullifyAStep() {
        Map<String, Object> skill = sletteagent();
        Map<String, Object> scope = new HashMap<>();
        scope.put("tools", List.of("transfer_ownership"));
        scope.put("deny", Map.of("tools", List.of("transfer_ownership")));
        skill.put("action_scope", scope);

        var result = KcpValidator.validate(manifest(List.of(skill, basePlaybook())), null);
        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.contains("self-nullified") && w.contains("'tools'")));
    }

    @Test
    void emptyDenyOnAPlaybookDrawsTheProhibitsNothingLint() {
        Map<String, Object> pb = basePlaybook();
        Map<String, Object> scope = new HashMap<>();
        scope.put("deny", Map.of());
        pb.put("action_scope", scope);

        var result = KcpValidator.validate(manifest(List.of(sletteagent(), pb)), null);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("prohibits nothing")));
    }
}
