"""v0.24 Org-Federation: manifests[].context + manifests[].agent_identity (RFC-0011)."""
import yaml
from kcp.parser import parse_dict
from kcp.validator import validate

HUB = yaml.safe_load("""
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
""")


def test_parses_context_and_agent_identity():
    m = parse_dict(HUB)
    platform = next(r for r in m.manifests if r.id == "platform")
    assert platform.context == ["prod"]
    assert platform.agent_identity.required is True
    assert platform.agent_identity.credential_hint == "github_pat"
    assert platform.agent_identity.docs_url == "https://kcp.example.com/auth.md"
    data = next(r for r in m.manifests if r.id == "data")
    assert data.agent_identity.issuer_hint == "https://auth.example.com"
    assert data.context is None  # absent = all environments


def test_warns_empty_context_and_agent_identity_misuse():
    m = parse_dict(yaml.safe_load("""
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
"""))
    w = validate(m).warnings
    assert any("context is present but empty" in x for x in w)
    assert any("required is true but no credential_hint" in x for x in w)
    assert any("issuer_hint is only meaningful for credential_hint 'oauth2'" in x for x in w)
