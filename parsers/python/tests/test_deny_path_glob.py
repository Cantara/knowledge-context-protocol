"""§4.3a (v0.32.1) — deny.paths entries are PATTERNS, matched structurally.

Mirrors cli/src/deny-path-glob.test.ts. Exact-string comparison never fires the
``schema/secrets/**`` carve-out the spec promises: no requested path is ever the
literal string ``schema/secrets/**``. Pins glob semantics for ``denies_token``,
the union (``effective_denies_token``), and the §4.3b self-nullified lint — and
pins that tools/capabilities stay exact-match.
"""

from kcp.parser import parse_dict
from kcp.validator import (
    validate,
    denies_token,
    effective_denies_token,
    path_glob_matches,
)


def test_double_star_crosses_segments():
    assert path_glob_matches("legal/hold/**", "legal/hold/2025/case.pdf")
    assert path_glob_matches("legal/hold/**", "legal/hold/x")
    assert not path_glob_matches("legal/hold/**", "legal/holdings/x")


def test_single_star_stays_within_segment():
    assert path_glob_matches("customers/*/pii", "customers/acme/pii")
    assert not path_glob_matches("customers/*/pii", "customers/a/b/pii")


def test_literals_are_escaped():
    assert path_glob_matches("a.b/c", "a.b/c")
    assert not path_glob_matches("a.b/c", "axb/c")


class _Scope:
    def __init__(self, **kw):
        self.tools = kw.get("tools")
        self.paths = kw.get("paths")
        self.capabilities = kw.get("capabilities")
        self.deny = kw.get("deny")
        self.spend = None


def _scope():
    return _Scope(
        paths=["schema/**"],
        deny=_Scope(paths=["schema/secrets/**", "legal/hold/**"], tools=["delete"]),
    )


def test_deny_glob_denies_paths_beneath_it():
    assert denies_token(_scope(), "paths", "legal/hold/2025/case.pdf")
    assert denies_token(_scope(), "paths", "schema/secrets/key.pem")


def test_carve_out_fires():
    assert not denies_token(_scope(), "paths", "schema/api.json")
    assert denies_token(_scope(), "paths", "schema/secrets/nested/key.pem")


def test_tools_remain_exact():
    assert denies_token(_scope(), "tools", "delete")
    assert not denies_token(_scope(), "tools", "delete_all")


def test_union_inherits_glob():
    pb = _Scope(deny=_Scope(paths=["legal/hold/**"]))
    skill = _Scope(paths=["customers/**"])
    assert effective_denies_token([pb, skill], "paths", "legal/hold/2025/x")
    assert not effective_denies_token([pb, skill], "paths", "customers/acme/x")


def _manifest(skill_paths, pb_deny_paths):
    return parse_dict({
        "project": "example",
        "version": "1.0.0",
        "kcp_version": "0.32",
        "authority_level_scale": ["observe", "explain", "suggest", "prepare", "commit"],
        "units": [
            {
                "id": "sletteagent", "kind": "skill", "path": "skills/s.md",
                "intent": "How do I delete compliantly?", "scope": "project",
                "audience": ["agent"], "load_eligible": True,
                "action_scope": {"tools": ["read"], "paths": skill_paths},
            },
            {
                "id": "pb", "kind": "playbook", "path": "playbooks/p.md",
                "intent": "How is deletion executed?", "scope": "project",
                "audience": ["agent"], "load_eligible": True,
                "authority_level": "commit",
                "action_scope": {"deny": {"paths": pb_deny_paths}},
                "steps": [{"id": "slett", "uses": "sletteagent", "authority_level": "commit"}],
            },
        ],
    })


def test_self_nullified_lint_sees_glob_containment():
    result = validate(_manifest(["legal/hold/2025/**"], ["legal/hold/**"]))
    assert any("self-nullified" in w and "'paths'" in w for w in result.warnings)


def test_carve_out_does_not_self_nullify():
    result = validate(_manifest(["customers/**"], ["customers/pii/**"]))
    assert not any("self-nullified" in w for w in result.warnings)
