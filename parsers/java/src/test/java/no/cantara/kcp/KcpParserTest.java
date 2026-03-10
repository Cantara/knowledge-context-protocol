package no.cantara.kcp;

import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KcpParserTest {

    private static final Map<String, Object> MINIMAL = Map.of(
            "project", "test-project",
            "version", "1.0.0",
            "units", List.of(Map.of(
                    "id", "overview",
                    "path", "README.md",
                    "intent", "What is this project?",
                    "scope", "global",
                    "audience", List.of("human", "agent")
            ))
    );

    private static Map<String, Object> minimalWith(String key, Object value) {
        Map<String, Object> m = new HashMap<>(MINIMAL);
        m.put(key, value);
        return m;
    }

    private static final Map<String, Object> COMPLETE;
    static {
        // Build COMPLETE with v0.3 fields
        Map<String, Object> m = new HashMap<>();
        m.put("project", "wiki.example.org");
        m.put("version", "1.0.0");
        m.put("kcp_version", "0.3");
        m.put("updated", "2026-02-25");
        m.put("language", "en");
        m.put("license", "Apache-2.0");
        m.put("indexing", "open");
        m.put("units", List.of(
                Map.of("id", "about", "path", "about.md",
                        "intent", "Who maintains this?",
                        "scope", "global", "audience", List.of("human", "agent"),
                        "validated", "2026-02-24", "update_frequency", "monthly"),
                Map.of("id", "methodology", "path", "methodology/overview.md",
                        "intent", "What methodology is used?",
                        "scope", "global", "audience", List.of("developer", "agent"),
                        "depends_on", List.of("about"),
                        "triggers", List.of("methodology", "productivity"),
                        "format", "markdown", "language", "en"),
                Map.of("id", "knowledge-infra", "path", "tools/knowledge-infra.md",
                        "intent", "How is knowledge infrastructure set up?",
                        "scope", "global", "audience", List.of("developer", "agent"),
                        "depends_on", List.of("methodology"))
        ));
        m.put("relationships", List.of(
                Map.of("from", "methodology", "to", "knowledge-infra", "type", "enables"),
                Map.of("from", "about", "to", "methodology", "type", "context")
        ));
        COMPLETE = Map.copyOf(m);
    }

    // -----------------------------------------------------------------------
    // Parser tests
    // -----------------------------------------------------------------------

    @Test
    void parsesMinimalManifest() {
        KnowledgeManifest m = KcpParser.fromMap(MINIMAL);
        assertEquals("test-project", m.project());
        assertEquals("1.0.0", m.version());
        assertNull(m.kcpVersion());
        assertEquals(1, m.units().size());
        assertEquals("overview", m.units().get(0).id());
    }

    @Test
    void parsesKcpVersion() {
        KnowledgeManifest m = KcpParser.fromMap(minimalWith("kcp_version", "0.3"));
        assertEquals("0.3", m.kcpVersion());
    }

    @Test
    void parsesCompleteManifest() {
        KnowledgeManifest m = KcpParser.fromMap(COMPLETE);
        assertEquals("wiki.example.org", m.project());
        assertEquals("0.3", m.kcpVersion());
        assertEquals(LocalDate.of(2026, 2, 25), m.updated());
        assertEquals("en", m.language());
        assertEquals("Apache-2.0", m.license());
        assertEquals("open", m.indexing());
        assertEquals(3, m.units().size());
        assertEquals(2, m.relationships().size());
    }

    @Test
    void parsesDependsOn() {
        KnowledgeManifest m = KcpParser.fromMap(COMPLETE);
        KnowledgeUnit methodology = m.units().stream()
                .filter(u -> u.id().equals("methodology")).findFirst().orElseThrow();
        assertEquals(List.of("about"), methodology.dependsOn());
    }

    @Test
    void parsesValidatedDate() {
        KnowledgeManifest m = KcpParser.fromMap(COMPLETE);
        KnowledgeUnit about = m.units().stream()
                .filter(u -> u.id().equals("about")).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 2, 24), about.validated());
    }

    @Test
    void parsesTriggers() {
        KnowledgeManifest m = KcpParser.fromMap(COMPLETE);
        KnowledgeUnit methodology = m.units().stream()
                .filter(u -> u.id().equals("methodology")).findFirst().orElseThrow();
        assertTrue(methodology.triggers().contains("methodology"));
        assertTrue(methodology.triggers().contains("productivity"));
    }

    @Test
    void parsesRelationshipContent() {
        KnowledgeManifest m = KcpParser.fromMap(COMPLETE);
        var rel = m.relationships().stream()
                .filter(r -> r.fromId().equals("methodology")).findFirst().orElseThrow();
        assertEquals("knowledge-infra", rel.toId());
        assertEquals("enables", rel.type());
    }

    @Test
    void parsesFormatAndLanguage() {
        KnowledgeManifest m = KcpParser.fromMap(COMPLETE);
        KnowledgeUnit methodology = m.units().stream()
                .filter(u -> u.id().equals("methodology")).findFirst().orElseThrow();
        assertEquals("markdown", methodology.format());
        assertEquals("en", methodology.language());
    }

    @Test
    void parsesUpdateFrequency() {
        KnowledgeManifest m = KcpParser.fromMap(COMPLETE);
        KnowledgeUnit about = m.units().stream()
                .filter(u -> u.id().equals("about")).findFirst().orElseThrow();
        assertEquals("monthly", about.updateFrequency());
    }

    @Test
    void parsesKindField() {
        Map<String, Object> unitWithKind = new HashMap<>(Map.of(
                "id", "api-spec", "path", "api.yaml", "intent", "API spec",
                "scope", "module", "audience", List.of("developer"), "kind", "schema"
        ));
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(unitWithKind)
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        assertEquals("schema", m.units().get(0).kind());
    }

    @Test
    void parsesLicenseString() {
        KnowledgeManifest m = KcpParser.fromMap(COMPLETE);
        assertEquals("Apache-2.0", m.license());
    }

    @Test
    void parsesLicenseObject() {
        Map<String, Object> licenseObj = Map.of("spdx", "CC-BY-4.0", "attribution_required", true);
        Map<String, Object> unitData = new HashMap<>(Map.of(
                "id", "u1", "path", "f.md", "intent", "test", "scope", "global",
                "audience", List.of("agent"), "license", licenseObj
        ));
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(unitData)
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        assertInstanceOf(Map.class, m.units().get(0).license());
    }

    @Test
    void parsesIndexingField() {
        Map<String, Object> unitData = new HashMap<>(Map.of(
                "id", "u1", "path", "f.md", "intent", "test", "scope", "global",
                "audience", List.of("agent"), "indexing", "no-train"
        ));
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(unitData)
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        assertEquals("no-train", m.units().get(0).indexing());
    }

    @Test
    void parsesContentType() {
        Map<String, Object> unitData = new HashMap<>(Map.of(
                "id", "u1", "path", "api.yaml", "intent", "API", "scope", "module",
                "audience", List.of("developer"), "content_type", "application/vnd.oai.openapi+yaml"
        ));
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(unitData)
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        assertEquals("application/vnd.oai.openapi+yaml", m.units().get(0).contentType());
    }

    // -----------------------------------------------------------------------
    // Validator tests
    // -----------------------------------------------------------------------

    @Test
    void validatesValidComplete() {
        KnowledgeManifest m = KcpParser.fromMap(COMPLETE);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid());
        assertFalse(result.hasWarnings());
    }

    @Test
    void missingKcpVersionProducesWarning() {
        KnowledgeManifest m = KcpParser.fromMap(MINIMAL);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("kcp_version")));
    }

    @Test
    void knownKcpVersionNoWarning() {
        KnowledgeManifest m = KcpParser.fromMap(minimalWith("kcp_version", "0.3"));
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("kcp_version")));
    }

    @Test
    void v02KcpVersionAccepted() {
        KnowledgeManifest m = KcpParser.fromMap(minimalWith("kcp_version", "0.2"));
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("kcp_version")));
    }

    @Test
    void unknownKcpVersionProducesWarning() {
        KnowledgeManifest m = KcpParser.fromMap(minimalWith("kcp_version", "99.0"));
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("kcp_version") && w.contains("99.0")));
    }

    @Test
    void isValidReturnsFalseWhenErrors() {
        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("project", "");
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertFalse(result.isValid());
    }

    @Test
    void rejectsEmptyUnits() {
        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("units", List.of());
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("units")));
    }

    @Test
    void rejectsMissingProject() {
        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("project", "");
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("project")));
    }

    @Test
    void rejectsMissingPath() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0",
                "units", List.of(Map.of(
                        "id", "u1", "path", "",
                        "intent", "test", "scope", "global",
                        "audience", List.of("agent")
                ))
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("path")));
    }

    @Test
    void rejectsMissingIntent() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0",
                "units", List.of(Map.of(
                        "id", "u1", "path", "f.md",
                        "intent", "", "scope", "global",
                        "audience", List.of("agent")
                ))
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("intent")));
    }

    @Test
    void rejectsInvalidScope() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0",
                "units", List.of(Map.of(
                        "id", "u1", "path", "f.md",
                        "intent", "test", "scope", "invalid",
                        "audience", List.of("agent")
                ))
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("scope")));
    }

    @Test
    void unknownAudienceProducesWarning() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0",
                "units", List.of(Map.of(
                        "id", "u1", "path", "f.md",
                        "intent", "test", "scope", "global",
                        "audience", List.of("agent", "martian")
                ))
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("martian")));
    }

    @Test
    void unknownDependsOnProducesWarning() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0",
                "units", List.of(Map.of(
                        "id", "u1", "path", "f.md",
                        "intent", "test", "scope", "global",
                        "audience", List.of("agent"),
                        "depends_on", List.of("ghost")
                ))
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("ghost")));
    }

    @Test
    void unknownRelationshipUnitProducesWarning() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0",
                "units", List.of(
                        Map.of("id", "a", "path", "a.md", "intent", "A", "scope", "global", "audience", List.of("agent"))
                ),
                "relationships", List.of(Map.of("from", "ghost", "to", "a", "type", "enables"))
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("ghost")));
    }

    @Test
    void invalidRelationshipTypeProducesWarning() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0",
                "units", List.of(
                        Map.of("id", "a", "path", "a.md", "intent", "A", "scope", "global", "audience", List.of("agent")),
                        Map.of("id", "b", "path", "b.md", "intent", "B", "scope", "global", "audience", List.of("agent"))
                ),
                "relationships", List.of(Map.of("from", "a", "to", "b", "type", "invented"))
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("type")));
    }

    // -----------------------------------------------------------------------
    // v0.3 field validation tests
    // -----------------------------------------------------------------------

    @Test
    void unknownKindProducesWarning() {
        Map<String, Object> unitData = new HashMap<>(Map.of(
                "id", "u1", "path", "f.md", "intent", "test", "scope", "global",
                "audience", List.of("agent"), "kind", "imaginary"
        ));
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(unitData)
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("kind") && w.contains("imaginary")));
    }

    @Test
    void validKindsProduceNoWarning() {
        for (String kind : List.of("knowledge", "schema", "service", "policy", "executable")) {
            Map<String, Object> unitData = new HashMap<>(Map.of(
                    "id", "u1", "path", "f.md", "intent", "test", "scope", "global",
                    "audience", List.of("agent"), "kind", kind
            ));
            Map<String, Object> data = Map.of(
                    "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                    "units", List.of(unitData)
            );
            KnowledgeManifest m = KcpParser.fromMap(data);
            KcpValidator.ValidationResult result = KcpValidator.validate(m);
            assertTrue(result.warnings().stream().noneMatch(w -> w.contains("kind")),
                    "kind '" + kind + "' should be valid");
        }
    }

    @Test
    void unknownFormatProducesWarning() {
        Map<String, Object> unitData = new HashMap<>(Map.of(
                "id", "u1", "path", "f.md", "intent", "test", "scope", "global",
                "audience", List.of("agent"), "format", "docx"
        ));
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(unitData)
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("format") && w.contains("docx")));
    }

    @Test
    void validFormatsProduceNoWarning() {
        for (String fmt : List.of("markdown", "pdf", "openapi", "json-schema", "jupyter", "html", "text")) {
            Map<String, Object> unitData = new HashMap<>(Map.of(
                    "id", "u1", "path", "f.md", "intent", "test", "scope", "global",
                    "audience", List.of("agent"), "format", fmt
            ));
            Map<String, Object> data = Map.of(
                    "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                    "units", List.of(unitData)
            );
            KnowledgeManifest m = KcpParser.fromMap(data);
            KcpValidator.ValidationResult result = KcpValidator.validate(m);
            assertTrue(result.warnings().stream().noneMatch(w -> w.contains("format")),
                    "format '" + fmt + "' should be valid");
        }
    }

    @Test
    void unknownUpdateFrequencyProducesWarning() {
        Map<String, Object> unitData = new HashMap<>(Map.of(
                "id", "u1", "path", "f.md", "intent", "test", "scope", "global",
                "audience", List.of("agent"), "update_frequency", "biweekly"
        ));
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(unitData)
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("update_frequency") && w.contains("biweekly")));
    }

    @Test
    void unknownIndexingShorthandProducesWarning() {
        Map<String, Object> unitData = new HashMap<>(Map.of(
                "id", "u1", "path", "f.md", "intent", "test", "scope", "global",
                "audience", List.of("agent"), "indexing", "custom-unknown"
        ));
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(unitData)
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("indexing") && w.contains("custom-unknown")));
    }

    @Test
    void indexingObjectNoWarning() {
        Map<String, Object> indexingObj = Map.of("allow", List.of("read", "index"), "deny", List.of("train"));
        Map<String, Object> unitData = new HashMap<>(Map.of(
                "id", "u1", "path", "f.md", "intent", "test", "scope", "global",
                "audience", List.of("agent"), "indexing", indexingObj
        ));
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(unitData)
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("indexing")));
    }

    // -----------------------------------------------------------------------
    // Duplicate ID, ID format, and trigger constraint tests
    // -----------------------------------------------------------------------

    @Test
    void duplicateIdProducesWarning() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(
                        Map.of("id", "dup", "path", "a.md", "intent", "A", "scope", "global", "audience", List.of("agent")),
                        Map.of("id", "dup", "path", "b.md", "intent", "B", "scope", "global", "audience", List.of("agent"))
                )
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("duplicate")));
    }

    @Test
    void invalidIdFormatProducesWarning() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(
                        Map.of("id", "Has_Uppercase!", "path", "a.md", "intent", "A", "scope", "global", "audience", List.of("agent"))
                )
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("lowercase")));
    }

    @Test
    void validIdFormatsProduceNoWarning() {
        for (String validId : List.of("overview", "my-unit", "v2.0", "a.b-c.1")) {
            Map<String, Object> data = Map.of(
                    "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                    "units", List.of(
                            Map.of("id", validId, "path", "a.md", "intent", "A", "scope", "global", "audience", List.of("agent"))
                    )
            );
            KnowledgeManifest m = KcpParser.fromMap(data);
            KcpValidator.ValidationResult result = KcpValidator.validate(m);
            assertTrue(result.warnings().stream().noneMatch(w -> w.contains("lowercase")),
                    "ID '" + validId + "' should be valid");
        }
    }

    @Test
    void triggerExceeding60CharsProducesWarning() {
        String longTrigger = "a".repeat(61);
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(
                        Map.of("id", "a", "path", "a.md", "intent", "A", "scope", "global",
                                "audience", List.of("agent"), "triggers", List.of(longTrigger))
                )
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("exceeds")));
    }

    @Test
    void moreThan20TriggersProducesWarning() {
        List<String> triggers = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) triggers.add("t" + i);
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(
                        Map.of("id", "a", "path", "a.md", "intent", "A", "scope", "global",
                                "audience", List.of("agent"), "triggers", triggers)
                )
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("more than 20")));
    }

    // -----------------------------------------------------------------------
    // Cycle detection tests (§4.7)
    // -----------------------------------------------------------------------

    @Test
    void noCycleReturnsEmpty() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(
                        Map.of("id", "a", "path", "a.md", "intent", "A", "scope", "global", "audience", List.of("agent")),
                        Map.of("id", "b", "path", "b.md", "intent", "B", "scope", "global", "audience", List.of("agent"), "depends_on", List.of("a")),
                        Map.of("id", "c", "path", "c.md", "intent", "C", "scope", "global", "audience", List.of("agent"), "depends_on", List.of("b"))
                )
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        java.util.Set<String> unitIds = m.units().stream().map(KnowledgeUnit::id).collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> cycles = KcpValidator.detectCycles(m.units(), unitIds);
        assertTrue(cycles.isEmpty());
    }

    @Test
    void simpleTwoNodeCycleDetected() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(
                        Map.of("id", "a", "path", "a.md", "intent", "A", "scope", "global", "audience", List.of("agent"), "depends_on", List.of("b")),
                        Map.of("id", "b", "path", "b.md", "intent", "B", "scope", "global", "audience", List.of("agent"), "depends_on", List.of("a"))
                )
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        java.util.Set<String> unitIds = m.units().stream().map(KnowledgeUnit::id).collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> cycles = KcpValidator.detectCycles(m.units(), unitIds);
        assertEquals(1, cycles.size());
    }

    @Test
    void threeNodeCycleDetected() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(
                        Map.of("id", "a", "path", "a.md", "intent", "A", "scope", "global", "audience", List.of("agent"), "depends_on", List.of("b")),
                        Map.of("id", "b", "path", "b.md", "intent", "B", "scope", "global", "audience", List.of("agent"), "depends_on", List.of("c")),
                        Map.of("id", "c", "path", "c.md", "intent", "C", "scope", "global", "audience", List.of("agent"), "depends_on", List.of("a"))
                )
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        java.util.Set<String> unitIds = m.units().stream().map(KnowledgeUnit::id).collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> cycles = KcpValidator.detectCycles(m.units(), unitIds);
        assertFalse(cycles.isEmpty());
    }

    @Test
    void selfCycleDetected() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(
                        Map.of("id", "a", "path", "a.md", "intent", "A", "scope", "global", "audience", List.of("agent"), "depends_on", List.of("a"))
                )
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        java.util.Set<String> unitIds = m.units().stream().map(KnowledgeUnit::id).collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> cycles = KcpValidator.detectCycles(m.units(), unitIds);
        assertTrue(cycles.contains("a->a"));
    }

    @Test
    void cycleDoesNotCauseValidationError() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(
                        Map.of("id", "a", "path", "a.md", "intent", "A", "scope", "global", "audience", List.of("agent"), "depends_on", List.of("b")),
                        Map.of("id", "b", "path", "b.md", "intent", "B", "scope", "global", "audience", List.of("agent"), "depends_on", List.of("a"))
                )
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void cycleMixedWithNonCyclicUnitsValidates() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(
                        Map.of("id", "root", "path", "root.md", "intent", "Root", "scope", "global", "audience", List.of("agent")),
                        Map.of("id", "a", "path", "a.md", "intent", "A", "scope", "global", "audience", List.of("agent"), "depends_on", List.of("root", "b")),
                        Map.of("id", "b", "path", "b.md", "intent", "B", "scope", "global", "audience", List.of("agent"), "depends_on", List.of("a"))
                )
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid());
    }

    // -----------------------------------------------------------------------
    // Path existence checking tests (§4.3 / §7)
    // -----------------------------------------------------------------------

    @Test
    void existingPathNoWarning(@TempDir Path tmpDir) throws IOException {
        Files.writeString(tmpDir.resolve("README.md"), "# Test");
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(
                        Map.of("id", "overview", "path", "README.md", "intent", "What?",
                                "scope", "global", "audience", List.of("agent"))
                )
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m, tmpDir);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("does not exist")));
    }

    @Test
    void missingPathProducesWarning(@TempDir Path tmpDir) {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(
                        Map.of("id", "missing", "path", "nonexistent.md", "intent", "What?",
                                "scope", "global", "audience", List.of("agent"))
                )
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m, tmpDir);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("does not exist")));
    }

    @Test
    void missingNestedPathProducesWarning(@TempDir Path tmpDir) {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(
                        Map.of("id", "guide", "path", "docs/guide.md", "intent", "How?",
                                "scope", "global", "audience", List.of("agent"))
                )
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m, tmpDir);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("does not exist")));
    }

    @Test
    void noPathCheckWithoutManifestDir() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(
                        Map.of("id", "missing", "path", "definitely-gone.md", "intent", "What?",
                                "scope", "global", "audience", List.of("agent"))
                )
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("does not exist")));
    }

    @Test
    void mixedExistenceOnlyMissingGetsWarning(@TempDir Path tmpDir) throws IOException {
        Files.writeString(tmpDir.resolve("exists.md"), "# Exists");
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.3",
                "units", List.of(
                        Map.of("id", "exists", "path", "exists.md", "intent", "A",
                                "scope", "global", "audience", List.of("agent")),
                        Map.of("id", "missing", "path", "missing.md", "intent", "B",
                                "scope", "global", "audience", List.of("agent"))
                )
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m, tmpDir);
        assertTrue(result.isValid());
        List<String> pathWarnings = result.warnings().stream()
                .filter(w -> w.contains("does not exist"))
                .toList();
        assertEquals(1, pathWarnings.size());
        assertTrue(pathWarnings.get(0).contains("missing.md"));
    }

    // -----------------------------------------------------------------------
    // v0.7 Delegation parsing tests
    // -----------------------------------------------------------------------

    @Test
    void parsesRootDelegation() {
        Map<String, Object> delegationMap = new HashMap<>(Map.of(
                "max_depth", 2,
                "require_capability_attenuation", true,
                "audit_chain", false,
                "human_in_the_loop", Map.of("required", true, "approval_mechanism", "oauth_consent")
        ));
        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("delegation", delegationMap);
        KnowledgeManifest m = KcpParser.fromMap(data);
        assertNotNull(m.delegation());
        assertEquals(2, m.delegation().maxDepth());
        assertTrue(m.delegation().requireCapabilityAttenuation());
        assertFalse(m.delegation().auditChain());
        assertNotNull(m.delegation().humanInTheLoop());
        assertTrue(m.delegation().humanInTheLoop().required());
        assertEquals("oauth_consent", m.delegation().humanInTheLoop().approvalMechanism());
    }

    @Test
    void parsesUnitDelegationOverride() {
        Map<String, Object> unitDelegation = new HashMap<>(Map.of(
                "max_depth", 0,
                "human_in_the_loop", Map.of("required", false, "approval_mechanism", "uma")
        ));
        Map<String, Object> unitData = new HashMap<>(Map.of(
                "id", "u1", "path", "f.md", "intent", "test", "scope", "global",
                "audience", List.of("agent"), "delegation", unitDelegation
        ));
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.7",
                "units", List.of(unitData)
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KnowledgeUnit u = m.units().get(0);
        assertNotNull(u.delegation());
        assertEquals(0, u.delegation().maxDepth());
        assertNotNull(u.delegation().humanInTheLoop());
        assertFalse(u.delegation().humanInTheLoop().required());
        assertEquals("uma", u.delegation().humanInTheLoop().approvalMechanism());
        assertNull(u.delegation().requireCapabilityAttenuation());
    }

    @Test
    void absentDelegationIsNull() {
        KnowledgeManifest m = KcpParser.fromMap(MINIMAL);
        assertNull(m.delegation());
        assertNull(m.units().get(0).delegation());
    }

    // -----------------------------------------------------------------------
    // v0.7 Compliance parsing tests
    // -----------------------------------------------------------------------

    @Test
    void parsesRootCompliance() {
        Map<String, Object> complianceMap = new HashMap<>(Map.of(
                "data_residency", List.of("EU", "NO"),
                "sensitivity", "confidential",
                "regulations", List.of("GDPR", "NIS2"),
                "restrictions", List.of("no_ai_training", "no_cross_border")
        ));
        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("compliance", complianceMap);
        KnowledgeManifest m = KcpParser.fromMap(data);
        assertNotNull(m.compliance());
        assertEquals(List.of("EU", "NO"), m.compliance().dataResidency());
        assertEquals("confidential", m.compliance().sensitivity());
        assertEquals(List.of("GDPR", "NIS2"), m.compliance().regulations());
        assertEquals(List.of("no_ai_training", "no_cross_border"), m.compliance().restrictions());
    }

    @Test
    void parsesUnitComplianceOverride() {
        Map<String, Object> unitCompliance = new HashMap<>(Map.of(
                "sensitivity", "restricted",
                "regulations", List.of("AML5D")
        ));
        Map<String, Object> unitData = new HashMap<>(Map.of(
                "id", "u1", "path", "f.md", "intent", "test", "scope", "global",
                "audience", List.of("agent"), "compliance", unitCompliance
        ));
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.7",
                "units", List.of(unitData)
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KnowledgeUnit u = m.units().get(0);
        assertNotNull(u.compliance());
        assertEquals("restricted", u.compliance().sensitivity());
        assertEquals(List.of("AML5D"), u.compliance().regulations());
        assertTrue(u.compliance().dataResidency().isEmpty());
        assertTrue(u.compliance().restrictions().isEmpty());
    }

    @Test
    void absentComplianceIsNull() {
        KnowledgeManifest m = KcpParser.fromMap(MINIMAL);
        assertNull(m.compliance());
        assertNull(m.units().get(0).compliance());
    }

    // -----------------------------------------------------------------------
    // Trust block parsing tests (#10)
    // -----------------------------------------------------------------------

    @Test
    void parsesRootTrust() {
        Map<String, Object> trustMap = Map.of(
                "provenance", Map.of(
                        "publisher", "Acme Corp",
                        "publisher_url", "https://acme.com",
                        "contact", "docs@acme.com"
                ),
                "audit", Map.of(
                        "agent_must_log", true,
                        "require_trace_context", false
                )
        );
        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("trust", trustMap);
        KnowledgeManifest m = KcpParser.fromMap(data);
        assertNotNull(m.trust());
        assertNotNull(m.trust().provenance());
        assertEquals("Acme Corp", m.trust().provenance().publisher());
        assertEquals("https://acme.com", m.trust().provenance().publisherUrl());
        assertEquals("docs@acme.com", m.trust().provenance().contact());
        assertNotNull(m.trust().audit());
        assertTrue(m.trust().audit().agentMustLog());
        assertFalse(m.trust().audit().requireTraceContext());
    }

    @Test
    void absentTrustIsNull() {
        KnowledgeManifest m = KcpParser.fromMap(MINIMAL);
        assertNull(m.trust());
    }

    // -----------------------------------------------------------------------
    // Auth block parsing tests (#10)
    // -----------------------------------------------------------------------

    @Test
    void parsesRootAuth() {
        Map<String, Object> authMap = Map.of(
                "methods", List.of(
                        Map.of("type", "oauth2", "issuer", "https://auth.example.com", "scopes", List.of("read:docs")),
                        Map.of("type", "api_key", "header", "X-API-Key", "registration_url", "https://example.com/register"),
                        Map.of("type", "none")
                )
        );
        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("auth", authMap);
        KnowledgeManifest m = KcpParser.fromMap(data);
        assertNotNull(m.auth());
        assertEquals(3, m.auth().methods().size());
        assertEquals("oauth2", m.auth().methods().get(0).type());
        assertEquals("https://auth.example.com", m.auth().methods().get(0).issuer());
        assertEquals(List.of("read:docs"), m.auth().methods().get(0).scopes());
        assertEquals("api_key", m.auth().methods().get(1).type());
        assertEquals("X-API-Key", m.auth().methods().get(1).header());
        assertEquals("none", m.auth().methods().get(2).type());
    }

    @Test
    void absentAuthIsNull() {
        KnowledgeManifest m = KcpParser.fromMap(MINIMAL);
        assertNull(m.auth());
    }

    @Test
    void warnsWhenProtectedUnitsButNoAuth() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.7",
                "units", List.of(Map.of(
                        "id", "u1", "path", "f.md", "intent", "test", "scope", "global",
                        "audience", List.of("agent"), "access", "restricted"
                ))
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("auth")));
    }

    // -----------------------------------------------------------------------
    // Hints block parsing tests (#10)
    // -----------------------------------------------------------------------

    @Test
    void parsesUnitHints() {
        Map<String, Object> hintsMap = new HashMap<>(Map.of(
                "token_estimate", 5000,
                "load_strategy", "lazy",
                "priority", "critical",
                "density", "dense",
                "summary_available", true,
                "summary_unit", "overview-tldr"
        ));
        Map<String, Object> unitData = new HashMap<>(Map.of(
                "id", "u1", "path", "f.md", "intent", "test", "scope", "global",
                "audience", List.of("agent"), "hints", hintsMap
        ));
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0",
                "units", List.of(unitData)
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        assertNotNull(m.units().get(0).hints());
        assertInstanceOf(Map.class, m.units().get(0).hints());
        @SuppressWarnings("unchecked")
        Map<String, Object> hints = (Map<String, Object>) m.units().get(0).hints();
        assertEquals(5000, hints.get("token_estimate"));
        assertEquals("lazy", hints.get("load_strategy"));
        assertEquals(true, hints.get("summary_available"));
    }

    @Test
    void parsesRootHints() {
        Map<String, Object> hintsMap = Map.of(
                "total_token_estimate", 50000,
                "unit_count", 5,
                "recommended_entry_point", "overview"
        );
        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("hints", hintsMap);
        KnowledgeManifest m = KcpParser.fromMap(data);
        assertNotNull(m.hints());
        assertInstanceOf(Map.class, m.hints());
    }

    // -----------------------------------------------------------------------
    // Payment block parsing tests (#10)
    // -----------------------------------------------------------------------

    @Test
    void parsesRootPayment() {
        Map<String, Object> paymentMap = Map.of("default_tier", "free");
        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("payment", paymentMap);
        KnowledgeManifest m = KcpParser.fromMap(data);
        assertNotNull(m.payment());
        assertInstanceOf(Map.class, m.payment());
    }

    @Test
    void parsesUnitPayment() {
        Map<String, Object> paymentMap = Map.of("default_tier", "metered");
        Map<String, Object> unitData = new HashMap<>(Map.of(
                "id", "u1", "path", "f.md", "intent", "test", "scope", "global",
                "audience", List.of("agent"), "payment", paymentMap
        ));
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0",
                "units", List.of(unitData)
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        assertNotNull(m.units().get(0).payment());
    }

    @Test
    void absentPaymentIsNull() {
        KnowledgeManifest m = KcpParser.fromMap(MINIMAL);
        assertNull(m.payment());
    }

    // -----------------------------------------------------------------------
    // Delegation/compliance validation tests (#9)
    // -----------------------------------------------------------------------

    @Test
    void invalidHitlValueProducesError() {
        Map<String, Object> delegationMap = Map.of(
                "human_in_the_loop", Map.of("required", true, "approval_mechanism", "invalid-value"));
        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("delegation", delegationMap);
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("human_in_the_loop")));
    }

    @Test
    void validHitlValuesAccepted() {
        for (String mech : List.of("oauth_consent", "uma", "custom")) {
            Map<String, Object> delegationMap = Map.of(
                    "human_in_the_loop", Map.of("required", true, "approval_mechanism", mech));
            Map<String, Object> data = new HashMap<>(MINIMAL);
            data.put("delegation", delegationMap);
            KnowledgeManifest m = KcpParser.fromMap(data);
            KcpValidator.ValidationResult result = KcpValidator.validate(m);
            assertTrue(result.errors().stream().noneMatch(e -> e.contains("human_in_the_loop")),
                    "human_in_the_loop approval_mechanism '" + mech + "' should be valid");
        }
    }

    @Test
    void unitMaxDepthExceedingRootProducesError() {
        Map<String, Object> rootDelegation = Map.of("max_depth", 2);
        Map<String, Object> unitDelegation = Map.of("max_depth", 5);
        Map<String, Object> unitData = new HashMap<>(Map.of(
                "id", "u1", "path", "f.md", "intent", "test", "scope", "global",
                "audience", List.of("agent"), "delegation", unitDelegation
        ));
        Map<String, Object> data = new HashMap<>(Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.7",
                "units", List.of(unitData)
        ));
        data.put("delegation", rootDelegation);
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("max_depth")));
    }

    @Test
    void invalidComplianceSensitivityProducesError() {
        Map<String, Object> complianceMap = Map.of("sensitivity", "top-secret");
        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("compliance", complianceMap);
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("compliance.sensitivity")));
    }

    // -----------------------------------------------------------------------
    // Hints validation tests (#11)
    // -----------------------------------------------------------------------

    @Test
    void summaryAvailableWithoutSummaryUnitWarns() {
        Map<String, Object> hintsMap = Map.of("summary_available", true);
        Map<String, Object> unitData = new HashMap<>(Map.of(
                "id", "u1", "path", "f.md", "intent", "test", "scope", "global",
                "audience", List.of("agent"), "hints", hintsMap
        ));
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.7",
                "units", List.of(unitData)
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("summary_available") && w.contains("summary_unit")));
    }

    @Test
    void chunkIndexWithoutChunkOfWarns() {
        Map<String, Object> hintsMap = Map.of("chunk_index", 1);
        Map<String, Object> unitData = new HashMap<>(Map.of(
                "id", "u1", "path", "f.md", "intent", "test", "scope", "global",
                "audience", List.of("agent"), "hints", hintsMap
        ));
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.7",
                "units", List.of(unitData)
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("chunk_index") && w.contains("chunk_of")));
    }

    // -----------------------------------------------------------------------
    // Federation tests (§3.6, v0.9)
    // -----------------------------------------------------------------------

    @Test
    void parsesManifestsBlock() {
        Map<String, Object> manifestRef = new HashMap<>();
        manifestRef.put("id", "platform");
        manifestRef.put("url", "https://platform.example.com/knowledge.yaml");
        manifestRef.put("label", "Platform Engineering");
        manifestRef.put("relationship", "foundation");
        manifestRef.put("update_frequency", "weekly");
        manifestRef.put("local_mirror", "./mirrors/platform.yaml");

        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("kcp_version", "0.9");
        data.put("manifests", List.of(manifestRef));
        KnowledgeManifest m = KcpParser.fromMap(data);

        assertEquals(1, m.manifests().size());
        assertEquals("platform", m.manifests().get(0).id());
        assertEquals("https://platform.example.com/knowledge.yaml", m.manifests().get(0).url());
        assertEquals("Platform Engineering", m.manifests().get(0).label());
        assertEquals("foundation", m.manifests().get(0).relationship());
        assertEquals("weekly", m.manifests().get(0).updateFrequency());
        assertEquals("./mirrors/platform.yaml", m.manifests().get(0).localMirror());
    }

    @Test
    void parsesManifestRefWithAuth() {
        Map<String, Object> manifestRef = new HashMap<>();
        manifestRef.put("id", "secure");
        manifestRef.put("url", "https://secure.example.com/knowledge.yaml");
        manifestRef.put("auth", Map.of("methods", List.of(
                Map.of("type", "api_key", "header", "X-KCP-Key")
        )));

        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("kcp_version", "0.9");
        data.put("manifests", List.of(manifestRef));
        KnowledgeManifest m = KcpParser.fromMap(data);

        assertNotNull(m.manifests().get(0).auth());
        assertEquals(1, m.manifests().get(0).auth().methods().size());
        assertEquals("api_key", m.manifests().get(0).auth().methods().get(0).type());
    }

    @Test
    void absentManifestsBlockIsEmptyList() {
        KnowledgeManifest m = KcpParser.fromMap(MINIMAL);
        assertNotNull(m.manifests());
        assertTrue(m.manifests().isEmpty());
    }

    @Test
    void parsesExternalDependsOn() {
        Map<String, Object> extDep = Map.of(
                "manifest", "security",
                "unit", "gdpr-policy",
                "on_failure", "degrade"
        );
        Map<String, Object> unitData = new HashMap<>(Map.of(
                "id", "data-handling", "path", "data.md", "intent", "Data handling",
                "scope", "global", "audience", List.of("agent"),
                "external_depends_on", List.of(extDep)
        ));
        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("units", List.of(unitData));
        KnowledgeManifest m = KcpParser.fromMap(data);

        assertEquals(1, m.units().get(0).externalDependsOn().size());
        assertEquals("security", m.units().get(0).externalDependsOn().get(0).manifest());
        assertEquals("gdpr-policy", m.units().get(0).externalDependsOn().get(0).unit());
        assertEquals("degrade", m.units().get(0).externalDependsOn().get(0).onFailure());
    }

    @Test
    void externalDependsOnDefaultsOnFailureToSkip() {
        Map<String, Object> extDep = Map.of(
                "manifest", "platform",
                "unit", "api-guide"
        );
        Map<String, Object> unitData = new HashMap<>(Map.of(
                "id", "u1", "path", "f.md", "intent", "test",
                "scope", "global", "audience", List.of("agent"),
                "external_depends_on", List.of(extDep)
        ));
        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("units", List.of(unitData));
        KnowledgeManifest m = KcpParser.fromMap(data);

        assertEquals("skip", m.units().get(0).externalDependsOn().get(0).onFailure());
    }

    @Test
    void absentExternalDependsOnIsEmptyList() {
        KnowledgeManifest m = KcpParser.fromMap(MINIMAL);
        assertNotNull(m.units().get(0).externalDependsOn());
        assertTrue(m.units().get(0).externalDependsOn().isEmpty());
    }

    @Test
    void parsesExternalRelationships() {
        Map<String, Object> extRel = new HashMap<>();
        extRel.put("from_manifest", "security");
        extRel.put("from_unit", "gdpr-policy");
        extRel.put("to_unit", "data-handling");
        extRel.put("type", "governs");

        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("kcp_version", "0.9");
        data.put("external_relationships", List.of(extRel));
        KnowledgeManifest m = KcpParser.fromMap(data);

        assertEquals(1, m.externalRelationships().size());
        assertEquals("security", m.externalRelationships().get(0).fromManifest());
        assertEquals("gdpr-policy", m.externalRelationships().get(0).fromUnit());
        assertNull(m.externalRelationships().get(0).toManifest());
        assertEquals("data-handling", m.externalRelationships().get(0).toUnit());
        assertEquals("governs", m.externalRelationships().get(0).type());
    }

    @Test
    void absentExternalRelationshipsIsEmptyList() {
        KnowledgeManifest m = KcpParser.fromMap(MINIMAL);
        assertNotNull(m.externalRelationships());
        assertTrue(m.externalRelationships().isEmpty());
    }

    @Test
    void governsRelationshipTypeIsValid() {
        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("kcp_version", "0.9");
        data.put("relationships", List.of(Map.of(
                "from", "overview", "to", "overview", "type", "governs"
        )));
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("governs")));
    }

    @Test
    void kcpVersion09IsRecognised() {
        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("kcp_version", "0.9");
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("unknown kcp_version")));
    }

    @Test
    void manifestIdMustMatchPattern() {
        Map<String, Object> manifestRef = new HashMap<>();
        manifestRef.put("id", "INVALID ID!");
        manifestRef.put("url", "https://example.com/knowledge.yaml");

        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("manifests", List.of(manifestRef));
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("id") && e.contains("INVALID ID!")));
    }

    @Test
    void manifestUrlMustBeHttps() {
        Map<String, Object> manifestRef = new HashMap<>();
        manifestRef.put("id", "platform");
        manifestRef.put("url", "http://insecure.example.com/knowledge.yaml");

        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("manifests", List.of(manifestRef));
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("HTTPS")));
    }

    @Test
    void duplicateManifestIdProducesError() {
        Map<String, Object> ref1 = new HashMap<>();
        ref1.put("id", "platform");
        ref1.put("url", "https://a.example.com/knowledge.yaml");
        Map<String, Object> ref2 = new HashMap<>();
        ref2.put("id", "platform");
        ref2.put("url", "https://b.example.com/knowledge.yaml");

        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("manifests", List.of(ref1, ref2));
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("duplicate")));
    }

    @Test
    void externalDependsOnUnknownManifestWarns() {
        Map<String, Object> extDep = Map.of(
                "manifest", "nonexistent",
                "unit", "some-unit"
        );
        Map<String, Object> unitData = new HashMap<>(Map.of(
                "id", "u1", "path", "f.md", "intent", "test",
                "scope", "global", "audience", List.of("agent"),
                "external_depends_on", List.of(extDep)
        ));
        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("units", List.of(unitData));
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("nonexistent")));
    }

    @Test
    void externalRelationshipsUnknownManifestWarns() {
        Map<String, Object> extRel = new HashMap<>();
        extRel.put("from_manifest", "unknown-src");
        extRel.put("from_unit", "a");
        extRel.put("to_unit", "b");
        extRel.put("type", "governs");

        Map<String, Object> data = new HashMap<>(MINIMAL);
        data.put("external_relationships", List.of(extRel));
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("unknown-src")));
    }

    @Test
    void fullFederationRoundTrip() {
        Map<String, Object> manifestRef = new HashMap<>();
        manifestRef.put("id", "security");
        manifestRef.put("url", "https://security.example.com/knowledge.yaml");
        manifestRef.put("label", "Security Team");
        manifestRef.put("relationship", "governs");

        Map<String, Object> extDep = Map.of(
                "manifest", "security",
                "unit", "gdpr-policy",
                "on_failure", "warn"
        );
        Map<String, Object> unitData = new HashMap<>(Map.of(
                "id", "data-handling", "path", "data.md", "intent", "Data handling",
                "scope", "global", "audience", List.of("agent"),
                "external_depends_on", List.of(extDep)
        ));

        Map<String, Object> extRel = new HashMap<>();
        extRel.put("from_manifest", "security");
        extRel.put("from_unit", "gdpr-policy");
        extRel.put("to_unit", "data-handling");
        extRel.put("type", "governs");

        Map<String, Object> data = new HashMap<>();
        data.put("kcp_version", "0.9");
        data.put("project", "federation-test");
        data.put("version", "1.0.0");
        data.put("manifests", List.of(manifestRef));
        data.put("units", List.of(unitData));
        data.put("external_relationships", List.of(extRel));

        KnowledgeManifest m = KcpParser.fromMap(data);

        assertEquals("0.9", m.kcpVersion());
        assertEquals(1, m.manifests().size());
        assertEquals("security", m.manifests().get(0).id());
        assertEquals(1, m.units().get(0).externalDependsOn().size());
        assertEquals("warn", m.units().get(0).externalDependsOn().get(0).onFailure());
        assertEquals(1, m.externalRelationships().size());
        assertEquals("governs", m.externalRelationships().get(0).type());

        // Validate — should have no errors
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid(), "Expected valid: " + result.errors());
    }
}
