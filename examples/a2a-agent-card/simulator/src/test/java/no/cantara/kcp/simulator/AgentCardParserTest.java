package no.cantara.kcp.simulator;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AgentCardParser using the actual agent-card.json file.
 */
class AgentCardParserTest {

    private Path agentCardPath() {
        // The agent-card.json is at ../agent-card.json relative to the simulator/ directory.
        // When tests run from the simulator/ directory (Maven default), this resolves correctly.
        // Also try the absolute path as a fallback.
        Path relative = Path.of("../agent-card.json");
        if (relative.toFile().exists()) return relative;

        // Fallback: try from the repo root
        Path absolute = Path.of("examples/a2a-agent-card/agent-card.json");
        if (absolute.toFile().exists()) return absolute;

        // Last resort: use the known absolute path
        return Path.of("/src/cantara/knowledge-context-protocol/examples/a2a-agent-card/agent-card.json");
    }

    @Test
    void parsesAgentName() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());

        assertEquals("Clinical Research Agent", card.name());
    }

    @Test
    void parsesAgentUrl() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());

        assertEquals("https://research.example.com/agent", card.url());
    }

    @Test
    void parsesSkillsCount() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());

        assertEquals(2, card.skills().size());
        assertEquals("protocol-lookup", card.skills().get(0).get("id"));
        assertEquals("cohort-analysis", card.skills().get(1).get("id"));
    }

    @Test
    void parsesOAuth2TokenEndpoint() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());

        assertEquals("https://auth.research.example.com/oauth2/token", card.oauth2TokenUrl());
    }

    @Test
    void parsesKnowledgeManifestField() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());

        assertEquals("/.well-known/kcp.json", card.knowledgeManifest());
    }

    @Test
    void parsesVersion() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());

        assertEquals("1.0.0", card.version());
    }

    @Test
    void parsesProvider() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());

        assertEquals("Example Health Research", card.providerOrg());
        assertEquals("https://research.example.com", card.providerUrl());
    }

    @Test
    void parsesOAuth2Scopes() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());

        assertNotNull(card.oauth2Scopes());
        assertEquals(3, card.oauth2Scopes().size());
        assertTrue(card.oauth2Scopes().containsKey("read:guidelines"));
        assertTrue(card.oauth2Scopes().containsKey("read:protocols"));
        assertTrue(card.oauth2Scopes().containsKey("read:cohort"));
    }
}
