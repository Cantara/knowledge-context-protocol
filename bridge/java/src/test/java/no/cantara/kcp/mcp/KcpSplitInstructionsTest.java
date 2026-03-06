package no.cantara.kcp.mcp;

import no.cantara.kcp.KcpParser;
import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for KcpSplitInstructions — split instruction file generation.
 */
class KcpSplitInstructionsTest {

    @TempDir
    Path tempDir;

    private static Path fixture(String name) {
        URL url = KcpSplitInstructionsTest.class.getClassLoader()
            .getResource("fixtures/" + name + "/knowledge.yaml");
        assertNotNull(url, "fixture not found: " + name);
        return Paths.get(url.getPath());
    }

    // ── SplitBy.NONE ─────────────────────────────────────────────────────────

    @Test void splitByNoneCreatesSingleFile() throws Exception {
        KnowledgeManifest manifest = KcpParser.parse(fixture("full"));
        KcpSplitInstructions.writeSplitInstructions(
            manifest, tempDir, KcpSplitInstructions.SplitBy.NONE, null);

        assertTrue(Files.exists(tempDir.resolve("all.instructions.md")));
    }

    @Test void splitByNoneContainsAllUnits() throws Exception {
        KnowledgeManifest manifest = KcpParser.parse(fixture("full"));
        KcpSplitInstructions.writeSplitInstructions(
            manifest, tempDir, KcpSplitInstructions.SplitBy.NONE, null);

        String content = Files.readString(tempDir.resolve("all.instructions.md"));
        assertTrue(content.contains("| spec |"));
        assertTrue(content.contains("| api-schema |"));
        assertTrue(content.contains("| guide |"));
    }

    @Test void splitByNoneHasApplyToFrontmatter() throws Exception {
        KnowledgeManifest manifest = KcpParser.parse(fixture("full"));
        KcpSplitInstructions.writeSplitInstructions(
            manifest, tempDir, KcpSplitInstructions.SplitBy.NONE, null);

        String content = Files.readString(tempDir.resolve("all.instructions.md"));
        assertTrue(content.startsWith("---\n"));
        assertTrue(content.contains("applyTo: \"**\""));
    }

    // ── SplitBy.SCOPE ────────────────────────────────────────────────────────

    @Test void splitByScopeCreatesFilePerScope() throws Exception {
        KnowledgeManifest manifest = KcpParser.parse(fixture("full"));
        KcpSplitInstructions.writeSplitInstructions(
            manifest, tempDir, KcpSplitInstructions.SplitBy.SCOPE, null);

        // full fixture has global (spec), module (api-schema), project (guide)
        assertTrue(Files.exists(tempDir.resolve("global.instructions.md")),
            "Should create global.instructions.md");
        assertTrue(Files.exists(tempDir.resolve("module.instructions.md")),
            "Should create module.instructions.md");
        assertTrue(Files.exists(tempDir.resolve("project.instructions.md")),
            "Should create project.instructions.md");
    }

    @Test void splitByScopeGlobalFileHasGlobalApplyTo() throws Exception {
        KnowledgeManifest manifest = KcpParser.parse(fixture("full"));
        KcpSplitInstructions.writeSplitInstructions(
            manifest, tempDir, KcpSplitInstructions.SplitBy.SCOPE, null);

        String content = Files.readString(tempDir.resolve("global.instructions.md"));
        assertTrue(content.contains("applyTo: \"**\""),
            "Global scope should have applyTo: ** pattern");
    }

    // ── SplitBy.UNIT ─────────────────────────────────────────────────────────

    @Test void splitByUnitCreatesFilePerUnit() throws Exception {
        KnowledgeManifest manifest = KcpParser.parse(fixture("full"));
        KcpSplitInstructions.writeSplitInstructions(
            manifest, tempDir, KcpSplitInstructions.SplitBy.UNIT, null);

        assertTrue(Files.exists(tempDir.resolve("spec.instructions.md")));
        assertTrue(Files.exists(tempDir.resolve("api-schema.instructions.md")));
        assertTrue(Files.exists(tempDir.resolve("guide.instructions.md")));
    }

    @Test void splitByUnitEachFileContainsOnlyOneUnit() throws Exception {
        KnowledgeManifest manifest = KcpParser.parse(fixture("full"));
        KcpSplitInstructions.writeSplitInstructions(
            manifest, tempDir, KcpSplitInstructions.SplitBy.UNIT, null);

        String specContent = Files.readString(tempDir.resolve("spec.instructions.md"));
        assertTrue(specContent.contains("| spec |"));
        assertFalse(specContent.contains("| api-schema |"));
        assertFalse(specContent.contains("| guide |"));
    }

    // ── SplitBy.DIRECTORY ────────────────────────────────────────────────────

    @Test void splitByDirectoryGroupsByTopLevelDir() throws Exception {
        KnowledgeManifest manifest = KcpParser.parse(fixture("full"));
        KcpSplitInstructions.writeSplitInstructions(
            manifest, tempDir, KcpSplitInstructions.SplitBy.DIRECTORY, null);

        // All three units in the full fixture have root-level paths (README.md, api.json, GUIDE.md)
        // so they all go to the "root" group
        assertTrue(Files.exists(tempDir.resolve("root.instructions.md")),
            "Units with root-level paths should be grouped as 'root'");
    }

    // ── Audience filter ──────────────────────────────────────────────────────

    @Test void audienceFilterAppliedToSplitFiles() throws Exception {
        KnowledgeManifest manifest = KcpParser.parse(fixture("full"));
        KcpSplitInstructions.writeSplitInstructions(
            manifest, tempDir, KcpSplitInstructions.SplitBy.NONE, "agent");

        String content = Files.readString(tempDir.resolve("all.instructions.md"));
        assertTrue(content.contains("| spec |"), "spec has agent audience");
        assertTrue(content.contains("| api-schema |"), "api-schema has agent audience");
        assertFalse(content.contains("| guide |"), "guide does not have agent audience");
    }

    // ── buildInstructionFile ─────────────────────────────────────────────────

    @Test void instructionFileHasHeaderComment() throws Exception {
        KnowledgeManifest manifest = KcpParser.parse(fixture("full"));
        String content = KcpSplitInstructions.buildInstructionFile(
            manifest, manifest.units(), "**");

        assertTrue(content.contains("<!-- Generated by kcp-mcp from knowledge.yaml | project: full-example"));
    }

    @Test void instructionFileHasKnowledgeUnitsTitle() throws Exception {
        KnowledgeManifest manifest = KcpParser.parse(fixture("full"));
        String content = KcpSplitInstructions.buildInstructionFile(
            manifest, manifest.units(), "docs/**");

        assertTrue(content.contains("# full-example \u2014 Knowledge Units"));
    }

    // ── buildApplyTo ─────────────────────────────────────────────────────────

    @Test void buildApplyToLimitsToFivePatterns() throws Exception {
        KnowledgeManifest manifest = KcpParser.parse(fixture("full"));
        List<KnowledgeUnit> units = manifest.units();
        String applyTo = KcpSplitInstructions.buildApplyTo(
            units, KcpSplitInstructions.SplitBy.DIRECTORY);
        String[] patterns = applyTo.split(",");

        assertTrue(patterns.length <= 5, "Should not exceed 5 patterns, got: " + patterns.length);
    }

    @Test void buildApplyToScopeGlobalReturnsWildcard() throws Exception {
        KnowledgeManifest manifest = KcpParser.parse(fixture("full"));
        // Only global units
        List<KnowledgeUnit> globalUnits = manifest.units().stream()
            .filter(u -> "global".equals(u.scope()))
            .toList();
        String applyTo = KcpSplitInstructions.buildApplyTo(
            globalUnits, KcpSplitInstructions.SplitBy.SCOPE);

        assertEquals("**", applyTo);
    }

    // ── groupUnits ───────────────────────────────────────────────────────────

    @Test void groupUnitsByScopeCreatesThreeGroups() throws Exception {
        KnowledgeManifest manifest = KcpParser.parse(fixture("full"));
        Map<String, List<KnowledgeUnit>> groups = KcpSplitInstructions.groupUnits(
            manifest.units(), KcpSplitInstructions.SplitBy.SCOPE);

        assertTrue(groups.containsKey("global"));
        assertTrue(groups.containsKey("module"));
        assertTrue(groups.containsKey("project"));
        assertEquals(3, groups.size());
    }

    @Test void groupUnitsByUnitCreatesOneGroupPerUnit() throws Exception {
        KnowledgeManifest manifest = KcpParser.parse(fixture("full"));
        Map<String, List<KnowledgeUnit>> groups = KcpSplitInstructions.groupUnits(
            manifest.units(), KcpSplitInstructions.SplitBy.UNIT);

        assertEquals(3, groups.size());
        assertTrue(groups.containsKey("spec"));
        assertTrue(groups.containsKey("api-schema"));
        assertTrue(groups.containsKey("guide"));
    }

    // ── generateSplitInstructions (file I/O) ─────────────────────────────────

    @Test void generateSplitInstructionsCreatesOutputDir() throws Exception {
        Path newDir = tempDir.resolve("nested/output");
        assertFalse(Files.exists(newDir));

        KcpSplitInstructions.generateSplitInstructions(
            fixture("full"), newDir, KcpSplitInstructions.SplitBy.NONE, null);

        assertTrue(Files.exists(newDir));
        assertTrue(Files.exists(newDir.resolve("all.instructions.md")));
    }

    // ── Relationships filtering ──────────────────────────────────────────────

    @Test void splitFileContainsRelevantRelationshipsOnly() throws Exception {
        KnowledgeManifest manifest = KcpParser.parse(fixture("full"));
        // Get just the spec unit
        List<KnowledgeUnit> specOnly = manifest.units().stream()
            .filter(u -> "spec".equals(u.id()))
            .toList();

        String content = KcpSplitInstructions.buildInstructionFile(
            manifest, specOnly, "**");

        // spec is referenced by both relationships (api-schema->spec, guide->spec)
        assertTrue(content.contains("## Relationships"));
        assertTrue(content.contains("api-schema \u2192 spec (context)"));
        assertTrue(content.contains("guide \u2192 spec (context)"));
    }
}
