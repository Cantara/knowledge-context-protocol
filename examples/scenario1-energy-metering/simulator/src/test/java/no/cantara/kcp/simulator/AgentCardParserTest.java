package no.cantara.kcp.simulator;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AgentCardParser using the energy metering agent-card.json.
 */
class AgentCardParserTest {

    private Path agentCardPath() {
        Path relative = Path.of("../agent-card.json");
        if (relative.toFile().exists()) return relative;
        return Path.of("/src/cantara/knowledge-context-protocol/examples/scenario1-energy-metering/agent-card.json");
    }

    @Test
    void parsesAgentName() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());
        assertEquals("Energy Metering Agent", card.name());
    }

    @Test
    void parsesAgentUrl() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());
        assertEquals("https://grid.example.com/agent", card.url());
    }

    @Test
    void parsesThreeSkills() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());
        assertEquals(3, card.skills().size());
        assertEquals("tariff-lookup", card.skills().get(0).get("id"));
        assertEquals("consumption-analysis", card.skills().get(1).get("id"));
        assertEquals("grid-diagnostics", card.skills().get(2).get("id"));
    }

    @Test
    void parsesOAuth2TokenEndpoint() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());
        assertEquals("https://auth.grid.example.com/oauth2/token", card.oauth2TokenUrl());
    }

    @Test
    void parsesFourOAuth2Scopes() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());
        assertNotNull(card.oauth2Scopes());
        assertEquals(4, card.oauth2Scopes().size());
        assertTrue(card.oauth2Scopes().containsKey("read:tariff"));
        assertTrue(card.oauth2Scopes().containsKey("read:meter"));
        assertTrue(card.oauth2Scopes().containsKey("read:billing"));
        assertTrue(card.oauth2Scopes().containsKey("grid-engineer"));
    }

    @Test
    void parsesKnowledgeManifest() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());
        assertEquals("/.well-known/kcp.json", card.knowledgeManifest());
    }

    @Test
    void parsesProvider() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());
        assertEquals("Example Grid Utilities", card.providerOrg());
        assertEquals("https://grid.example.com", card.providerUrl());
    }
}
