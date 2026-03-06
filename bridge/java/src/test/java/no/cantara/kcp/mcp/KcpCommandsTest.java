package no.cantara.kcp.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KcpCommandsTest {

    private static Path commandsFixture() {
        URL url = KcpCommandsTest.class.getClassLoader()
            .getResource("fixtures/commands/git-commit.yaml");
        assertNotNull(url, "commands fixture not found");
        return Paths.get(url.getPath()).getParent();
    }

    // ── loadCommandManifests ──────────────────────────────────────────────────

    @Test void loadCommandManifestsReadsYamlFiles() {
        Map<String, KcpCommands.CommandManifest> map = KcpCommands.loadCommandManifests(commandsFixture());
        assertFalse(map.isEmpty());
        assertTrue(map.containsKey("git commit"));
        assertTrue(map.containsKey("mvn"));
    }

    @Test void loadCommandManifestsSkipsNonYamlFiles() {
        Map<String, KcpCommands.CommandManifest> map = KcpCommands.loadCommandManifests(commandsFixture());
        // not-yaml.txt should be skipped — only 2 YAML files
        assertEquals(2, map.size());
    }

    @Test void loadCommandManifestsEmptyDir(@TempDir Path tmp) {
        Map<String, KcpCommands.CommandManifest> map = KcpCommands.loadCommandManifests(tmp);
        assertTrue(map.isEmpty());
    }

    @Test void loadCommandManifestsNonexistentDir() {
        Map<String, KcpCommands.CommandManifest> map =
            KcpCommands.loadCommandManifests(Path.of("/nonexistent/dir"));
        assertTrue(map.isEmpty());
    }

    @Test void loadedManifestHasCorrectFields() {
        Map<String, KcpCommands.CommandManifest> map = KcpCommands.loadCommandManifests(commandsFixture());
        KcpCommands.CommandManifest gitCommit = map.get("git commit");
        assertNotNull(gitCommit);
        assertEquals("git", gitCommit.command());
        assertEquals("commit", gitCommit.subcommand());
        assertEquals("all", gitCommit.platform());
        assertEquals("Record staged changes to the repository", gitCommit.description());
        assertEquals("git commit [<options>]", gitCommit.syntax().usage());
        assertEquals(1, gitCommit.syntax().keyFlags().size());
        assertEquals(1, gitCommit.syntax().preferredInvocations().size());
    }

    @Test void loadedManifestWithoutSubcommand() {
        Map<String, KcpCommands.CommandManifest> map = KcpCommands.loadCommandManifests(commandsFixture());
        KcpCommands.CommandManifest mvn = map.get("mvn");
        assertNotNull(mvn);
        assertEquals("mvn", mvn.command());
        assertNull(mvn.subcommand());
    }

    @Test void invalidYamlIsSkipped(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("bad.yaml"), "not: [valid: yaml: {{{");
        Map<String, KcpCommands.CommandManifest> map = KcpCommands.loadCommandManifests(tmp);
        assertTrue(map.isEmpty());
    }

    @Test void yamlWithoutCommandFieldIsSkipped(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("nocmd.yaml"), "platform: all\ndescription: test\n");
        Map<String, KcpCommands.CommandManifest> map = KcpCommands.loadCommandManifests(tmp);
        assertTrue(map.isEmpty());
    }

    // ── formatSyntaxBlock ─────────────────────────────────────────────────────

    @Test void formatSyntaxBlockProducesCorrectOutput() {
        Map<String, KcpCommands.CommandManifest> map = KcpCommands.loadCommandManifests(commandsFixture());
        KcpCommands.CommandManifest gitCommit = map.get("git commit");
        String block = KcpCommands.formatSyntaxBlock(gitCommit);

        assertTrue(block.startsWith("[kcp] git commit:"));
        assertTrue(block.contains("Usage: git commit [<options>]"));
        assertTrue(block.contains("Key flags:"));
        assertTrue(block.contains("-m '<message>':"));
        assertTrue(block.contains("\u2192"));
        assertTrue(block.contains("Preferred:"));
        assertTrue(block.contains("git commit -m 'Add feature X'"));
    }

    @Test void formatSyntaxBlockWithoutSubcommand() {
        Map<String, KcpCommands.CommandManifest> map = KcpCommands.loadCommandManifests(commandsFixture());
        KcpCommands.CommandManifest mvn = map.get("mvn");
        String block = KcpCommands.formatSyntaxBlock(mvn);

        assertTrue(block.startsWith("[kcp] mvn:"));
        assertFalse(block.contains("[kcp] mvn null")); // no "null" for missing subcommand
    }

    // ── lookupCommand ─────────────────────────────────────────────────────────

    @Test void lookupCommandExactMatch() {
        Map<String, KcpCommands.CommandManifest> map = KcpCommands.loadCommandManifests(commandsFixture());
        KcpCommands.CommandManifest found = KcpCommands.lookupCommand(map, "git commit");
        assertNotNull(found);
        assertEquals("git", found.command());
        assertEquals("commit", found.subcommand());
    }

    @Test void lookupCommandCaseInsensitive() {
        Map<String, KcpCommands.CommandManifest> map = KcpCommands.loadCommandManifests(commandsFixture());
        KcpCommands.CommandManifest found = KcpCommands.lookupCommand(map, "GIT COMMIT");
        assertNotNull(found);
        assertEquals("git", found.command());
    }

    @Test void lookupCommandPrefixMatchFindsSubcommand() {
        Map<String, KcpCommands.CommandManifest> map = KcpCommands.loadCommandManifests(commandsFixture());
        // "git" should find the git commit entry via prefix match (no base "git" command)
        KcpCommands.CommandManifest found = KcpCommands.lookupCommand(map, "git");
        assertNotNull(found);
        assertEquals("git", found.command());
    }

    @Test void lookupCommandPrefixPrefersBaseCommand() {
        Map<String, KcpCommands.CommandManifest> map = KcpCommands.loadCommandManifests(commandsFixture());
        // "mvn" has no subcommand — should be found as base command
        KcpCommands.CommandManifest found = KcpCommands.lookupCommand(map, "mvn");
        assertNotNull(found);
        assertEquals("mvn", found.command());
        assertNull(found.subcommand());
    }

    @Test void lookupCommandReturnsNullForUnknown() {
        Map<String, KcpCommands.CommandManifest> map = KcpCommands.loadCommandManifests(commandsFixture());
        KcpCommands.CommandManifest found = KcpCommands.lookupCommand(map, "unknown-command");
        assertNull(found);
    }

    @Test void lookupCommandTrimsWhitespace() {
        Map<String, KcpCommands.CommandManifest> map = KcpCommands.loadCommandManifests(commandsFixture());
        KcpCommands.CommandManifest found = KcpCommands.lookupCommand(map, "  git commit  ");
        assertNotNull(found);
        assertEquals("commit", found.subcommand());
    }
}
