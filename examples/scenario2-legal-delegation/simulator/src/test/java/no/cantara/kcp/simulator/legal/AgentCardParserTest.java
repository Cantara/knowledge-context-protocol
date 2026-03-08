package no.cantara.kcp.simulator.legal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AgentCardParser using the legal research agent-card.json.
 */
class AgentCardParserTest {

    private Path agentCardPath() {
        Path relative = Path.of("../agent-card.json");
        if (relative.toFile().exists()) return relative;
        return Path.of("/src/cantara/knowledge-context-protocol/examples/scenario2-legal-delegation/agent-card.json");
    }

    @Test
    void parsesAgentName() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());
        assertEquals("Legal Research Agent", card.name());
    }

    @Test
    void parsesAgentUrl() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());
        assertEquals("https://legal.example.com/agent", card.url());
    }

    @Test
    void parsesTwoSkills() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());
        assertEquals(2, card.skills().size());
        assertEquals("precedent-search", card.skills().get(0).get("id"));
        assertEquals("case-analysis", card.skills().get(1).get("id"));
    }

    @Test
    void parsesFourOAuth2Scopes() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());
        assertNotNull(card.oauth2Scopes());
        assertEquals(4, card.oauth2Scopes().size());
        assertTrue(card.oauth2Scopes().containsKey("read:precedents"));
        assertTrue(card.oauth2Scopes().containsKey("read:case"));
        assertTrue(card.oauth2Scopes().containsKey("attorney"));
        assertTrue(card.oauth2Scopes().containsKey("court-officer"));
    }

    @Test
    void parsesProvider() throws IOException {
        AgentCard card = AgentCardParser.parse(agentCardPath());
        assertEquals("Example Law LLP", card.providerOrg());
    }
}
