"""§4.3b playbook-level prohibitions (v0.32, RFC-0030).

Mirrors cli/src/playbook-negative-scope.test.ts. A ``kind: playbook`` unit's
``action_scope.deny`` is a blanket prohibition over EVERY step — the one normative
sub-object of the otherwise declarative playbook ``action_scope`` envelope. The
effective denylist for a step is the UNION of the playbook's deny and the used
skill's deny: a match in either denies, overriding any allow, fail-closed. Union is
the only sound composition — adding a source can only refuse more (the scope-axis
mirror of the §3.13 lowest-of).
"""

from kcp.parser import parse_dict
from kcp.validator import validate, denies_token, effective_denies_token


def _manifest(units, **extra):
    raw = {
        "project": "example",
        "version": "1.0.0",
        "kcp_version": "0.32",
        "authority_level_scale": ["observe", "explain", "suggest", "prepare", "commit"],
        "units": units,
    }
    raw.update(extra)
    return parse_dict(raw)


SLETTEAGENT = {
    "id": "sletteagent",
    "kind": "skill",
    "path": "skills/sletteagent.md",
    "intent": "How do I delete customer data compliantly?",
    "scope": "project",
    "audience": ["agent"],
    "load_eligible": True,
    "action_scope": {
        "tools": ["delete", "read"],
        "paths": ["customers/**", "legal/hold/2025/**"],
        "deny": {"tools": ["transfer_ownership"]},
    },
}

BASE_PLAYBOOK = {
    "id": "pb-002-gdpr-sletting",
    "kind": "playbook",
    "path": "playbooks/gdpr-sletting.md",
    "intent": "How is a GDPR Art.17 deletion request executed?",
    "scope": "project",
    "audience": ["agent"],
    "load_eligible": True,
    "authority_level": "commit",
}


def test_round_trips_deny_on_a_playbook():
    m = _manifest([
        SLETTEAGENT,
        {
            **BASE_PLAYBOOK,
            "action_scope": {
                "deny": {"paths": ["legal/hold/**"], "tools": ["transfer_ownership"]},
            },
            "steps": [{"id": "slett", "uses": "sletteagent", "authority_level": "commit"}],
        },
    ])
    pb = next(u for u in m.units if u.id == "pb-002-gdpr-sletting")
    assert pb.action_scope.deny.paths == ["legal/hold/**"]
    assert pb.action_scope.deny.tools == ["transfer_ownership"]


def test_effective_deny_is_the_union():
    m = _manifest([
        SLETTEAGENT,
        {
            **BASE_PLAYBOOK,
            "action_scope": {"deny": {"paths": ["legal/hold/**"], "tools": ["set_billing"]}},
            "steps": [{"id": "slett", "uses": "sletteagent", "authority_level": "commit"}],
        },
    ])
    pb = next(u for u in m.units if u.id == "pb-002-gdpr-sletting")
    skill = next(u for u in m.units if u.id == "sletteagent")
    scopes = [pb.action_scope, skill.action_scope]

    # playbook-only match
    assert effective_denies_token(scopes, "paths", "legal/hold/**")
    assert not denies_token(skill.action_scope, "paths", "legal/hold/**")

    # skill-only match
    assert effective_denies_token(scopes, "tools", "transfer_ownership")
    assert not denies_token(pb.action_scope, "tools", "transfer_ownership")

    # neither — allowed tokens pass through
    assert not effective_denies_token(scopes, "tools", "read")


def test_adding_a_deny_source_never_un_denies():
    m = _manifest([SLETTEAGENT])
    skill = m.units[0]
    assert denies_token(skill.action_scope, "tools", "transfer_ownership")
    # a playbook that denies nothing on this dimension cannot relax the skill's deny
    assert effective_denies_token([None, skill.action_scope], "tools", "transfer_ownership")


def test_warns_when_a_step_is_self_nullified():
    m = _manifest([
        SLETTEAGENT,
        {
            **BASE_PLAYBOOK,
            "action_scope": {"deny": {"tools": ["delete", "read"]}},
            "steps": [{"id": "slett", "uses": "sletteagent", "authority_level": "commit"}],
        },
    ])
    result = validate(m)
    assert any("self-nullified" in w and "'tools'" in w for w in result.warnings)
    # paths dimension is not fully denied — no warning there
    assert not any("self-nullified" in w and "'paths'" in w for w in result.warnings)


def test_does_not_warn_when_the_deny_only_carves_a_hole():
    m = _manifest([
        SLETTEAGENT,
        {
            **BASE_PLAYBOOK,
            "action_scope": {"deny": {"paths": ["legal/hold/**"]}},
            "steps": [{"id": "slett", "uses": "sletteagent", "authority_level": "commit"}],
        },
    ])
    result = validate(m)
    assert not any("self-nullified" in w for w in result.warnings)


def test_skill_deny_alone_can_self_nullify_a_step():
    m = _manifest([
        {
            **SLETTEAGENT,
            "action_scope": {
                "tools": ["transfer_ownership"],
                "deny": {"tools": ["transfer_ownership"]},
            },
        },
        {
            **BASE_PLAYBOOK,
            "steps": [{"id": "slett", "uses": "sletteagent", "authority_level": "commit"}],
        },
    ])
    result = validate(m)
    assert any("self-nullified" in w and "'tools'" in w for w in result.warnings)


def test_empty_deny_on_a_playbook_draws_the_prohibits_nothing_lint():
    m = _manifest([
        SLETTEAGENT,
        {
            **BASE_PLAYBOOK,
            "action_scope": {"deny": {}},
            "steps": [{"id": "slett", "uses": "sletteagent", "authority_level": "commit"}],
        },
    ])
    result = validate(m)
    assert any("prohibits nothing" in w for w in result.warnings)
