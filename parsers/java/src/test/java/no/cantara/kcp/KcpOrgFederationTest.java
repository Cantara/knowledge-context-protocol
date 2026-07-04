package no.cantara.kcp;

import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.ManifestRef;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** v0.24 Org-Federation: manifests[].context + manifests[].agent_identity (RFC-0011). */
class KcpOrgFederationTest {

    @SuppressWarnings("unchecked")
    private static KnowledgeManifest parse(String yaml) {
        return KcpParser.fromMap((Map<String, Object>) new Yaml().load(yaml));
    }

    private static ManifestRef ref(KnowledgeManifest m, String id) {
        return m.manifests().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    @Test void parsesContextAndAgentIdentity() {
        KnowledgeManifest m = parse("""
            kcp_version: "0.24"
            project: hub
            version: 1.0.0
            units:
              - {id: front-door, path: README.md, intent: x, scope: global, audience: [agent]}
            manifests:
              - id: platform
                url: "https://git.example.com/platform/knowledge.yaml"
                relationship: foundation
                context: ["prod"]
                agent_identity:
                  required: true
                  credential_hint: github_pat
                  docs_url: "https://kcp.example.com/auth.md"
              - id: data
                url: "https://git.example.com/data/knowledge.yaml"
                relationship: peer
                agent_identity:
                  required: true
                  credential_hint: oauth2
                  issuer_hint: "https://auth.example.com"
            """);
        ManifestRef platform = ref(m, "platform");
        assertEquals(List.of("prod"), platform.context());
        assertEquals(Boolean.TRUE, platform.agentIdentity().required());
        assertEquals("github_pat", platform.agentIdentity().credentialHint());
        assertEquals("https://kcp.example.com/auth.md", platform.agentIdentity().docsUrl());
        ManifestRef data = ref(m, "data");
        assertEquals("https://auth.example.com", data.agentIdentity().issuerHint());
        assertNull(data.context()); // absent = all environments
    }

    @Test void warnsEmptyContextAndAgentIdentityMisuse() {
        KnowledgeManifest m = parse("""
            kcp_version: "0.24"
            project: bad
            version: 1.0.0
            units:
              - {id: u, path: u.md, intent: x, scope: project, audience: [agent]}
            manifests:
              - id: a
                url: "https://git.example.com/a/knowledge.yaml"
                context: []
                agent_identity: {required: true}
              - id: b
                url: "https://git.example.com/b/knowledge.yaml"
                agent_identity: {credential_hint: github_pat, issuer_hint: "https://x.example.com"}
            """);
        var w = KcpValidator.validate(m).warnings();
        assertTrue(w.stream().anyMatch(x -> x.contains("context is present but empty")), w.toString());
        assertTrue(w.stream().anyMatch(x -> x.contains("required is true but no credential_hint")), w.toString());
        assertTrue(w.stream().anyMatch(x -> x.contains("issuer_hint is only meaningful for credential_hint 'oauth2'")), w.toString());
    }
}
