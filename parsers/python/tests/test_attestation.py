"""v0.22 Trust & Attestation: trust.agent_requirements + extended auth (RFC-0004/0002)."""
import yaml
from kcp.parser import parse_dict
from kcp.validator import validate

ATTEST = yaml.safe_load("""
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
""")


def test_parses_agent_requirements():
    ar = parse_dict(ATTEST).trust.agent_requirements
    assert ar.require_attestation is True
    assert ar.trusted_providers == ["internal-agents.acme.com"]
    assert ar.attestation_url == "https://acme.com/v1/attest"
    assert ar.propagate_to_governed is True


def test_parses_extended_auth_methods():
    methods = {m.type: m for m in parse_dict(ATTEST).auth.methods}
    assert methods["spiffe"].trust_domain == "acme.internal"
    assert methods["did"].supported_methods == ["did:web", "did:key"]
    assert methods["http_signature"].key_id == "k1"
    assert methods["http_signature"].algorithm == "ed25519"


def test_warns_non_https_and_unsatisfiable():
    m = parse_dict(yaml.safe_load("""
kcp_version: "0.21"
project: bad
version: 1.0.0
trust:
  agent_requirements:
    require_attestation: true
    attestation_url: http://insecure.example/attest
units:
  - {id: u, path: u.md, intent: x, scope: project, audience: [agent]}
"""))
    warns = validate(m).warnings
    assert any("attestation_url SHOULD use HTTPS" in w for w in warns)


def test_warns_propagate_without_governs():
    m = parse_dict(yaml.safe_load("""
kcp_version: "0.21"
project: nogov
version: 1.0.0
trust:
  agent_requirements:
    propagate_to_governed: true
units:
  - {id: u, path: u.md, intent: x, scope: project, audience: [agent]}
"""))
    assert any("propagate_to_governed" in w for w in validate(m).warnings)
