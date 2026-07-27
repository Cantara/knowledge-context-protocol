"""action_scope — §4.3a (v0.26.1), the envelope that gives `kind: skill` its meaning.

The Python model carried v0.27 (authority_level, grant_ceiling, task types, agents) but
never gained `action_scope` from v0.26.1. A unit parsed without it is indistinguishable
from a unit that declares none — and per §4.3a those mean opposite things: an absent
envelope authorizes nothing, so a skill silently loses the declaration that permits it to
act at all.

Mirrors shared/src/parser.ts `parseActionScope`: four known sub-fields, each optional,
absent input yielding None rather than an empty object.
"""

from kcp.parser import parse_dict


def _unit(action_scope):
    raw = {
        "project": "example",
        "version": "1.0.0",
        "kcp_version": "0.28",
        "units": [
            {
                "id": "rotate-key",
                "path": "skills/rotate-key.md",
                "intent": "How do I rotate the signing key?",
                "scope": "project",
                "audience": ["agent"],
                "kind": "skill",
            }
        ],
    }
    if action_scope is not None:
        raw["units"][0]["action_scope"] = action_scope
    return parse_dict(raw).units[0]


def test_parses_tools_paths_capabilities():
    u = _unit({
        "tools": ["kcp-sign", "git"],
        "paths": ["schema/**", ".well-known/kcp-signing-key"],
        "capabilities": ["key-management"],
    })
    assert u.action_scope is not None
    assert u.action_scope.tools == ["kcp-sign", "git"]
    assert u.action_scope.paths == ["schema/**", ".well-known/kcp-signing-key"]
    assert u.action_scope.capabilities == ["key-management"]


def test_parses_spend():
    # §4.3a.1 — the money corner of the envelope. Governs the buy decision; a runtime
    # wallet settles.
    u = _unit({
        "tools": ["http"],
        "spend": {
            "max_spend": 2.0,
            "currency": "USD",
            "allowed_vendors": ["registry.example.com"],
        },
    })
    assert u.action_scope.spend is not None
    assert u.action_scope.spend.max_spend == 2.0
    assert u.action_scope.spend.currency == "USD"
    assert u.action_scope.spend.allowed_vendors == ["registry.example.com"]


def test_absent_action_scope_is_none_not_empty():
    # An empty object would read as "declares a scope that permits nothing", which is a
    # different statement from "declares no scope". Keep them distinguishable.
    u = _unit(None)
    assert u.action_scope is None


def test_partial_declaration_leaves_other_fields_none():
    u = _unit({"tools": ["bash"]})
    assert u.action_scope.tools == ["bash"]
    assert u.action_scope.paths is None
    assert u.action_scope.capabilities is None
    assert u.action_scope.spend is None


def test_non_object_action_scope_is_ignored():
    # Matches parseActionScope: a scalar or list where an object belongs yields None
    # rather than raising — a malformed envelope must not take down the whole parse.
    for bad in ("tools", ["tools"], 42):
        u = _unit(bad)
        assert u.action_scope is None, f"expected None for {bad!r}"
