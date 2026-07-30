package no.cantara.kcp;

import no.cantara.kcp.model.ActionScope;
import no.cantara.kcp.model.KnowledgeManifest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §4.3a skill negative scope + own authority ceiling (v0.31, RFC-0029).
 *
 * <p>Mirrors cli/src/skill-negative-scope.test.ts and
 * parsers/python/tests/test_skill_negative_scope.py, so the three stay in cross-language
 * parity. Two capabilities a downstream KCP consumer needs from a {@code kind: skill} unit:
 *
 * <ol>
 *   <li>The skill carries its OWN {@code authority_level} — its capability ceiling — so it
 *       participates as a {@code grant_ceiling} source (§3.13) in the multi-source MIN.</li>
 *   <li>{@code action_scope.deny} — an explicit negative scope with the same
 *       {tools, paths, capabilities} shape as the allowlist. A deny entry is DENIED even
 *       when the allowlist would grant it: deny overrides allow, fail-closed.</li>
 * </ol>
 */
class KcpSkillNegativeScopeTest {

    private static Map<String, Object> baseSkill() {
        Map<String, Object> u = new HashMap<>();
        u.put("id", "rotate-signing-key");
        u.put("kind", "skill");
        u.put("path", "skills/rotate.md");
        u.put("intent", "How do I rotate the signing key safely?");
        u.put("scope", "project");
        u.put("audience", List.of("agent"));
        u.put("load_eligible", true);
        return u;
    }

    private static KnowledgeManifest manifest(List<Map<String, Object>> units, Map<String, Object> extra) {
        Map<String, Object> m = new HashMap<>();
        m.put("project", "example");
        m.put("version", "1.0.0");
        m.put("kcp_version", "0.31");
        m.put("authority_level_scale", List.of("observe", "explain", "suggest", "prepare", "commit"));
        m.put("units", units);
        if (extra != null) m.putAll(extra);
        return KcpParser.fromMap(m);
    }

    @Test
    void roundTripsDenyAlongsideAllowlist() {
        Map<String, Object> skill = baseSkill();
        Map<String, Object> deny = new HashMap<>();
        deny.put("tools", List.of("shell"));
        deny.put("paths", List.of("schema/secrets/**"));
        deny.put("capabilities", List.of("network"));
        Map<String, Object> scope = new HashMap<>();
        scope.put("tools", List.of("kcp-sign", "git"));
        scope.put("paths", List.of("schema/**"));
        scope.put("capabilities", List.of("key-management"));
        scope.put("deny", deny);
        skill.put("action_scope", scope);

        KnowledgeManifest m = manifest(List.of(skill), null);
        ActionScope as = m.units().get(0).actionScope();
        assertEquals(List.of("kcp-sign", "git"), as.tools());
        assertNotNull(as.deny());
        assertEquals(List.of("shell"), as.deny().tools());
        assertEquals(List.of("schema/secrets/**"), as.deny().paths());
        assertEquals(List.of("network"), as.deny().capabilities());
        // a well-formed deny + allow validates clean
        assertTrue(KcpValidator.validate(m).isValid());
    }

    @Test
    void deniesTokenAdjudicatesFailClosed() {
        Map<String, Object> skill = baseSkill();
        skill.put("action_scope", Map.of(
                "tools", List.of("git", "shell"),
                "deny", Map.of("tools", List.of("shell"))));
        KnowledgeManifest m = manifest(List.of(skill), null);
        ActionScope as = m.units().get(0).actionScope();
        assertTrue(KcpValidator.deniesToken(as, "tools", "shell"));
        assertFalse(KcpValidator.deniesToken(as, "tools", "git"));
    }

    @Test
    void catchesOverBroadAllowThatDenyDenies() {
        // 'shell' is both granted and forbidden — the allow is dead, deny wins.
        Map<String, Object> skill = baseSkill();
        skill.put("action_scope", Map.of(
                "tools", List.of("git", "shell"),
                "deny", Map.of("tools", List.of("shell"))));
        KcpValidator.ValidationResult r = KcpValidator.validate(manifest(List.of(skill), null));
        boolean hit = r.warnings().stream()
                .anyMatch(w -> w.contains("deny") && w.contains("shell") && w.contains("§4.3a"));
        assertTrue(hit, "expected a deny-overrides-allow warning, got: " + r.warnings());
    }

    @Test
    void warnsOnEmptyDeny() {
        Map<String, Object> skill = baseSkill();
        skill.put("action_scope", Map.of(
                "tools", List.of("git"),
                "deny", new HashMap<String, Object>()));
        KcpValidator.ValidationResult r = KcpValidator.validate(manifest(List.of(skill), null));
        boolean hit = r.warnings().stream()
                .anyMatch(w -> w.contains("deny") && w.contains("prohibits nothing"));
        assertTrue(hit, "expected an empty-deny warning, got: " + r.warnings());
    }

    @Test
    void roundTripsAuthorityLevelOnSkillAndScaleChecksIt() {
        Map<String, Object> skill = baseSkill();
        skill.put("authority_level", "prepare");
        skill.put("action_scope", Map.of("tools", List.of("kcp-sign")));
        KnowledgeManifest m = manifest(List.of(skill), null);
        assertEquals("prepare", m.units().get(0).authorityLevel());
        assertTrue(KcpValidator.validate(m).isValid());

        // a value off the declared scale warns
        Map<String, Object> skill2 = baseSkill();
        skill2.put("authority_level", "yolo");
        skill2.put("action_scope", Map.of("tools", List.of("kcp-sign")));
        KcpValidator.ValidationResult r2 = KcpValidator.validate(manifest(List.of(skill2), null));
        assertTrue(r2.warnings().stream().anyMatch(w -> w.contains("authority_level") && w.contains("yolo")));
    }

    @Test
    void skillAuthorityLevelResolvesThroughGrantCeilingUnitRef() {
        Map<String, Object> skill = baseSkill();
        skill.put("authority_level", "suggest");
        skill.put("action_scope", Map.of("tools", List.of("kcp-sign")));

        Map<String, Object> src1 = new HashMap<>();
        src1.put("id", "org-policy");
        src1.put("authority_level", "prepare");
        Map<String, Object> src2 = new HashMap<>();
        src2.put("id", "skill-ceiling");
        src2.put("unit_ref", "rotate-signing-key");
        List<Object> sources = new ArrayList<>(List.of(src1, src2));

        Map<String, Object> extra = new HashMap<>();
        extra.put("grant_ceiling", Map.of("sources", sources));

        KcpValidator.ValidationResult r = KcpValidator.validate(manifest(List.of(skill), extra));
        assertTrue(r.errors().stream().noneMatch(e -> e.contains("unit_ref")));
        assertTrue(r.isValid());
    }
}
