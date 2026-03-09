package no.cantara.kcp.simulator;

import no.cantara.kcp.simulator.model.KnowledgeUnit;
import no.cantara.kcp.simulator.parser.ManifestParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManifestParserTest {

    private List<KnowledgeUnit> parseYaml(String yaml) {
        return ManifestParser.parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void unitInheritsRootDefault() {
        String yaml = """
                kcp_version: "0.8"
                project: test
                version: "1.0.0"
                rate_limits:
                  default:
                    requests_per_minute: 60
                    requests_per_day: 1000
                units:
                  - id: unit1
                    path: docs/unit1.md
                    intent: "Test unit"
                """;
        List<KnowledgeUnit> units = parseYaml(yaml);
        assertEquals(1, units.size());
        assertEquals(60, units.get(0).rateLimit().requestsPerMinute());
        assertEquals(1000, units.get(0).rateLimit().requestsPerDay());
    }

    @Test
    void unitOverrideBeatsRootDefault() {
        String yaml = """
                kcp_version: "0.8"
                project: test
                version: "1.0.0"
                rate_limits:
                  default:
                    requests_per_minute: 60
                    requests_per_day: 1000
                units:
                  - id: unit1
                    path: docs/unit1.md
                    intent: "Test unit"
                    rate_limits:
                      default:
                        requests_per_minute: 5
                        requests_per_day: 50
                """;
        List<KnowledgeUnit> units = parseYaml(yaml);
        assertEquals(5, units.get(0).rateLimit().requestsPerMinute());
        assertEquals(50, units.get(0).rateLimit().requestsPerDay());
    }

    @Test
    void noRateLimits_returnsUnlimited() {
        String yaml = """
                kcp_version: "0.8"
                project: test
                version: "1.0.0"
                units:
                  - id: unit1
                    path: docs/unit1.md
                    intent: "Test unit"
                """;
        List<KnowledgeUnit> units = parseYaml(yaml);
        assertTrue(units.get(0).rateLimit().isUnlimited());
    }

    @Test
    void multipleUnits_differentLimits() {
        String yaml = """
                kcp_version: "0.8"
                project: test
                version: "1.0.0"
                rate_limits:
                  default:
                    requests_per_minute: 30
                    requests_per_day: 1000
                units:
                  - id: public-docs
                    path: docs/public.md
                    intent: "Public docs"
                    rate_limits:
                      default:
                        requests_per_minute: 120
                        requests_per_day: 10000
                  - id: internal-guide
                    path: docs/internal.md
                    intent: "Internal"
                    rate_limits:
                      default:
                        requests_per_minute: 10
                        requests_per_day: 200
                  - id: inheriting-unit
                    path: docs/inheriting.md
                    intent: "Inherits root"
                """;
        List<KnowledgeUnit> units = parseYaml(yaml);
        assertEquals(3, units.size());
        assertEquals(120, units.get(0).rateLimit().requestsPerMinute());
        assertEquals(10, units.get(1).rateLimit().requestsPerMinute());
        assertEquals(30, units.get(2).rateLimit().requestsPerMinute()); // inherited root
    }

    @Test
    void dependsOnExtracted() {
        String yaml = """
                kcp_version: "0.8"
                project: test
                version: "1.0.0"
                units:
                  - id: child
                    path: docs/child.md
                    intent: "Child unit"
                    depends_on: [parent1, parent2]
                """;
        List<KnowledgeUnit> units = parseYaml(yaml);
        assertEquals(List.of("parent1", "parent2"), units.get(0).dependsOn());
    }

    @Test
    void accessAndSensitivityParsed() {
        String yaml = """
                kcp_version: "0.8"
                project: test
                version: "1.0.0"
                units:
                  - id: restricted
                    path: docs/restricted.md
                    intent: "Restricted unit"
                    access: restricted
                    sensitivity: confidential
                """;
        List<KnowledgeUnit> units = parseYaml(yaml);
        assertEquals("restricted", units.get(0).access());
        assertEquals("confidential", units.get(0).sensitivity());
    }

    @Test
    void parsesRealManifest() throws Exception {
        Path manifestPath = Path.of("../knowledge.yaml");
        if (!manifestPath.toFile().exists()) {
            manifestPath = Path.of("/src/cantara/knowledge-context-protocol/examples/scenario4-rate-limit-aware/knowledge.yaml");
        }
        if (!manifestPath.toFile().exists()) return; // skip if not available

        List<KnowledgeUnit> units = ManifestParser.parse(manifestPath);
        assertEquals(4, units.size());

        // public-docs has unit-level override: 120/min
        assertEquals("public-docs", units.get(0).id());
        assertEquals(120, units.get(0).rateLimit().requestsPerMinute());

        // compliance-data: 2/min, 20/day
        assertEquals("compliance-data", units.get(3).id());
        assertEquals(2, units.get(3).rateLimit().requestsPerMinute());
        assertEquals(20, units.get(3).rateLimit().requestsPerDay());
    }
}
