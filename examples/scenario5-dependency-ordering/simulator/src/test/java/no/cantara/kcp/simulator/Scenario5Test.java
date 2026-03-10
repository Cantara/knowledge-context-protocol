package no.cantara.kcp.simulator;

import no.cantara.kcp.simulator.agent.KnowledgeIngestionAgent;
import no.cantara.kcp.simulator.audit.IngestionLog;
import no.cantara.kcp.simulator.model.KnowledgeUnit;
import no.cantara.kcp.simulator.model.LoadResult;
import no.cantara.kcp.simulator.model.Relationship;
import no.cantara.kcp.simulator.parser.ManifestParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test: runs the full ingestion on the scenario5 manifest YAML.
 */
class Scenario5Test {

    private static final String MANIFEST_YAML = """
            kcp_version: "0.9"
            project: novaplatform-migration
            version: "2.0.0"
            units:
              - id: platform-overview
                path: docs/overview/platform-v1.md
                intent: "V1 architecture overview"
                access: public

              - id: migration-guide
                path: docs/migration/v1-to-v2.md
                intent: "Migration guide"
                access: public
                depends_on: [platform-overview]

              - id: api-v1-reference
                path: docs/api/v1-reference.md
                intent: "V1 API docs"
                access: public
                deprecated: true

              - id: api-v2-reference
                path: docs/api/v2-reference.md
                intent: "V2 API docs"
                access: public
                depends_on: [migration-guide]
                supersedes: api-v1-reference

              - id: deployment-guide
                path: docs/ops/deployment-v2.md
                intent: "Deployment instructions"
                access: authenticated
                depends_on: [api-v2-reference]

              - id: legacy-security-policy
                path: docs/security/legacy-perimeter.md
                intent: "Legacy security"
                access: authenticated
                deprecated: true

              - id: zero-trust-policy
                path: docs/security/zero-trust.md
                intent: "Zero trust security"
                access: authenticated
                supersedes: legacy-security-policy

              - id: troubleshooting
                path: docs/ops/troubleshooting.md
                intent: "Troubleshooting"
                access: public
                depends_on: [deployment-guide]

            relationships:
              - from: migration-guide
                to: platform-overview
                type: depends_on
              - from: api-v2-reference
                to: migration-guide
                type: depends_on
              - from: deployment-guide
                to: api-v2-reference
                type: depends_on
              - from: troubleshooting
                to: deployment-guide
                type: depends_on
              - from: platform-overview
                to: migration-guide
                type: enables
              - from: deployment-guide
                to: troubleshooting
                type: enables
              - from: api-v2-reference
                to: api-v1-reference
                type: supersedes
              - from: zero-trust-policy
                to: legacy-security-policy
                type: supersedes
              - from: legacy-security-policy
                to: zero-trust-policy
                type: contradicts
              - from: legacy-security-policy
                to: zero-trust-policy
                type: context
            """;

    private ManifestParser.ParsedManifest parsedManifest() {
        return ManifestParser.parse(
                new ByteArrayInputStream(MANIFEST_YAML.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void allEightUnitsProcessed() {
        var parsed = parsedManifest();
        var log = new IngestionLog(new PrintStream(new ByteArrayOutputStream()));
        var agent = new KnowledgeIngestionAgent(log);

        List<LoadResult> results = agent.ingest(parsed.units(), parsed.relationships());

        assertEquals(8, results.size(), "All 8 units should be processed");
    }

    @Test
    void allUnitsLoadedSuccessfully() {
        var parsed = parsedManifest();
        var log = new IngestionLog(new PrintStream(new ByteArrayOutputStream()));
        var agent = new KnowledgeIngestionAgent(log);

        List<LoadResult> results = agent.ingest(parsed.units(), parsed.relationships());

        long loaded = results.stream()
                .filter(r -> r.status() == LoadResult.Status.LOADED)
                .count();
        assertEquals(8, loaded, "All 8 should be LOADED");
    }

    @Test
    void platformOverviewLoadedFirst() {
        var parsed = parsedManifest();
        var log = new IngestionLog(new PrintStream(new ByteArrayOutputStream()));
        var agent = new KnowledgeIngestionAgent(log);

        List<LoadResult> results = agent.ingest(parsed.units(), parsed.relationships());
        var ids = results.stream().map(LoadResult::unitId).toList();

        // platform-overview has no deps, should be early
        // troubleshooting depends on deployment-guide, should be late
        assertTrue(ids.indexOf("platform-overview") < ids.indexOf("migration-guide"));
        assertTrue(ids.indexOf("migration-guide") < ids.indexOf("api-v2-reference"));
        assertTrue(ids.indexOf("api-v2-reference") < ids.indexOf("deployment-guide"));
        assertTrue(ids.indexOf("deployment-guide") < ids.indexOf("troubleshooting"));
    }

    @Test
    void supersedesLogged() {
        var parsed = parsedManifest();
        var log = new IngestionLog(new PrintStream(new ByteArrayOutputStream()));
        var agent = new KnowledgeIngestionAgent(log);

        agent.ingest(parsed.units(), parsed.relationships());

        assertTrue(log.messages().stream()
                        .anyMatch(m -> m.contains("SUPERSEDES") && m.contains("api-v2-reference")),
                "Should log api-v2-reference supersedes api-v1-reference");
        assertTrue(log.messages().stream()
                        .anyMatch(m -> m.contains("SUPERSEDES") && m.contains("zero-trust-policy")),
                "Should log zero-trust-policy supersedes legacy-security-policy");
    }

    @Test
    void contradictionWarningLogged() {
        var parsed = parsedManifest();
        var log = new IngestionLog(new PrintStream(new ByteArrayOutputStream()));
        var agent = new KnowledgeIngestionAgent(log);

        agent.ingest(parsed.units(), parsed.relationships());

        assertTrue(log.messages().stream()
                        .anyMatch(m -> m.contains("CONFLICT") && m.contains("legacy-security-policy")),
                "Should warn about legacy-security-policy contradicts zero-trust-policy");
    }

    @Test
    void independentUnitsCanLoadInAnyOrder() {
        // legacy-security-policy and zero-trust-policy have no depends_on
        // Their relative order is unimportant, but both should be LOADED
        var parsed = parsedManifest();
        var log = new IngestionLog(new PrintStream(new ByteArrayOutputStream()));
        var agent = new KnowledgeIngestionAgent(log);

        List<LoadResult> results = agent.ingest(parsed.units(), parsed.relationships());

        var statuses = results.stream()
                .filter(r -> r.unitId().equals("legacy-security-policy")
                        || r.unitId().equals("zero-trust-policy"))
                .map(LoadResult::status)
                .toList();
        assertTrue(statuses.stream().allMatch(s -> s == LoadResult.Status.LOADED));
    }
}
