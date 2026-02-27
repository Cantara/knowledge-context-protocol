package no.cantara.kcp;

import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;
import org.junit.jupiter.api.Test;

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

    private static final Map<String, Object> COMPLETE = Map.of(
            "project", "wiki.example.org",
            "version", "1.0.0",
            "kcp_version", "0.1",
            "updated", "2026-02-25",
            "units", List.of(
                    Map.of("id", "about", "path", "about.md",
                            "intent", "Who maintains this?",
                            "scope", "global", "audience", List.of("human", "agent"),
                            "validated", "2026-02-24"),
                    Map.of("id", "methodology", "path", "methodology/overview.md",
                            "intent", "What methodology is used?",
                            "scope", "global", "audience", List.of("developer", "agent"),
                            "depends_on", List.of("about"),
                            "triggers", List.of("methodology", "productivity")),
                    Map.of("id", "knowledge-infra", "path", "tools/knowledge-infra.md",
                            "intent", "How is knowledge infrastructure set up?",
                            "scope", "global", "audience", List.of("developer", "agent"),
                            "depends_on", List.of("methodology"))
            ),
            "relationships", List.of(
                    Map.of("from", "methodology", "to", "knowledge-infra", "type", "enables"),
                    Map.of("from", "about", "to", "methodology", "type", "context")
            )
    );

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
        KnowledgeManifest m = KcpParser.fromMap(minimalWith("kcp_version", "0.1"));
        assertEquals("0.1", m.kcpVersion());
    }

    @Test
    void parsesCompleteManifest() {
        KnowledgeManifest m = KcpParser.fromMap(COMPLETE);
        assertEquals("wiki.example.org", m.project());
        assertEquals("0.1", m.kcpVersion());
        assertEquals(LocalDate.of(2026, 2, 25), m.updated());
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
        KnowledgeManifest m = KcpParser.fromMap(MINIMAL); // no kcp_version
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("kcp_version")));
    }

    @Test
    void knownKcpVersionNoWarning() {
        KnowledgeManifest m = KcpParser.fromMap(minimalWith("kcp_version", "0.1"));
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("kcp_version")));
    }

    @Test
    void unknownKcpVersionProducesWarning() {
        KnowledgeManifest m = KcpParser.fromMap(minimalWith("kcp_version", "99.0"));
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid()); // warning, not error
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
        assertTrue(result.isValid()); // warning, not error
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
        assertTrue(result.isValid()); // warning, not error
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
        assertTrue(result.isValid()); // warning, not error
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
        assertTrue(result.isValid()); // warning, not error
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("type")));
    }

    // -----------------------------------------------------------------------
    // Duplicate ID, ID format, and trigger constraint tests
    // -----------------------------------------------------------------------

    @Test
    void duplicateIdProducesWarning() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.1",
                "units", List.of(
                        Map.of("id", "dup", "path", "a.md", "intent", "A", "scope", "global", "audience", List.of("agent")),
                        Map.of("id", "dup", "path", "b.md", "intent", "B", "scope", "global", "audience", List.of("agent"))
                )
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.isValid()); // warning, not error
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("duplicate")));
    }

    @Test
    void invalidIdFormatProducesWarning() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0", "kcp_version", "0.1",
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
                    "project", "test", "version", "1.0.0", "kcp_version", "0.1",
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
                "project", "test", "version", "1.0.0", "kcp_version", "0.1",
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
                "project", "test", "version", "1.0.0", "kcp_version", "0.1",
                "units", List.of(
                        Map.of("id", "a", "path", "a.md", "intent", "A", "scope", "global",
                                "audience", List.of("agent"), "triggers", triggers)
                )
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        KcpValidator.ValidationResult result = KcpValidator.validate(m);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("more than 20")));
    }
}
