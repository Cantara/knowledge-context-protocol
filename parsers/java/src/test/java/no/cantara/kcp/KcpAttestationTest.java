package no.cantara.kcp;

import no.cantara.kcp.model.KnowledgeManifest;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** v0.22 Trust & Attestation: trust.agent_requirements + extended auth (RFC-0004/0002). */
class KcpAttestationTest {

    @SuppressWarnings("unchecked")
    private static KnowledgeManifest parse(String yaml) {
        return KcpParser.fromMap((Map<String, Object>) new Yaml().load(yaml));
    }

    private static final String ATTEST = """
        kcp_version: "0.21"
        project: attest-demo
        version: 1.0.0
        trust:
          agent_requirements:
            require_attestation: true
            trusted_providers: [internal-agents.acme.com]
            attestation_url: https://acme.com/v1/attest
            propagate_to_governed: true
        auth:
          methods:
            - {type: spiffe, trust_domain: acme.internal}
            - {type: did, supported_methods: [did:web, did:key]}
            - {type: http_signature, key_id: k1, algorithm: ed25519}
        relationships:
          - {from: overview, to: overview, type: governs}
        units:
          - {id: overview, path: README.md, intent: x, scope: project, audience: [agent], access: restricted}
        """;

    @Test void parsesAgentRequirements() {
        var ar = parse(ATTEST).trust().agentRequirements();
        assertEquals(Boolean.TRUE, ar.requireAttestation());
        assertEquals(List.of("internal-agents.acme.com"), ar.trustedProviders());
        assertEquals("https://acme.com/v1/attest", ar.attestationUrl());
        assertEquals(Boolean.TRUE, ar.propagateToGoverned());
    }

    @Test void parsesExtendedAuthMethods() {
        var methods = parse(ATTEST).auth().methods();
        var spiffe = methods.stream().filter(m -> "spiffe".equals(m.type())).findFirst().orElseThrow();
        var did = methods.stream().filter(m -> "did".equals(m.type())).findFirst().orElseThrow();
        var sig = methods.stream().filter(m -> "http_signature".equals(m.type())).findFirst().orElseThrow();
        assertEquals("acme.internal", spiffe.trustDomain());
        assertEquals(List.of("did:web", "did:key"), did.supportedMethods());
        assertEquals("k1", sig.keyId());
        assertEquals("ed25519", sig.algorithm());
    }

    @Test void warnsNonHttpsAndUnsatisfiable() {
        KnowledgeManifest m = parse("""
            kcp_version: "0.21"
            project: bad
            version: 1.0.0
            trust:
              agent_requirements:
                require_attestation: true
                attestation_url: http://insecure.example/attest
            units:
              - {id: u, path: u.md, intent: x, scope: project, audience: [agent]}
            """);
        var warnings = KcpValidator.validate(m).warnings();
        assertTrue(warnings.stream().anyMatch(w -> w.contains("attestation_url SHOULD use HTTPS")), warnings.toString());
    }

    @Test void warnsPropagateWithoutGoverns() {
        KnowledgeManifest m = parse("""
            kcp_version: "0.21"
            project: nogov
            version: 1.0.0
            trust:
              agent_requirements:
                propagate_to_governed: true
            units:
              - {id: u, path: u.md, intent: x, scope: project, audience: [agent]}
            """);
        assertTrue(KcpValidator.validate(m).warnings().stream()
                .anyMatch(w -> w.contains("propagate_to_governed")));
    }
}
