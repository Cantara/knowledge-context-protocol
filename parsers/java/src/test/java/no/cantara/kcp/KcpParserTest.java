package no.cantara.kcp;

import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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

    private static final Map<String, Object> COMPLETE = Map.of(
            "project", "wiki.example.org",
            "version", "1.0.0",
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

    @Test
    void parsesMinimalManifest() {
        KnowledgeManifest m = KcpParser.fromMap(MINIMAL);
        assertEquals("test-project", m.project());
        assertEquals("1.0.0", m.version());
        assertEquals(1, m.units().size());
        assertEquals("overview", m.units().get(0).id());
    }

    @Test
    void parsesCompleteManifest() {
        KnowledgeManifest m = KcpParser.fromMap(COMPLETE);
        assertEquals("wiki.example.org", m.project());
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
    void validatesValidMinimal() {
        KnowledgeManifest m = KcpParser.fromMap(MINIMAL);
        assertTrue(KcpValidator.validate(m).isEmpty());
    }

    @Test
    void validatesValidComplete() {
        KnowledgeManifest m = KcpParser.fromMap(COMPLETE);
        assertTrue(KcpValidator.validate(m).isEmpty());
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
        List<String> errors = KcpValidator.validate(m);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("scope")));
    }

    @Test
    void rejectsUnknownDependsOn() {
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
        List<String> errors = KcpValidator.validate(m);
        assertTrue(errors.stream().anyMatch(e -> e.contains("ghost")));
    }

    @Test
    void rejectsInvalidRelationshipType() {
        Map<String, Object> data = Map.of(
                "project", "test", "version", "1.0.0",
                "units", List.of(
                        Map.of("id", "a", "path", "a.md", "intent", "A", "scope", "global", "audience", List.of("agent")),
                        Map.of("id", "b", "path", "b.md", "intent", "B", "scope", "global", "audience", List.of("agent"))
                ),
                "relationships", List.of(Map.of("from", "a", "to", "b", "type", "invented"))
        );
        KnowledgeManifest m = KcpParser.fromMap(data);
        List<String> errors = KcpValidator.validate(m);
        assertTrue(errors.stream().anyMatch(e -> e.contains("type")));
    }
}
