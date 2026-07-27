"""kind: playbook — §4.3b (v0.29, RFC-0027).

Mirrors cli/src/playbook.test.ts and KcpPlaybookTest.java case for case, so the three
implementations can be compared by reading them side by side.

Two rules are tested from the attack direction rather than the happy path, because the
adversarial review on RFC-0027 found both stated as advice when they are load-bearing:

  - an unresolvable ``uses`` must be an ERROR. A resolvable ``uses`` is the entire
    justification for playbook being a distinct kind rather than ``executable`` plus a
    metadata block; a dangling reference that lints clean removes the only thing the
    new kind buys.
  - nesting must be an ERROR pending RFC-0027 OQ1. As a warning it is no guard at all:
    nested playbooks form a combined depends_on graph that the per-playbook cycle check
    never sees.
"""

from kcp.parser import parse_dict
from kcp.validator import validate

SKILL = {
    "id": "run-test-suite",
    "kind": "skill",
    "path": "skills/run.md",
    "intent": "How do I run the suite?",
    "scope": "project",
    "audience": ["agent"],
    "action_scope": {"tools": ["bash"], "paths": ["test/**"]},
}


def _manifest(units, **extra):
    raw = {
        "project": "example",
        "version": "1.0.0",
        "kcp_version": "0.29",
        "units": units,
    }
    raw.update(extra)
    return parse_dict(raw)


def _playbook(steps, **extra):
    unit = {
        "id": "promote",
        "kind": "playbook",
        "path": "playbooks/promote.md",
        "intent": "How do we promote a build?",
        "scope": "project",
        "audience": ["agent"],
        "steps": steps,
    }
    unit.update(extra)
    return unit


def _errors(units, **extra):
    return validate(_manifest(units, **extra)).errors


def _warnings(units, **extra):
    return validate(_manifest(units, **extra)).warnings


# --- parsing -----------------------------------------------------------------


def test_parses_every_step_field():
    m = _manifest([SKILL, _playbook([{
        "id": "verify",
        "uses": "run-test-suite",
        "depends_on": [],
        "authority_level": "observe",
        "escalation": ["requires_approval"],
        "success_condition": "zero failures",
        "on_failure": "abort",
        "timeout": "PT10M",
    }])])
    s = m.units[1].steps[0]
    assert s.id == "verify"
    assert s.uses == "run-test-suite"
    assert s.authority_level == "observe"
    assert s.escalation == ["requires_approval"]
    assert s.success_condition == "zero failures"
    assert s.on_failure == "abort"
    assert s.timeout == "PT10M"


def test_bare_escalation_string_normalises_to_a_list():
    # §4.3b calls the triggers disjunctive, so a scalar and a one-element list mean the
    # same thing. Normalising at parse time means no consumer handles both shapes.
    m = _manifest([SKILL, _playbook([
        {"id": "a", "uses": "run-test-suite", "escalation": "requires_approval"}
    ])])
    assert m.units[1].steps[0].escalation == ["requires_approval"]


def test_absent_steps_is_none_not_empty_list():
    # "declares no steps" and "declares an empty composition" are different statements.
    # The validator rejects both for a playbook, but the parser keeps them distinct.
    m = _manifest([SKILL])
    assert m.units[0].steps is None


def test_steps_without_an_id_are_dropped():
    # A step with no identity cannot be named by depends_on, so it cannot join the
    # graph. Half-parsing would put an id-less entry into a structure indexed by id.
    m = _manifest([SKILL, _playbook(
        ["not-a-mapping", {"uses": "run-test-suite"}, {"id": "ok", "action": "x"}]
    )])
    assert [s.id for s in m.units[1].steps] == ["ok"]


def test_malformed_steps_block_does_not_break_the_parse():
    m = _manifest([SKILL, _playbook("steps")])
    assert m.units[1].steps is None
    assert m.units[1].id == "promote"


# --- validation: structure ---------------------------------------------------


def test_well_formed_playbook_has_no_errors():
    assert _errors(
        [SKILL, _playbook([{"id": "verify", "uses": "run-test-suite",
                            "authority_level": "observe"}])],
        authority_level_scale=["observe", "explain", "suggest", "prepare", "commit"],
    ) == []


def test_playbook_without_steps_errors():
    unit = _playbook([])
    del unit["steps"]
    assert any("non-empty 'steps'" in e for e in _errors([unit]))


def test_empty_steps_list_errors():
    assert any("non-empty 'steps'" in e for e in _errors([_playbook([])]))


def test_step_with_neither_uses_nor_action_errors():
    assert any("either 'uses' or 'action'" in e for e in _errors([_playbook([{"id": "orphan"}])]))


def test_duplicate_step_ids_error():
    errs = _errors([SKILL, _playbook([
        {"id": "a", "uses": "run-test-suite"},
        {"id": "a", "action": "again"},
    ])])
    assert any("duplicate step id 'a'" in e for e in errs)


def test_unknown_on_failure_errors():
    errs = _errors([SKILL, _playbook([
        {"id": "a", "uses": "run-test-suite", "on_failure": "retry"}
    ])])
    assert any("'on_failure' must be one of" in e for e in errs)


# --- validation: uses resolution ---------------------------------------------


def test_unresolvable_uses_is_an_error_not_a_warning():
    r = validate(_manifest([_playbook([{"id": "a", "uses": "nonexistent"}])]))
    assert any("not declared in this manifest" in e for e in r.errors)
    assert not any("nonexistent" in w for w in r.warnings)


def test_uses_resolves_against_a_unit_declared_later():
    # The check is a second pass for exactly this reason: an inline check would reject
    # a legal forward reference, because the id set is incomplete mid-loop.
    assert _errors([_playbook([{"id": "a", "uses": "run-test-suite"}]), SKILL]) == []


def test_nesting_is_an_error():
    inner = dict(_playbook([{"id": "x", "action": "inner"}]), id="inner")
    outer = dict(_playbook([{"id": "a", "uses": "inner"}]), id="outer")
    assert any("nesting is not permitted" in e for e in _errors([inner, outer]))


def test_uses_naming_a_resolvable_non_skill_unit_warns_only():
    doc = {"id": "notes", "kind": "knowledge", "path": "n.md", "intent": "x",
           "scope": "project", "audience": ["agent"]}
    r = validate(_manifest([doc, _playbook([{"id": "a", "uses": "notes"}])]))
    assert r.errors == []
    assert any("SHOULD name a kind: skill unit" in w for w in r.warnings)


# --- validation: the depends_on graph ----------------------------------------


def test_dangling_depends_on_errors():
    errs = _errors([SKILL, _playbook([
        {"id": "a", "uses": "run-test-suite", "depends_on": ["ghost"]}
    ])])
    assert any("depends_on names unknown step 'ghost'" in e for e in errs)


def test_two_step_cycle_errors():
    errs = _errors([SKILL, _playbook([
        {"id": "a", "uses": "run-test-suite", "depends_on": ["b"]},
        {"id": "b", "uses": "run-test-suite", "depends_on": ["a"]},
    ])])
    assert any("contains a cycle" in e for e in errs)


def test_longer_cycle_reports_the_path():
    errs = _errors([SKILL, _playbook([
        {"id": "a", "uses": "run-test-suite", "depends_on": ["c"]},
        {"id": "b", "uses": "run-test-suite", "depends_on": ["a"]},
        {"id": "c", "uses": "run-test-suite", "depends_on": ["b"]},
    ])])
    cycle = [e for e in errs if "contains a cycle" in e]
    assert cycle and "->" in cycle[0]


def test_self_dependency_is_a_cycle():
    errs = _errors([SKILL, _playbook([
        {"id": "a", "uses": "run-test-suite", "depends_on": ["a"]}
    ])])
    assert any("contains a cycle" in e for e in errs)


def test_diamond_is_not_a_cycle():
    # The classic false positive for a naive visited-set walk: d is reached twice by
    # distinct paths, which is convergence, not a cycle.
    assert _errors([SKILL, _playbook([
        {"id": "a", "uses": "run-test-suite"},
        {"id": "b", "uses": "run-test-suite", "depends_on": ["a"]},
        {"id": "c", "uses": "run-test-suite", "depends_on": ["a"]},
        {"id": "d", "uses": "run-test-suite", "depends_on": ["b", "c"]},
    ])]) == []


def test_long_chain_does_not_exhaust_the_stack():
    # Untrusted input: a deep chain must report cleanly, not blow the recursion limit.
    steps = [
        {"id": f"s{i}", "uses": "run-test-suite",
         "depends_on": [f"s{i - 1}"] if i else []}
        for i in range(5000)
    ]
    errs = _errors([SKILL, _playbook(steps)])
    assert not [e for e in errs if "cycle" in e]


# --- validation: scope verifiability -----------------------------------------


def test_inline_steps_warn_they_are_scope_unbounded():
    warns = _warnings([_playbook([{"id": "a", "action": "do the thing"}])])
    assert any("bounded only by its authority_level" in w for w in warns)


def test_declared_scope_is_unverified_when_a_step_is_inline():
    # §4.3b: an unverifiable declaration that lints clean is worse than none, because
    # it reads as checked.
    warns = _warnings([SKILL, _playbook(
        [{"id": "a", "uses": "run-test-suite"}, {"id": "b", "action": "inline"}],
        action_scope={"tools": ["bash"]},
    )])
    assert any("UNVERIFIED" in w for w in warns)


def test_declared_scope_is_unverified_when_a_referenced_unit_has_no_scope():
    bare = dict(SKILL, id="bare")
    del bare["action_scope"]
    warns = _warnings([bare, _playbook(
        [{"id": "a", "uses": "bare"}], action_scope={"tools": ["bash"]}
    )])
    assert any("UNVERIFIED" in w for w in warns)


def test_scope_is_verified_when_every_step_resolves_to_a_scoped_unit():
    warns = _warnings([SKILL, _playbook(
        [{"id": "a", "uses": "run-test-suite"}], action_scope={"tools": ["bash"]}
    )])
    assert not any("UNVERIFIED" in w for w in warns)


def test_mutating_step_without_authority_level_warns():
    warns = _warnings([SKILL, _playbook([{"id": "a", "uses": "run-test-suite"}])])
    assert any("omits 'authority_level'" in w for w in warns)


# --- validation: non-playbook units ------------------------------------------


def test_steps_on_a_non_playbook_warns():
    warns = _warnings([dict(SKILL, steps=[{"id": "a", "action": "x"}])])
    assert any("only enacted for kind: playbook" in w for w in warns)


def test_playbook_is_a_recognised_kind():
    warns = _warnings([SKILL, _playbook([{"id": "a", "uses": "run-test-suite"}])])
    assert not any("unknown 'kind'" in w for w in warns)
