package no.cantara.kcp;

import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.PlaybookStep;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code kind: playbook} — §4.3b (v0.29, RFC-0027).
 *
 * <p>Mirrors cli/src/playbook.test.ts and parsers/python/tests/test_playbook.py case for
 * case, so the three implementations can be compared by reading them side by side.
 *
 * <p>Two rules are tested from the attack direction rather than the happy path, because
 * the adversarial review on RFC-0027 found both stated as advice while being load-bearing:
 *
 * <ul>
 *   <li>an unresolvable {@code uses} must be an ERROR. A resolvable {@code uses} is the
 *       entire justification for playbook being a distinct kind rather than
 *       {@code executable} plus a metadata block; a dangling reference that lints clean
 *       removes the only thing the new kind buys.
 *   <li>nesting must be an ERROR pending RFC-0027 OQ1. As a warning it is no guard at
 *       all: nested playbooks form a combined depends_on graph the per-playbook cycle
 *       check never sees.
 * </ul>
 */
class KcpPlaybookTest {

    private static Map<String, Object> skill() {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("id", "run-test-suite");
        u.put("kind", "skill");
        u.put("path", "skills/run.md");
        u.put("intent", "How do I run the suite?");
        u.put("scope", "project");
        u.put("audience", List.of("agent"));
        u.put("action_scope", Map.of("tools", List.of("bash"), "paths", List.of("test/**")));
        return u;
    }

    private static Map<String, Object> playbook(Object steps) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("id", "promote");
        u.put("kind", "playbook");
        u.put("path", "playbooks/promote.md");
        u.put("intent", "How do we promote a build?");
        u.put("scope", "project");
        u.put("audience", List.of("agent"));
        if (steps != null) u.put("steps", steps);
        return u;
    }

    private static Map<String, Object> step(String id, String... kv) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", id);
        for (int i = 0; i < kv.length; i += 2) s.put(kv[i], kv[i + 1]);
        return s;
    }

    private static KnowledgeManifest manifest(List<Map<String, Object>> units) {
        return manifest(units, Map.of());
    }

    private static KnowledgeManifest manifest(List<Map<String, Object>> units,
                                              Map<String, Object> extra) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("project", "example");
        raw.put("version", "1.0.0");
        raw.put("kcp_version", "0.29");
        raw.put("units", units);
        raw.putAll(extra);
        return KcpParser.fromMap(raw);
    }

    private static List<String> errors(List<Map<String, Object>> units) {
        return KcpValidator.validate(manifest(units)).errors();
    }

    private static List<String> warnings(List<Map<String, Object>> units) {
        return KcpValidator.validate(manifest(units)).warnings();
    }

    private static boolean any(List<String> messages, String fragment) {
        return messages.stream().anyMatch(m -> m.contains(fragment));
    }

    // --- parsing ---------------------------------------------------------------

    @Test
    void parsesEveryStepField() {
        Map<String, Object> s = step("verify",
                "uses", "run-test-suite",
                "authority_level", "observe",
                "success_condition", "zero failures",
                "on_failure", "abort",
                "timeout", "PT10M");
        s.put("depends_on", List.of());
        s.put("escalation", List.of("requires_approval"));

        var m = manifest(List.of(skill(), playbook(List.of(s))));
        PlaybookStep parsed = m.units().get(1).steps().get(0);
        assertEquals("verify", parsed.id());
        assertEquals("run-test-suite", parsed.uses());
        assertEquals("observe", parsed.authorityLevel());
        assertEquals(List.of("requires_approval"), parsed.escalation());
        assertEquals("zero failures", parsed.successCondition());
        assertEquals("abort", parsed.onFailure());
        assertEquals("PT10M", parsed.timeout());
    }

    @Test
    void bareEscalationStringNormalisesToAList() {
        // §4.3b calls the triggers disjunctive, so a scalar and a one-element list mean
        // the same thing. Normalising at parse time means no consumer handles both.
        var m = manifest(List.of(skill(), playbook(List.of(
                step("a", "uses", "run-test-suite", "escalation", "requires_approval")))));
        assertEquals(List.of("requires_approval"), m.units().get(1).steps().get(0).escalation());
    }

    @Test
    void absentStepsIsNullNotEmpty() {
        // "declares no steps" and "declares an empty composition" are different
        // statements. The validator rejects both for a playbook, but the parser must
        // keep them distinguishable.
        assertNull(manifest(List.of(skill())).units().get(0).steps());
    }

    @Test
    void stepsWithoutAnIdAreDropped() {
        // A step with no identity cannot be named by depends_on, so it cannot join the
        // graph. Half-parsing would put an id-less entry into a structure keyed by id.
        List<Object> steps = new ArrayList<>();
        steps.add("not-a-map");
        steps.add(Map.of("uses", "run-test-suite"));
        steps.add(Map.of("id", "ok", "action", "x"));
        var m = manifest(List.of(skill(), playbook(steps)));
        assertEquals(List.of("ok"), m.units().get(1).steps().stream().map(PlaybookStep::id).toList());
    }

    @Test
    void malformedStepsBlockDoesNotBreakTheParse() {
        var m = manifest(List.of(skill(), playbook("steps")));
        assertNull(m.units().get(1).steps());
        assertEquals("promote", m.units().get(1).id());
    }

    // --- validation: structure -------------------------------------------------

    @Test
    void wellFormedPlaybookHasNoErrors() {
        var m = manifest(
                List.of(skill(), playbook(List.of(
                        step("verify", "uses", "run-test-suite", "authority_level", "observe")))),
                Map.of("authority_level_scale",
                        List.of("observe", "explain", "suggest", "prepare", "commit")));
        assertEquals(List.of(), KcpValidator.validate(m).errors());
    }

    @Test
    void playbookWithoutStepsErrors() {
        assertTrue(any(errors(List.of(playbook(null))), "non-empty 'steps'"));
    }

    @Test
    void emptyStepsListErrors() {
        assertTrue(any(errors(List.of(playbook(List.of()))), "non-empty 'steps'"));
    }

    @Test
    void stepWithNeitherUsesNorActionErrors() {
        assertTrue(any(errors(List.of(playbook(List.of(step("orphan"))))),
                "either 'uses' or 'action'"));
    }

    @Test
    void duplicateStepIdsError() {
        var errs = errors(List.of(skill(), playbook(List.of(
                step("a", "uses", "run-test-suite"),
                step("a", "action", "again")))));
        assertTrue(any(errs, "duplicate step id 'a'"));
    }

    @Test
    void unknownOnFailureErrors() {
        var errs = errors(List.of(skill(), playbook(List.of(
                step("a", "uses", "run-test-suite", "on_failure", "retry")))));
        assertTrue(any(errs, "'on_failure' must be one of"));
    }

    // --- validation: uses resolution -------------------------------------------

    @Test
    void unresolvableUsesIsAnErrorNotAWarning() {
        var r = KcpValidator.validate(manifest(List.of(
                playbook(List.of(step("a", "uses", "nonexistent"))))));
        assertTrue(any(r.errors(), "not declared in this manifest"));
        assertFalse(any(r.warnings(), "nonexistent"));
    }

    @Test
    void usesResolvesAgainstAUnitDeclaredLater() {
        // The check is a second pass for exactly this reason: an inline check would
        // reject a legal forward reference, the id set being incomplete mid-loop.
        assertEquals(List.of(), errors(List.of(
                playbook(List.of(step("a", "uses", "run-test-suite"))), skill())));
    }

    @Test
    void nestingIsAnError() {
        Map<String, Object> inner = playbook(List.of(step("x", "action", "inner")));
        inner.put("id", "inner");
        Map<String, Object> outer = playbook(List.of(step("a", "uses", "inner")));
        outer.put("id", "outer");
        assertTrue(any(errors(List.of(inner, outer)), "nesting is not permitted"));
    }

    @Test
    void usesNamingAResolvableNonSkillUnitWarnsOnly() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", "notes");
        doc.put("kind", "knowledge");
        doc.put("path", "n.md");
        doc.put("intent", "x");
        doc.put("scope", "project");
        doc.put("audience", List.of("agent"));
        var r = KcpValidator.validate(manifest(List.of(doc,
                playbook(List.of(step("a", "uses", "notes"))))));
        assertEquals(List.of(), r.errors());
        assertTrue(any(r.warnings(), "SHOULD name a kind: skill unit"));
    }

    // --- validation: the depends_on graph --------------------------------------

    private static Map<String, Object> dependentStep(String id, String... deps) {
        Map<String, Object> s = step(id, "uses", "run-test-suite");
        s.put("depends_on", List.of(deps));
        return s;
    }

    @Test
    void danglingDependsOnErrors() {
        assertTrue(any(errors(List.of(skill(), playbook(List.of(dependentStep("a", "ghost"))))),
                "depends_on names unknown step 'ghost'"));
    }

    @Test
    void twoStepCycleErrors() {
        var errs = errors(List.of(skill(), playbook(List.of(
                dependentStep("a", "b"), dependentStep("b", "a")))));
        assertTrue(any(errs, "contains a cycle"));
    }

    @Test
    void longerCycleReportsThePath() {
        var errs = errors(List.of(skill(), playbook(List.of(
                dependentStep("a", "c"), dependentStep("b", "a"), dependentStep("c", "b")))));
        var cycle = errs.stream().filter(e -> e.contains("contains a cycle")).findFirst();
        assertTrue(cycle.isPresent());
        assertTrue(cycle.get().contains("->"));
    }

    @Test
    void selfDependencyIsACycle() {
        assertTrue(any(errors(List.of(skill(), playbook(List.of(dependentStep("a", "a"))))),
                "contains a cycle"));
    }

    @Test
    void diamondIsNotACycle() {
        // The classic false positive for a naive visited-set walk: d is reached twice by
        // distinct paths, which is convergence, not a cycle.
        assertEquals(List.of(), errors(List.of(skill(), playbook(List.of(
                step("a", "uses", "run-test-suite"),
                dependentStep("b", "a"),
                dependentStep("c", "a"),
                dependentStep("d", "b", "c"))))));
    }

    @Test
    void longChainDoesNotExhaustTheStack() {
        // Untrusted input: a deep chain must report cleanly, not overflow the stack.
        List<Object> steps = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            steps.add(i == 0 ? step("s0", "uses", "run-test-suite")
                    : dependentStep("s" + i, "s" + (i - 1)));
        }
        var errs = KcpValidator.validate(manifest(List.of(skill(), playbook(steps)))).errors();
        assertTrue(errs.stream().noneMatch(e -> e.contains("cycle")));
    }

    // --- validation: scope verifiability ---------------------------------------

    @Test
    void inlineStepsWarnTheyAreScopeUnbounded() {
        assertTrue(any(warnings(List.of(playbook(List.of(step("a", "action", "do it"))))),
                "bounded only by its authority_level"));
    }

    @Test
    void declaredScopeIsUnverifiedWhenAStepIsInline() {
        // §4.3b: an unverifiable declaration that lints clean is worse than none,
        // because it reads as checked.
        Map<String, Object> pb = playbook(List.of(
                step("a", "uses", "run-test-suite"), step("b", "action", "inline")));
        pb.put("action_scope", Map.of("tools", List.of("bash")));
        assertTrue(any(warnings(List.of(skill(), pb)), "UNVERIFIED"));
    }

    @Test
    void declaredScopeIsUnverifiedWhenAReferencedUnitHasNoScope() {
        Map<String, Object> bare = skill();
        bare.put("id", "bare");
        bare.remove("action_scope");
        Map<String, Object> pb = playbook(List.of(step("a", "uses", "bare")));
        pb.put("action_scope", Map.of("tools", List.of("bash")));
        assertTrue(any(warnings(List.of(bare, pb)), "UNVERIFIED"));
    }

    @Test
    void scopeIsVerifiedWhenEveryStepResolvesToAScopedUnit() {
        Map<String, Object> pb = playbook(List.of(step("a", "uses", "run-test-suite")));
        pb.put("action_scope", Map.of("tools", List.of("bash")));
        assertFalse(any(warnings(List.of(skill(), pb)), "UNVERIFIED"));
    }

    @Test
    void mutatingStepWithoutAuthorityLevelWarns() {
        assertTrue(any(warnings(List.of(skill(),
                playbook(List.of(step("a", "uses", "run-test-suite"))))),
                "omits 'authority_level'"));
    }

    // --- validation: non-playbook units ----------------------------------------

    @Test
    void stepsOnANonPlaybookWarns() {
        Map<String, Object> s = skill();
        s.put("steps", List.of(Map.of("id", "a", "action", "x")));
        assertTrue(any(warnings(List.of(s)), "only enacted for kind: playbook"));
    }

    @Test
    void playbookIsARecognisedKind() {
        assertFalse(any(warnings(List.of(skill(),
                playbook(List.of(step("a", "uses", "run-test-suite"))))), "unknown 'kind'"));
    }
}
