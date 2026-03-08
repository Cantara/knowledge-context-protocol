package no.cantara.kcp.simulator.legal;

import java.util.List;
import java.util.Map;

/**
 * Parsed representation of an A2A Agent Card (agent-card.json).
 *
 * @param name              agent display name
 * @param description       what the agent does
 * @param url               agent endpoint URL
 * @param version           agent version
 * @param documentationUrl  link to documentation
 * @param providerOrg       provider organisation name
 * @param providerUrl       provider URL
 * @param oauth2TokenUrl    OAuth2 token endpoint (client_credentials flow)
 * @param oauth2Scopes      available OAuth2 scopes with descriptions
 * @param skills            list of skill maps (id, name, description, tags, examples)
 * @param knowledgeManifest URL path to the KCP manifest
 */
public record AgentCard(
        String name,
        String description,
        String url,
        String version,
        String documentationUrl,
        String providerOrg,
        String providerUrl,
        String oauth2TokenUrl,
        Map<String, String> oauth2Scopes,
        List<Map<String, Object>> skills,
        String knowledgeManifest
) {}
