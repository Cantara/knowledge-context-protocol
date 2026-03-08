package no.cantara.kcp.simulator.legal;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses an A2A Agent Card JSON file using SnakeYAML (YAML is a superset of JSON).
 * No Jackson/Gson dependency required.
 */
public final class AgentCardParser {

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    private AgentCardParser() {}

    public static AgentCard parse(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return parse(is);
        }
    }

    @SuppressWarnings("unchecked")
    public static AgentCard parse(InputStream is) {
        Map<String, Object> data = YAML.load(is);
        if (data == null) {
            throw new IllegalArgumentException("Empty or null agent card");
        }

        String name = getString(data, "name");
        String description = getString(data, "description");
        String url = getString(data, "url");
        String version = getString(data, "version");
        String documentationUrl = getString(data, "documentationUrl");

        // Provider
        String providerOrg = null;
        String providerUrl = null;
        Map<String, Object> provider = (Map<String, Object>) data.get("provider");
        if (provider != null) {
            providerOrg = getString(provider, "organization");
            providerUrl = getString(provider, "url");
        }

        // Security schemes -> oauth2
        String oauth2TokenUrl = null;
        Map<String, String> oauth2Scopes = Collections.emptyMap();
        Map<String, Object> securitySchemes = (Map<String, Object>) data.get("securitySchemes");
        if (securitySchemes != null) {
            Map<String, Object> oauth2 = (Map<String, Object>) securitySchemes.get("oauth2");
            if (oauth2 != null) {
                Map<String, Object> flows = (Map<String, Object>) oauth2.get("flows");
                if (flows != null) {
                    Map<String, Object> clientCreds = (Map<String, Object>) flows.get("clientCredentials");
                    if (clientCreds != null) {
                        oauth2TokenUrl = getString(clientCreds, "tokenUrl");
                        Map<String, Object> rawScopes = (Map<String, Object>) clientCreds.get("scopes");
                        if (rawScopes != null) {
                            oauth2Scopes = new LinkedHashMap<>();
                            for (Map.Entry<String, Object> e : rawScopes.entrySet()) {
                                oauth2Scopes.put(e.getKey(), e.getValue() != null ? e.getValue().toString() : "");
                            }
                        }
                    }
                }
            }
        }

        // Skills
        List<Map<String, Object>> skills = (List<Map<String, Object>>) data.getOrDefault("skills", List.of());

        // Knowledge manifest
        String knowledgeManifest = getString(data, "knowledgeManifest");

        return new AgentCard(name, description, url, version, documentationUrl,
                providerOrg, providerUrl, oauth2TokenUrl, oauth2Scopes, skills, knowledgeManifest);
    }

    private static String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }
}
