"""Tests for kcp_mcp.mapper — pure mapping functions, no I/O."""
from datetime import date

import pytest
from kcp.parser import parse_dict

from kcp_mcp.mapper import (
    build_manifest_json,
    is_binary_mime,
    manifest_resource_dict,
    manifest_uri,
    map_audience,
    project_slug,
    resolve_mime,
    unit_resource_dict,
    unit_uri,
)
from kcp.model import KnowledgeUnit


def make_unit(**kwargs) -> KnowledgeUnit:
    defaults = dict(
        id="spec",
        path="SPEC.md",
        intent="What are the rules?",
        scope="global",
        audience=["human", "agent"],
        depends_on=[],
        triggers=[],
    )
    defaults.update(kwargs)
    return KnowledgeUnit(**defaults)


# ── project_slug ──────────────────────────────────────────────────────────────

def test_slug_lowercases_and_hyphenates():
    assert project_slug("My Project") == "my-project"

def test_slug_collapses_spaces():
    assert project_slug("Knowledge Context Protocol") == "knowledge-context-protocol"

def test_slug_already_clean():
    assert project_slug("kcp") == "kcp"

def test_slug_removes_special_chars():
    assert project_slug("wiki.totto.org") == "wikitottoorg"


# ── unit_uri / manifest_uri ───────────────────────────────────────────────────

def test_unit_uri():
    assert unit_uri("my-project", "spec") == "knowledge://my-project/spec"

def test_manifest_uri():
    assert manifest_uri("my-project") == "knowledge://my-project/manifest"


# ── is_binary_mime ────────────────────────────────────────────────────────────

def test_pdf_is_binary():
    assert is_binary_mime("application/pdf") is True

def test_image_is_binary():
    assert is_binary_mime("image/png") is True

def test_markdown_is_not_binary():
    assert is_binary_mime("text/markdown") is False

def test_json_is_not_binary():
    assert is_binary_mime("application/json") is False


# ── resolve_mime ──────────────────────────────────────────────────────────────

def test_content_type_wins():
    unit = make_unit(content_type="application/schema+json", format="markdown")
    assert resolve_mime(unit) == "application/schema+json"

def test_format_lookup():
    unit = make_unit(format="openapi")
    assert resolve_mime(unit) == "application/vnd.oai.openapi+yaml"

def test_extension_fallback_md():
    unit = make_unit(path="docs/guide.md")
    assert resolve_mime(unit) == "text/markdown"

def test_extension_fallback_yaml():
    unit = make_unit(path="schema.yaml")
    assert resolve_mime(unit) == "application/yaml"

def test_extension_fallback_json():
    unit = make_unit(path="api.json")
    assert resolve_mime(unit) == "application/json"

def test_unknown_extension_defaults_plain():
    unit = make_unit(path="file.xyz")
    assert resolve_mime(unit) == "text/plain"


# ── map_audience ──────────────────────────────────────────────────────────────

def test_agent_maps_to_assistant():
    assert "assistant" in map_audience(["agent"])

def test_human_maps_to_user():
    assert "user" in map_audience(["human"])

def test_mixed_audience_gets_both():
    result = map_audience(["human", "agent"])
    assert "user" in result
    assert "assistant" in result

def test_empty_audience_defaults_to_user():
    assert map_audience([]) == ["user"]

def test_developer_maps_to_user():
    assert "user" in map_audience(["developer"])


# ── unit_resource_dict ────────────────────────────────────────────────────────

def test_unit_resource_dict_uri():
    unit = make_unit()
    r = unit_resource_dict("slug", unit)
    assert r["uri"] == "knowledge://slug/spec"

def test_unit_resource_dict_name():
    unit = make_unit()
    r = unit_resource_dict("slug", unit)
    assert r["name"] == "spec"

def test_unit_resource_global_priority():
    unit = make_unit(scope="global")
    r = unit_resource_dict("slug", unit)
    assert r["annotations"]["priority"] == 1.0

def test_unit_resource_module_priority():
    unit = make_unit(scope="module")
    r = unit_resource_dict("slug", unit)
    assert r["annotations"]["priority"] == 0.5

def test_unit_resource_last_modified():
    unit = make_unit(validated=date(2026, 2, 27))
    r = unit_resource_dict("slug", unit)
    assert r["annotations"]["lastModified"] == "2026-02-27T00:00:00Z"

def test_unit_resource_no_last_modified_when_absent():
    unit = make_unit()
    r = unit_resource_dict("slug", unit)
    assert r["annotations"]["lastModified"] is None

def test_unit_resource_triggers_in_description():
    unit = make_unit(triggers=["spec", "rules"])
    r = unit_resource_dict("slug", unit)
    assert "Triggers: spec, rules" in r["description"]

def test_unit_resource_depends_on_in_description():
    unit = make_unit(depends_on=["overview"])
    r = unit_resource_dict("slug", unit)
    assert "Depends on: overview" in r["description"]


# ── manifest_resource_dict ───────────────────────────────────────────────────

def test_manifest_resource_dict_always_has_name_manifest():
    manifest = parse_dict({
        "project": "test",
        "version": "1.0.0",
        "units": [],
    })
    r = manifest_resource_dict("test", manifest)
    assert r["name"] == "manifest"
    assert r["uri"] == "knowledge://test/manifest"
    assert r["mimeType"] == "application/json"
    assert r["annotations"]["priority"] == 1.0


# ── build_manifest_json ───────────────────────────────────────────────────────

def test_build_manifest_json_structure():
    import json
    manifest = parse_dict({
        "project": "test",
        "version": "1.0.0",
        "units": [
            {
                "id": "spec",
                "path": "SPEC.md",
                "intent": "What is this?",
                "scope": "global",
                "audience": ["agent"],
                "triggers": ["spec"],
            }
        ],
        "relationships": [{"from": "spec", "to": "spec", "type": "context"}],
    })
    body = json.loads(build_manifest_json(manifest, "test"))
    assert body["project"] == "test"
    assert body["unit_count"] == 1
    assert body["units"][0]["id"] == "spec"
    assert body["units"][0]["uri"] == "knowledge://test/spec"
    assert body["relationships"][0]["from"] == "spec"
