package no.cantara.kcp;

import no.cantara.kcp.model.ActionScope;
import no.cantara.kcp.model.KnowledgeManifest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §4.3a (v0.32.1) — deny.paths entries are PATTERNS, matched structurally.
 *
 * <p>Mirrors cli/src/deny-path-glob.test.ts and parsers/python/tests/test_deny_path_glob.py.
 * Exact-string comparison never fires the {@code schema/secrets/**} carve-out the spec
 * promises: no requested path is ever the literal string {@code schema/secrets/**}. Pins
 * glob semantics for deniesToken, the union (effectiveDeniesToken), and the §4.3b
 * self-nullified lint — and pins that tools/capabilities stay exact-match.
 */
class KcpDenyPathGlobTest {

    @Test
    void doubleStarCrossesSegments() {
        assertTrue(KcpValidator.pathGlobMatches("legal/hold/**", "legal/hold/2025/case.pdf"));
        assertTrue(KcpValidator.pathGlobMatches("legal/hold/**", "legal/hold/x"));
        assertFalse(KcpValidator.pathGlobMatches("legal/hold/**", "legal/holdings/x"));
    }

    @Test
    void singleStarStaysWithinSegment() {
        assertTrue(KcpValidator.pathGlobMatches("customers/*/pii", "customers/acme/pii"));
        assertFalse(KcpValidator.pathGlobMatches("customers/*/pii", "customers/a/b/pii"));
    }

    @Test
    void literalsAreEscaped() {
        assertTrue(KcpValidator.pathGlobMatches("a.b/c", "a.b/c"));
        assertFalse(KcpValidator.pathGlobMatches("a.b/c", "axb/c"));
    }

    private static KnowledgeManifest scopedManifest() {
        Map<String, Object> scope = new HashMap<>();
        scope.put("paths", List.of("schema/**"));
        scope.put("deny", Map.of(
                "paths", List.of("schema/secrets/**", "legal/hold/**"),
                "tools", List.of("delete")));
        Map<String, Object> u = new HashMap<>();
        u.put("id", "s");
        u.put("kind", "skill");
        u.put("path", "skills/s.md");
        u.put("intent", "How do I rotate safely?");
        u.put("scope", "project");
        u.put("audience", List.of("agent"));
        u.put("load_eligible", true);
        u.put("action_scope", scope);
        Map<String, Object> m = new HashMap<>();
        m.put("project", "example");
        m.put("version", "1.0.0");
        m.put("kcp_version", "0.32");
        m.put("units", List.of(u));
        return KcpParser.fromMap(m);
    }

    @Test
    void denyGlobDeniesPathsBeneathIt() {
        ActionScope scope = scopedManifest().units().get(0).actionScope();
        assertTrue(KcpValidator.deniesToken(scope, "paths", "legal/hold/2025/case.pdf"));
        assertTrue(KcpValidator.deniesToken(scope, "paths", "schema/secrets/key.pem"));
    }

    @Test
    void carveOutFires() {
        ActionScope scope = scopedManifest().units().get(0).actionScope();
        assertFalse(KcpValidator.deniesToken(scope, "paths", "schema/api.json"));
        assertTrue(KcpValidator.deniesToken(scope, "paths", "schema/secrets/nested/key.pem"));
    }

    @Test
    void toolsRemainExact() {
        ActionScope scope = scopedManifest().units().get(0).actionScope();
        assertTrue(KcpValidator.deniesToken(scope, "tools", "delete"));
        assertFalse(KcpValidator.deniesToken(scope, "tools", "delete_all"));
    }

    private static KnowledgeManifest playbookManifest(List<String> skillPaths, List<String> pbDenyPaths) {
        Map<String, Object> skill = new HashMap<>();
        skill.put("id", "sletteagent");
        skill.put("kind", "skill");
        skill.put("path", "skills/s.md");
        skill.put("intent", "How do I delete compliantly?");
        skill.put("scope", "project");
        skill.put("audience", List.of("agent"));
        skill.put("load_eligible", true);
        skill.put("action_scope", Map.of("tools", List.of("read"), "paths", skillPaths));
        Map<String, Object> pb = new HashMap<>();
        pb.put("id", "pb");
        pb.put("kind", "playbook");
        pb.put("path", "playbooks/p.md");
        pb.put("intent", "How is deletion executed?");
        pb.put("scope", "project");
        pb.put("audience", List.of("agent"));
        pb.put("load_eligible", true);
        pb.put("authority_level", "commit");
        pb.put("action_scope", Map.of("deny", Map.of("paths", pbDenyPaths)));
        pb.put("steps", List.of(Map.of("id", "slett", "uses", "sletteagent", "authority_level", "commit")));
        Map<String, Object> m = new HashMap<>();
        m.put("project", "example");
        m.put("version", "1.0.0");
        m.put("kcp_version", "0.32");
        m.put("authority_level_scale", List.of("observe", "explain", "suggest", "prepare", "commit"));
        m.put("units", List.of(skill, pb));
        return KcpParser.fromMap(m);
    }

    @Test
    void unionInheritsGlob() {
        KnowledgeManifest m = playbookManifest(List.of("customers/**"), List.of("legal/hold/**"));
        ActionScope pbScope = m.units().get(1).actionScope();
        ActionScope skillScope = m.units().get(0).actionScope();
        assertTrue(KcpValidator.effectiveDeniesToken(
                java.util.Arrays.asList(pbScope, skillScope), "paths", "legal/hold/2025/x"));
        assertFalse(KcpValidator.effectiveDeniesToken(
                java.util.Arrays.asList(pbScope, skillScope), "paths", "customers/acme/x"));
    }

    @Test
    void selfNullifiedLintSeesGlobContainment() {
        var result = KcpValidator.validate(
                playbookManifest(List.of("legal/hold/2025/**"), List.of("legal/hold/**")), null);
        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.contains("self-nullified") && w.contains("'paths'")));
    }

    @Test
    void carveOutDoesNotSelfNullify() {
        var result = KcpValidator.validate(
                playbookManifest(List.of("customers/**"), List.of("customers/pii/**")), null);
        assertFalse(result.warnings().stream().anyMatch(w -> w.contains("self-nullified")));
    }
}
