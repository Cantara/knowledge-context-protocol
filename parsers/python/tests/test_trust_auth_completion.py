"""v0.23 Trust & Auth Completion: publisher_did, access receipts, require_delegation_proof,
per-unit auth (RFC-0004/0002)."""
import yaml
from kcp.parser import parse_dict
from kcp.validator import validate

M = yaml.safe_load("""
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
""")


def test_parses_v23_fields():
    m = parse_dict(M)
    assert m.trust.provenance.publisher_did == "did:web:acme.com"
    assert m.trust.audit.provides_access_receipts is True
    assert m.trust.audit.receipt_format == "jws"
    assert m.delegation.require_delegation_proof is True
    assert m.units[0].auth.methods[0].type == "oauth2"
    assert m.units[0].auth.methods[0].issuer == "https://partner.example.com"


def test_warns_bad_did_and_receipts_without_format():
    m = parse_dict(yaml.safe_load("""
kcp_version: "0.22"
project: bad
version: 1.0.0
trust:
  provenance: {publisher_did: "acme.com"}
  audit: {provides_access_receipts: true}
units:
  - {id: u, path: u.md, intent: x, scope: project, audience: [agent]}
"""))
    w = validate(m).warnings
    assert any("publisher_did SHOULD be a DID" in x for x in w)
    assert any("provides_access_receipts is true but no receipt_format" in x for x in w)
