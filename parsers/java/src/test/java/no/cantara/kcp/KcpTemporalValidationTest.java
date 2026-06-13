package no.cantara.kcp;

import no.cantara.kcp.model.KnowledgeManifest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Temporal validation tests (§4.22 unit-level; §3.6 manifests[].temporal).
 * Mirrors cli/src/temporal-validation.test.ts and the Python suite so the §7
 * warnings and superseded_by cycle MUST-errors stay in cross-language parity.
 */
class KcpTemporalValidationTest {

    private static final String PAST = "2000-01-01";
    private static final String FUTURE = "2999-12-31";

    private static Map<String, Object> unit(String id, Map<String, Object> temporal, Map<String, Object> discovery) {
        Map<String, Object> u = new HashMap<>();
        u.put("id", id);
        u.put("path", "docs/" + id + ".md");
        u.put("intent", "Unit " + id);
        u.put("scope", "project");
        u.put("audience", List.of("agent"));
        if (temporal != null) u.put("temporal", temporal);
        if (discovery != null) u.put("discovery", discovery);
        return u;
    }

    private static Map<String, Object> manifest(List<Object> units, Map<String, Object> extra) {
        Map<String, Object> m = new HashMap<>();
        m.put("kcp_version", "0.21");
        m.put("project", "test");
        m.put("version", "1.0.0");
        m.put("units", units);
        if (extra != null) m.putAll(extra);
        return m;
    }

    private static KcpValidator.ValidationResult validateUnits(List<Object> units, Map<String, Object> extra) {
        KnowledgeManifest m = KcpParser.fromMap(manifest(units, extra));
        return KcpValidator.validate(m);
    }

    private static boolean anyWarn(KcpValidator.ValidationResult r, String needle) {
        return r.warnings().stream().anyMatch(w -> w.contains(needle));
    }

    private static boolean anyErr(KcpValidator.ValidationResult r, String needle) {
        return r.errors().stream().anyMatch(e -> e.contains(needle));
    }

    @Test
    void emptyWindowWarns() {
        var r = validateUnits(List.of(unit("a", Map.of("valid_from", "2026-06-01", "valid_until", "2026-01-01"), null)), null);
        assertTrue(anyWarn(r, "empty validity window"));
        assertTrue(r.isValid());
    }

    @Test
    void normalWindowClean() {
        var r = validateUnits(List.of(unit("a", Map.of("valid_from", "2026-01-01", "valid_until", FUTURE), null)), null);
        assertFalse(anyWarn(r, "empty validity window"));
        assertFalse(anyWarn(r, "stale"));
    }

    @Test
    void staleUnitWarns() {
        var r = validateUnits(List.of(unit("a", Map.of("valid_until", PAST), null)), null);
        assertTrue(anyWarn(r, "stale unit with no successor"));
    }

    @Test
    void staleSuppressedBySuccessor() {
        var r = validateUnits(List.of(
                unit("a", Map.of("valid_until", PAST, "superseded_by", "b"), null),
                unit("b", null, null)), null);
        assertFalse(anyWarn(r, "stale unit"));
    }

    @Test
    void danglingSupersededByWarns() {
        var r = validateUnits(List.of(unit("a", Map.of("superseded_by", "ghost"), null)), null);
        assertTrue(anyWarn(r, "superseded_by references unknown unit 'ghost'"));
    }

    @Test
    void namespacedSupersededByNotFlagged() {
        var r = validateUnits(List.of(unit("a", Map.of("superseded_by", "platform:newer"), null)), null);
        assertFalse(anyWarn(r, "superseded_by references unknown"));
    }

    @Test
    void supersededByCycleErrors() {
        var r = validateUnits(List.of(
                unit("a", Map.of("superseded_by", "b"), null),
                unit("b", Map.of("superseded_by", "a"), null)), null);
        assertFalse(r.isValid());
        assertTrue(anyErr(r, "superseded_by cycle"));
    }

    @Test
    void linearChainNoCycle() {
        var r = validateUnits(List.of(
                unit("a", Map.of("superseded_by", "b"), null),
                unit("b", Map.of("superseded_by", "c"), null),
                unit("c", null, null)), null);
        assertFalse(anyErr(r, "superseded_by cycle"));
    }

    @Test
    void rootTemporalDefaultsApply() {
        var r = validateUnits(
                List.of(unit("a", Map.of("valid_from", "1999-01-01"), null)),
                Map.of("temporal", Map.of("valid_until", PAST)));
        assertTrue(anyWarn(r, "stale unit"));
    }

    @Test
    void verifiedWithoutVerifiedByWarnsUnitAndRoot() {
        var r = validateUnits(
                List.of(unit("a", null, Map.of("verification_status", "verified"))),
                Map.of("discovery", Map.of("verification_status", "verified")));
        assertEquals(2, r.warnings().stream().filter(w -> w.contains("verified_by is absent")).count());
    }

    @Test
    void verifiedWithVerifiedByClean() {
        var r = validateUnits(
                List.of(unit("a", null, Map.of("verification_status", "verified", "verified_by", "key-1"))), null);
        assertFalse(anyWarn(r, "verified_by is absent"));
    }

    // --- federation temporal (§3.6 manifests[].temporal) ---

    private static Map<String, Object> ref(String id, Map<String, Object> temporal) {
        Map<String, Object> r = new HashMap<>();
        r.put("id", id);
        r.put("url", "https://example.com/" + id + "/knowledge.yaml");
        r.put("relationship", "governs");
        if (temporal != null) r.put("temporal", temporal);
        return r;
    }

    private static KcpValidator.ValidationResult validateManifests(List<Object> manifests) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("manifests", manifests);
        return validateUnits(List.of(unit("local", null, null)), extra);
    }

    @Test
    void manifestsTemporalExposed() {
        KnowledgeManifest m = KcpParser.fromMap(manifest(
                List.of(unit("local", null, null)),
                Map.of("manifests", List.of(ref("a", Map.of("valid_from", "2020-01-01"))))));
        assertEquals("2020-01-01", m.manifests().get(0).temporal().validFrom());
    }

    @Test
    void staleFederationLinkWarns() {
        var r = validateManifests(List.of(ref("a", Map.of("valid_until", PAST))));
        assertTrue(anyWarn(r, "stale federation link"));
    }

    @Test
    void federationEmptyWindowAndDangling() {
        var r = validateManifests(List.of(
                ref("a", Map.of("valid_from", "2026-06-01", "valid_until", "2026-01-01", "superseded_by", "ghost"))));
        assertTrue(anyWarn(r, "empty validity window"));
        assertTrue(anyWarn(r, "unknown manifests[].id 'ghost'"));
    }

    @Test
    void federationSupersededCycleErrors() {
        var r = validateManifests(List.of(
                ref("a", Map.of("superseded_by", "b")),
                ref("b", Map.of("superseded_by", "a"))));
        assertFalse(r.isValid());
        assertTrue(anyErr(r, "manifests[].temporal.superseded_by cycle"));
    }
}
