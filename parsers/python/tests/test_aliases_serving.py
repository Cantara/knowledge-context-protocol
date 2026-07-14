"""v0.26: unit aliases (RFC-0023) + serving endpoint binding (RFC-0024)."""
import yaml
from kcp.parser import parse_dict
from kcp.validator import validate

M = yaml.safe_load("""
kcp_version: "0.26"
project: v26
version: 1.0.0
serving:
  manifest:
    - https://wiki.example.com/knowledge.yaml
    - https://mirror.example.org/knowledge.yaml
  mcp:
    - https://mcp.example.com/mcp
units:
  - id: reg-art-021
    path: articles/art-021.txt
    intent: "What security measures are required?"
    scope: global
    audience: [agent]
    aliases: [reg-art-21-2a, reg-art-21-2b, reg-art-21-2c]
  - id: other
    path: b.txt
    intent: y
    scope: global
    audience: [agent]
""")


def test_parses_aliases_and_serving():
    m = parse_dict(M)
    assert m.units[0].aliases == ["reg-art-21-2a", "reg-art-21-2b", "reg-art-21-2c"]
    assert m.units[1].aliases is None
    assert m.serving.manifest == ["https://wiki.example.com/knowledge.yaml", "https://mirror.example.org/knowledge.yaml"]
    assert m.serving.mcp == ["https://mcp.example.com/mcp"]
    r = validate(m)
    assert r.errors == []
    assert not any("alias" in w for w in r.warnings)


def test_warns_on_alias_collision_and_bad_char():
    m = parse_dict(yaml.safe_load("""
kcp_version: "0.26"
project: bad
version: 1.0.0
units:
  - id: a
    path: a.txt
    intent: x
    scope: global
    audience: [agent]
    aliases: [b, "BAD Alias"]
  - id: b
    path: b.txt
    intent: y
    scope: global
    audience: [agent]
"""))
    w = validate(m).warnings
    assert any("collides with an existing unit id" in x for x in w)  # alias 'b' == unit id 'b'
    assert any("must match" in x for x in w)  # "BAD Alias" fails the char rule


def test_malformed_aliases_and_serving_coerce_to_absent():
    # v0.26 parser parity: a scalar where a list is expected is treated as *absent*
    # (not coerced into a one-element list), non-string entries are dropped, and a
    # non-object serving block is absent — matching the TS and Java parsers exactly.
    m = parse_dict(yaml.safe_load("""
kcp_version: "0.26"
project: malformed
version: 1.0.0
serving: "https://not-an-object/knowledge.yaml"
units:
  - id: u1
    path: a.txt
    intent: x
    scope: global
    audience: [agent]
    aliases: reg-art-21-2a
  - id: u2
    path: b.txt
    intent: y
    scope: global
    audience: [agent]
    aliases: [good-alias, null, 42, true]
"""))
    assert m.serving is None                       # scalar serving -> absent, no crash
    assert m.units[0].aliases is None              # scalar aliases -> absent, not ["reg-art-21-2a"]
    assert m.units[1].aliases == ["good-alias"]    # null/int/bool entries dropped


def test_serving_requires_https():
    m = parse_dict(yaml.safe_load("""
kcp_version: "0.26"
project: bad2
version: 1.0.0
serving:
  manifest: ["http://insecure/knowledge.yaml"]
units:
  - {id: u, path: u.md, intent: x, scope: project, audience: [agent]}
"""))
    r = validate(m)
    assert any("serving.manifest entry 'http://insecure/knowledge.yaml' must be an HTTPS URL" in e for e in r.errors)
    assert not r.is_valid
