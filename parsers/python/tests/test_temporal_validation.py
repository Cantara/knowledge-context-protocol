"""Temporal validation tests (§4.22 unit-level; §3.6 manifests[].temporal).

Mirrors the TypeScript cli/src/temporal-validation.test.ts so the §7 warnings
and superseded_by cycle MUST-errors stay in cross-language parity.
"""

from kcp import parse_dict, validate

PAST = "2000-01-01"
FUTURE = "2999-12-31"


def _manifest(**extra):
    return {"kcp_version": "0.21", "project": "test", "version": "1.0.0", **extra}


def _unit(uid, temporal=None, **extra):
    u = {"id": uid, "path": f"docs/{uid}.md", "intent": f"Unit {uid}",
         "scope": "project", "audience": ["agent"], **extra}
    if temporal is not None:
        u["temporal"] = temporal
    return u


def _validate_units(units, **extra):
    return validate(parse_dict(_manifest(units=units, **extra)))


def test_empty_window_warns():
    r = _validate_units([_unit("a", {"valid_from": "2026-06-01", "valid_until": "2026-01-01"})])
    assert any("empty validity window" in w for w in r.warnings)
    assert r.is_valid  # warning, not error


def test_normal_window_clean():
    r = _validate_units([_unit("a", {"valid_from": "2026-01-01", "valid_until": FUTURE})])
    assert not any("empty validity window" in w for w in r.warnings)
    assert not any("stale" in w for w in r.warnings)


def test_stale_unit_warns():
    r = _validate_units([_unit("a", {"valid_until": PAST})])
    assert any("stale unit with no successor" in w for w in r.warnings)


def test_stale_suppressed_by_successor():
    r = _validate_units([_unit("a", {"valid_until": PAST, "superseded_by": "b"}), _unit("b")])
    assert not any("stale unit" in w for w in r.warnings)


def test_dangling_superseded_by_warns():
    r = _validate_units([_unit("a", {"superseded_by": "ghost"})])
    assert any("superseded_by references unknown unit 'ghost'" in w for w in r.warnings)


def test_namespaced_superseded_by_not_flagged():
    r = _validate_units([_unit("a", {"superseded_by": "platform:newer"})])
    assert not any("superseded_by references unknown" in w for w in r.warnings)


def test_superseded_by_cycle_errors():
    r = _validate_units([_unit("a", {"superseded_by": "b"}), _unit("b", {"superseded_by": "a"})])
    assert not r.is_valid
    assert any("superseded_by cycle" in e for e in r.errors)


def test_linear_chain_no_cycle():
    r = _validate_units([
        _unit("a", {"superseded_by": "b"}),
        _unit("b", {"superseded_by": "c"}),
        _unit("c"),
    ])
    assert not any("superseded_by cycle" in e for e in r.errors)


def test_root_temporal_defaults_apply():
    r = _validate_units([_unit("a", {"valid_from": "1999-01-01"})], temporal={"valid_until": PAST})
    assert any("stale unit" in w for w in r.warnings)


def test_verified_without_verified_by_warns_unit_and_root():
    r = _validate_units(
        [_unit("a", discovery={"verification_status": "verified"})],
        discovery={"verification_status": "verified"},
    )
    assert len([w for w in r.warnings if "verified_by is absent" in w]) == 2


def test_verified_with_verified_by_clean():
    r = _validate_units([_unit("a", discovery={"verification_status": "verified", "verified_by": "key-1"})])
    assert not any("verified_by is absent" in w for w in r.warnings)


# --- federation temporal (§3.6 manifests[].temporal) ---

def _ref(rid, temporal=None):
    r = {"id": rid, "url": f"https://example.com/{rid}/knowledge.yaml", "relationship": "governs"}
    if temporal is not None:
        r["temporal"] = temporal
    return r


def _validate_manifests(manifests):
    return validate(parse_dict(_manifest(units=[_unit("local")], manifests=manifests)))


def test_manifests_temporal_exposed():
    m = parse_dict(_manifest(units=[_unit("local")], manifests=[_ref("a", {"valid_from": "2020-01-01"})]))
    assert m.manifests[0].temporal.valid_from == "2020-01-01"


def test_stale_federation_link_warns():
    r = _validate_manifests([_ref("a", {"valid_until": PAST})])
    assert any("stale federation link" in w for w in r.warnings)


def test_federation_empty_window_and_dangling():
    r = _validate_manifests([
        _ref("a", {"valid_from": "2026-06-01", "valid_until": "2026-01-01", "superseded_by": "ghost"}),
    ])
    assert any("empty validity window" in w for w in r.warnings)
    assert any("unknown manifests[].id 'ghost'" in w for w in r.warnings)


def test_federation_superseded_cycle_errors():
    r = _validate_manifests([_ref("a", {"superseded_by": "b"}), _ref("b", {"superseded_by": "a"})])
    assert not r.is_valid
    assert any("manifests[].temporal.superseded_by cycle" in e for e in r.errors)
