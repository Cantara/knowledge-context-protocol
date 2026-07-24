"""Authority level / grant_ceiling validation tests (§3.13, RFC-0025, v0.27).

Mirrors the TypeScript bridge/typescript/tests/validator.test.ts
"validate — §3.13 authority_level / grant_ceiling (RFC-0025, v0.27)" describe block, so the
§7 warnings, manifest errors, computed effective-ceiling minimum, and normative capping table
stay in cross-language parity.
"""

from kcp import parse_dict, validate
from kcp.validator import compute_grant_ceiling, apply_authority_cap

SCALE = ["observe", "explain", "suggest", "prepare", "commit"]


def _manifest(**overrides):
    return parse_dict({
        "project": "test",
        "version": "1.0.0",
        "kcp_version": "0.12",
        "units": [
            {
                "id": "overview",
                "path": "README.md",
                "intent": "What is this project?",
                "scope": "global",
                "audience": ["agent"],
            }
        ],
        **overrides,
    })


def test_accepts_well_formed_grant_ceiling_with_inline_sources():
    manifest = _manifest(
        authority_level_scale=SCALE,
        task_types=[{"id": "t1", "authority_level": "explain"}],
        grant_ceiling={
            "sources": [
                {"id": "org-risk", "authority_level": "prepare"},
                {"id": "task-ceiling", "task_type_ref": "t1"},
            ],
        },
    )
    result = validate(manifest)
    assert result.errors == []


def test_errors_on_duplicate_task_types_id():
    manifest = _manifest(task_types=[{"id": "dup"}, {"id": "dup"}])
    result = validate(manifest)
    assert any("Duplicate task_types[].id" in e for e in result.errors)


def test_errors_on_duplicate_agents_id():
    manifest = _manifest(agents=[{"id": "dup"}, {"id": "dup"}])
    result = validate(manifest)
    assert any("Duplicate agents[].id" in e for e in result.errors)


def test_errors_when_grant_ceiling_sources_omits_a_mandatory_source():
    manifest = _manifest(
        grant_ceiling={
            "sources": [{"id": "a", "authority_level": "prepare"}],
            "mandatory_sources": ["a", "b"],
        },
    )
    result = validate(manifest)
    assert any("missing mandatory source 'b'" in e for e in result.errors)


def test_errors_when_source_declares_both_authority_level_and_a_ref():
    manifest = _manifest(
        task_types=[{"id": "t1", "authority_level": "explain"}],
        grant_ceiling={
            "sources": [{"id": "a", "authority_level": "prepare", "task_type_ref": "t1"}],
        },
    )
    result = validate(manifest)
    assert any("mutually exclusive" in e for e in result.errors)


def test_errors_when_source_declares_neither_authority_level_nor_a_ref():
    manifest = _manifest(grant_ceiling={"sources": [{"id": "a"}]})
    result = validate(manifest)
    assert any("must declare exactly one of" in e for e in result.errors)


def test_errors_when_refs_point_to_an_unknown_id():
    manifest = _manifest(
        grant_ceiling={
            "sources": [
                {"id": "a", "unit_ref": "nope"},
                {"id": "b", "task_type_ref": "nope"},
                {"id": "c", "agent_ref": "nope"},
            ],
        },
    )
    result = validate(manifest)
    assert len([e for e in result.errors if "references unknown" in e]) == 3


def test_warns_on_authority_level_value_not_in_declared_scale():
    manifest = _manifest(
        authority_level_scale=SCALE,
        task_types=[{"id": "t1", "authority_level": "yolo"}],
    )
    result = validate(manifest)
    assert any("not in the declared 'authority_level_scale'" in w for w in result.warnings)


def test_warns_authority_ceiling_undeclared_when_scale_declared_but_task_type_has_no_ceiling():
    manifest = _manifest(authority_level_scale=SCALE, task_types=[{"id": "t1"}])
    result = validate(manifest)
    assert any("authority_ceiling_undeclared" in w for w in result.warnings)


def test_does_not_warn_authority_ceiling_undeclared_when_grant_ceiling_exists():
    manifest = _manifest(
        authority_level_scale=SCALE,
        task_types=[{"id": "t1"}],
        grant_ceiling={"sources": [{"id": "a", "authority_level": "prepare"}]},
    )
    result = validate(manifest)
    assert not any("authority_ceiling_undeclared" in w for w in result.warnings)


def test_compute_grant_ceiling_resolves_minimum_and_names_binding_source():
    manifest = _manifest(
        authority_level_scale=SCALE,
        task_types=[{"id": "change-status", "authority_level": "explain"}],
        agents=[{"id": "lara", "authority_level": "prepare"}],
        grant_ceiling={
            "sources": [
                {"id": "org-risk", "authority_level": "prepare"},
                {"id": "org-data", "authority_level": "suggest"},
                {"id": "task-ceiling", "task_type_ref": "change-status"},
                {"id": "agent-ceiling", "agent_ref": "lara"},
            ],
        },
    )
    result = compute_grant_ceiling(manifest)
    assert result.effective_level == "explain"
    assert result.binding_source_ids == ["task-ceiling"]


def test_compute_grant_ceiling_reports_all_tied_sources():
    manifest = _manifest(
        authority_level_scale=SCALE,
        grant_ceiling={
            "sources": [
                {"id": "a", "authority_level": "suggest"},
                {"id": "b", "authority_level": "suggest"},
                {"id": "c", "authority_level": "prepare"},
            ],
        },
    )
    result = compute_grant_ceiling(manifest)
    assert result.effective_level == "suggest"
    assert sorted(result.binding_source_ids) == ["a", "b"]


def test_compute_grant_ceiling_ref_to_entity_with_no_declared_ceiling_is_non_binding():
    manifest = _manifest(
        authority_level_scale=SCALE,
        units=[
            {"id": "u1", "path": "f.md", "intent": "i", "scope": "global", "audience": ["agent"]},
        ],
        grant_ceiling={
            "sources": [
                {"id": "org-risk", "authority_level": "prepare"},
                {"id": "unit-ceiling", "unit_ref": "u1"},  # u1 has no authority_level declared
            ],
        },
    )
    result = compute_grant_ceiling(manifest)
    assert result.effective_level == "prepare"
    assert result.binding_source_ids == ["org-risk"]


def test_apply_authority_cap_caps_a_declared_permission_stricter_than_effective_level_allows():
    assert apply_authority_cap("initiative", "modify", "suggest") == "requires_approval"
    assert apply_authority_cap("initiative", "share_externally", "explain") == "denied"


def test_apply_authority_cap_never_widens_a_declared_permission_already_stricter():
    assert apply_authority_cap("denied", "modify", "commit") == "denied"


def test_apply_authority_cap_passes_through_when_no_effective_level_in_scope():
    assert apply_authority_cap("initiative", "modify", None) == "initiative"
