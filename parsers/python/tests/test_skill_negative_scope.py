"""§4.3a skill negative scope + own authority ceiling (v0.31, RFC-0029).

Mirrors cli/src/skill-negative-scope.test.ts. Two capabilities a downstream KCP
consumer needs from a ``kind: skill`` unit:

 1. The skill carries its OWN ``authority_level`` — its capability ceiling — so it
    participates as a ``grant_ceiling`` source (§3.13) in the multi-source MIN.
 2. ``action_scope.deny`` — an explicit negative scope with the same
    {tools, paths, capabilities} shape as the allowlist. A deny entry is DENIED even
    when the allowlist would grant it: deny overrides allow, fail-closed.
"""

from kcp.parser import parse_dict
from kcp.validator import validate, denies_token


def _manifest(units, **extra):
    raw = {
        "project": "example",
        "version": "1.0.0",
        "kcp_version": "0.31",
        "authority_level_scale": ["observe", "explain", "suggest", "prepare", "commit"],
        "units": units,
    }
    raw.update(extra)
    return parse_dict(raw)


BASE_SKILL = {
    "id": "rotate-signing-key",
    "kind": "skill",
    "path": "skills/rotate.md",
    "intent": "How do I rotate the signing key safely?",
    "scope": "project",
    "audience": ["agent"],
    "load_eligible": True,
}


def test_round_trips_deny_alongside_allowlist():
    m = _manifest([
        {
            **BASE_SKILL,
            "action_scope": {
                "tools": ["kcp-sign", "git"],
                "paths": ["schema/**"],
                "capabilities": ["key-management"],
                "deny": {
                    "tools": ["shell"],
                    "paths": ["schema/secrets/**"],
                    "capabilities": ["network"],
                },
            },
        }
    ])
    scope = m.units[0].action_scope
    assert scope.tools == ["kcp-sign", "git"]
    assert scope.deny is not None
    assert scope.deny.tools == ["shell"]
    assert scope.deny.paths == ["schema/secrets/**"]
    assert scope.deny.capabilities == ["network"]
    # a well-formed deny + allow validates clean
    assert validate(m).is_valid


def test_denies_token_adjudicates_fail_closed():
    m = _manifest([
        {**BASE_SKILL, "action_scope": {"tools": ["git", "shell"], "deny": {"tools": ["shell"]}}}
    ])
    scope = m.units[0].action_scope
    assert denies_token(scope, "tools", "shell") is True
    assert denies_token(scope, "tools", "git") is False


def test_catches_over_broad_allow_that_deny_denies():
    # 'shell' is both granted and forbidden — the allow is dead, deny wins.
    m = _manifest([
        {**BASE_SKILL, "action_scope": {"tools": ["git", "shell"], "deny": {"tools": ["shell"]}}}
    ])
    r = validate(m)
    hit = [w for w in r.warnings if "deny" in w and "shell" in w and "§4.3a" in w]
    assert hit, f"expected a deny-overrides-allow warning, got: {r.warnings}"


def test_warns_on_empty_deny():
    m = _manifest([
        {**BASE_SKILL, "action_scope": {"tools": ["git"], "deny": {}}}
    ])
    r = validate(m)
    hit = [w for w in r.warnings if "deny" in w and "prohibits nothing" in w]
    assert hit, f"expected an empty-deny warning, got: {r.warnings}"


def test_round_trips_authority_level_on_skill_and_scale_checks_it():
    m = _manifest([
        {**BASE_SKILL, "authority_level": "prepare", "action_scope": {"tools": ["kcp-sign"]}}
    ])
    assert m.units[0].authority_level == "prepare"
    assert validate(m).is_valid
    # a value off the declared scale warns
    m2 = _manifest([
        {**BASE_SKILL, "authority_level": "yolo", "action_scope": {"tools": ["kcp-sign"]}}
    ])
    r2 = validate(m2)
    assert any("authority_level" in w and "yolo" in w for w in r2.warnings)


def test_skill_authority_level_resolves_through_grant_ceiling_unit_ref():
    m = _manifest(
        [{**BASE_SKILL, "authority_level": "suggest", "action_scope": {"tools": ["kcp-sign"]}}],
        grant_ceiling={
            "sources": [
                {"id": "org-policy", "authority_level": "prepare"},
                {"id": "skill-ceiling", "unit_ref": "rotate-signing-key"},
            ],
        },
    )
    r = validate(m)
    assert not [e for e in r.errors if "unit_ref" in e]
    assert r.is_valid
