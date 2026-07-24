package no.cantara.kcp;

import no.cantara.kcp.model.KnowledgeManifest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §3.13 authority_level / grant_ceiling validation tests (RFC-0025, v0.27).
 * Mirrors bridge/typescript/tests/validator.test.ts's "§3.13" describe block so the
 * errors/warnings and the computeGrantCeiling/applyAuthorityCap utilities stay in
 * cross-language parity.
 */
class KcpAuthorityLevelTest {

    private static final List<String> SCALE = List.of("observe", "explain", "suggest", "prepare", "commit");

    private static Map<String, Object> baseUnit() {
        Map<String, Object> u = new HashMap<>();
        u.put("id", "overview");
        u.put("path", "README.md");
        u.put("intent", "What is this project?");
        u.put("scope", "global");
        u.put("audience", List.of("agent"));
        return u;
    }

    private static Map<String, Object> unit(String id) {
        Map<String, Object> u = new HashMap<>();
        u.put("id", id);
        u.put("path", "f.md");
        u.put("intent", "i");
        u.put("scope", "global");
        u.put("audience", List.of("agent"));
        return u;
    }

    private static KnowledgeManifest makeManifest(Map<String, Object> extra) {
        Map<String, Object> m = new HashMap<>();
        m.put("project", "test");
        m.put("version", "1.0.0");
        m.put("kcp_version", "0.12");
        m.put("units", new ArrayList<>(List.of(baseUnit())));
        if (extra != null) m.putAll(extra);
        return KcpParser.fromMap(m);
    }

    private static Map<String, Object> taskType(String id, String authorityLevel) {
        Map<String, Object> t = new HashMap<>();
        t.put("id", id);
        if (authorityLevel != null) t.put("authority_level", authorityLevel);
        return t;
    }

    private static Map<String, Object> agent(String id, String authorityLevel) {
        Map<String, Object> a = new HashMap<>();
        a.put("id", id);
        if (authorityLevel != null) a.put("authority_level", authorityLevel);
        return a;
    }

    private static Map<String, Object> source(String id, Map<String, Object> fields) {
        Map<String, Object> s = new HashMap<>();
        s.put("id", id);
        s.putAll(fields);
        return s;
    }

    private static boolean anyErr(KcpValidator.ValidationResult r, String needle) {
        return r.errors().stream().anyMatch(e -> e.contains(needle));
    }

    private static boolean anyWarn(KcpValidator.ValidationResult r, String needle) {
        return r.warnings().stream().anyMatch(w -> w.contains(needle));
    }

    @Test
    void acceptsWellFormedGrantCeilingWithInlineSources() {
        KnowledgeManifest m = makeManifest(Map.of(
                "authority_level_scale", SCALE,
                "task_types", List.of(taskType("t1", "explain")),
                "grant_ceiling", Map.of("sources", List.of(
                        source("org-risk", Map.of("authority_level", "prepare")),
                        source("task-ceiling", Map.of("task_type_ref", "t1"))
                ))
        ));
        var r = KcpValidator.validate(m);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
    }

    @Test
    void errorsOnDuplicateTaskTypeId() {
        KnowledgeManifest m = makeManifest(Map.of(
                "task_types", List.of(taskType("dup", null), taskType("dup", null))
        ));
        var r = KcpValidator.validate(m);
        assertTrue(anyErr(r, "Duplicate task_types[].id"));
    }

    @Test
    void errorsOnDuplicateAgentId() {
        KnowledgeManifest m = makeManifest(Map.of(
                "agents", List.of(agent("dup", null), agent("dup", null))
        ));
        var r = KcpValidator.validate(m);
        assertTrue(anyErr(r, "Duplicate agents[].id"));
    }

    @Test
    void errorsWhenGrantCeilingOmitsMandatorySource() {
        KnowledgeManifest m = makeManifest(Map.of(
                "grant_ceiling", Map.of(
                        "sources", List.of(source("a", Map.of("authority_level", "prepare"))),
                        "mandatory_sources", List.of("a", "b")
                )
        ));
        var r = KcpValidator.validate(m);
        assertTrue(anyErr(r, "missing mandatory source 'b'"));
    }

    @Test
    void errorsWhenSourceDeclaresBothAuthorityLevelAndRef() {
        KnowledgeManifest m = makeManifest(Map.of(
                "task_types", List.of(taskType("t1", "explain")),
                "grant_ceiling", Map.of("sources", List.of(
                        source("a", Map.of("authority_level", "prepare", "task_type_ref", "t1"))
                ))
        ));
        var r = KcpValidator.validate(m);
        assertTrue(anyErr(r, "mutually exclusive"));
    }

    @Test
    void errorsWhenSourceDeclaresNeitherAuthorityLevelNorRef() {
        KnowledgeManifest m = makeManifest(Map.of(
                "grant_ceiling", Map.of("sources", List.of(source("a", Map.of())))
        ));
        var r = KcpValidator.validate(m);
        assertTrue(anyErr(r, "must declare exactly one of"));
    }

    @Test
    void errorsWhenRefsPointToUnknownIds() {
        KnowledgeManifest m = makeManifest(Map.of(
                "grant_ceiling", Map.of("sources", List.of(
                        source("a", Map.of("unit_ref", "nope")),
                        source("b", Map.of("task_type_ref", "nope")),
                        source("c", Map.of("agent_ref", "nope"))
                ))
        ));
        var r = KcpValidator.validate(m);
        long count = r.errors().stream().filter(e -> e.contains("references unknown")).count();
        assertEquals(3, count);
    }

    @Test
    void warnsOnAuthorityLevelNotInDeclaredScale() {
        KnowledgeManifest m = makeManifest(Map.of(
                "authority_level_scale", SCALE,
                "task_types", List.of(taskType("t1", "yolo"))
        ));
        var r = KcpValidator.validate(m);
        assertTrue(anyWarn(r, "not in the declared 'authority_level_scale'"));
    }

    @Test
    void warnsAuthorityCeilingUndeclaredWhenScaleDeclaredButTaskTypeHasNoCeiling() {
        KnowledgeManifest m = makeManifest(Map.of(
                "authority_level_scale", SCALE,
                "task_types", List.of(taskType("t1", null))
        ));
        var r = KcpValidator.validate(m);
        assertTrue(anyWarn(r, "authority_ceiling_undeclared"));
    }

    @Test
    void doesNotWarnAuthorityCeilingUndeclaredWhenGrantCeilingExists() {
        KnowledgeManifest m = makeManifest(Map.of(
                "authority_level_scale", SCALE,
                "task_types", List.of(taskType("t1", null)),
                "grant_ceiling", Map.of("sources", List.of(source("a", Map.of("authority_level", "prepare"))))
        ));
        var r = KcpValidator.validate(m);
        assertFalse(anyWarn(r, "authority_ceiling_undeclared"));
    }

    @Test
    void computeGrantCeilingResolvesMinimumAndNamesBindingSource() {
        KnowledgeManifest m = makeManifest(Map.of(
                "authority_level_scale", SCALE,
                "task_types", List.of(taskType("change-status", "explain")),
                "agents", List.of(agent("lara", "prepare")),
                "grant_ceiling", Map.of("sources", List.of(
                        source("org-risk", Map.of("authority_level", "prepare")),
                        source("org-data", Map.of("authority_level", "suggest")),
                        source("task-ceiling", Map.of("task_type_ref", "change-status")),
                        source("agent-ceiling", Map.of("agent_ref", "lara"))
                ))
        ));
        var result = KcpValidator.computeGrantCeiling(m);
        assertEquals("explain", result.effectiveLevel());
        assertEquals(List.of("task-ceiling"), result.bindingSourceIds());
    }

    @Test
    void computeGrantCeilingReportsAllTiedSources() {
        KnowledgeManifest m = makeManifest(Map.of(
                "authority_level_scale", SCALE,
                "grant_ceiling", Map.of("sources", List.of(
                        source("a", Map.of("authority_level", "suggest")),
                        source("b", Map.of("authority_level", "suggest")),
                        source("c", Map.of("authority_level", "prepare"))
                ))
        ));
        var result = KcpValidator.computeGrantCeiling(m);
        assertEquals("suggest", result.effectiveLevel());
        assertEquals(List.of("a", "b"), result.bindingSourceIds().stream().sorted().toList());
    }

    @Test
    void computeGrantCeilingTreatsUnresolvedRefAsNonBinding() {
        Map<String, Object> extra = new HashMap<>();
        extra.put("authority_level_scale", SCALE);
        extra.put("units", List.of(unit("u1")));
        extra.put("grant_ceiling", Map.of("sources", List.of(
                source("org-risk", Map.of("authority_level", "prepare")),
                source("unit-ceiling", Map.of("unit_ref", "u1")) // u1 has no authority_level declared
        )));
        KnowledgeManifest m = makeManifest(extra);
        var result = KcpValidator.computeGrantCeiling(m);
        assertEquals("prepare", result.effectiveLevel());
        assertEquals(List.of("org-risk"), result.bindingSourceIds());
    }

    @Test
    void applyAuthorityCapCapsADeclaredPermissionStricterThanTheEffectiveLevelAllows() {
        assertEquals("requires_approval", KcpValidator.applyAuthorityCap("initiative", "modify", "suggest"));
        assertEquals("denied", KcpValidator.applyAuthorityCap("initiative", "share_externally", "explain"));
    }

    @Test
    void applyAuthorityCapNeverWidensAnAlreadyStricterPermission() {
        assertEquals("denied", KcpValidator.applyAuthorityCap("denied", "modify", "commit"));
    }

    @Test
    void applyAuthorityCapPassesThroughWhenNoEffectiveLevelInScope() {
        assertEquals("initiative", KcpValidator.applyAuthorityCap("initiative", "modify", null));
    }
}
