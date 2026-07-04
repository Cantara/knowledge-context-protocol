package no.cantara.kcp;

import no.cantara.kcp.model.KnowledgeManifest;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** v0.23 Trust & Auth Completion: publisher_did, access receipts, require_delegation_proof,
 *  per-unit auth override (RFC-0004/0002). */
class KcpTrustAuthCompletionTest {

    @SuppressWarnings("unchecked")
    private static KnowledgeManifest parse(String yaml) {
        return KcpParser.fromMap((Map<String, Object>) new Yaml().load(yaml));
    }

    @Test void parsesV23Fields() {
        KnowledgeManifest m = parse("""
            kcp_version: "0.22"
            project: v23
            version: 1.0.0
            trust:
              provenance: {publisher: Acme, publisher_did: "did:web:acme.com"}
              audit: {provides_access_receipts: true, receipt_format: jws}
            delegation: {max_depth: 2, require_delegation_proof: true}
            units:
              - id: partner
                path: p.md
                intent: partner data
                scope: project
                audience: [agent]
                access: restricted
                auth:
                  methods:
                    - {type: oauth2, issuer: "https://partner.example.com", scopes: [read:shared]}
            """);
        assertEquals("did:web:acme.com", m.trust().provenance().publisherDid());
        assertEquals(Boolean.TRUE, m.trust().audit().providesAccessReceipts());
        assertEquals("jws", m.trust().audit().receiptFormat());
        assertEquals(Boolean.TRUE, m.delegation().requireDelegationProof());
        assertEquals("oauth2", m.units().get(0).auth().methods().get(0).type());
        assertEquals("https://partner.example.com", m.units().get(0).auth().methods().get(0).issuer());
    }

    @Test void warnsBadDidAndReceiptsWithoutFormat() {
        KnowledgeManifest m = parse("""
            kcp_version: "0.22"
            project: bad
            version: 1.0.0
            trust:
              provenance: {publisher_did: "acme.com"}
              audit: {provides_access_receipts: true}
            units:
              - {id: u, path: u.md, intent: x, scope: project, audience: [agent]}
            """);
        var w = KcpValidator.validate(m).warnings();
        assertTrue(w.stream().anyMatch(x -> x.contains("publisher_did SHOULD be a DID")), w.toString());
        assertTrue(w.stream().anyMatch(x -> x.contains("provides_access_receipts is true but no receipt_format")), w.toString());
    }
}
