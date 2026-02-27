import pytest
from datetime import date
from kcp import parse_dict, validate, ValidationResult
from kcp.model import KnowledgeManifest, KnowledgeUnit, Relationship
from kcp.parser import _validate_unit_path


MINIMAL = {
    "project": "test-project",
    "version": "1.0.0",
    "units": [
        {
            "id": "overview",
            "path": "README.md",
            "intent": "What is this project?",
            "scope": "global",
            "audience": ["human", "agent"],
        }
    ],
}

MINIMAL_WITH_KCP_VERSION = {**MINIMAL, "kcp_version": "0.1"}

COMPLETE = {
    "project": "wiki.example.org",
    "version": "1.0.0",
    "kcp_version": "0.1",
    "updated": "2026-02-25",
    "units": [
        {
            "id": "about",
            "path": "about.md",
            "intent": "Who maintains this?",
            "scope": "global",
            "audience": ["human", "agent"],
            "validated": "2026-02-24",
        },
        {
            "id": "methodology",
            "path": "methodology/overview.md",
            "intent": "What development methodology is used?",
            "scope": "global",
            "audience": ["developer", "architect", "agent"],
            "depends_on": ["about"],
            "validated": "2026-02-13",
            "triggers": ["methodology", "productivity"],
        },
        {
            "id": "knowledge-infra",
            "path": "tools/knowledge-infra.md",
            "intent": "How is knowledge infrastructure set up?",
            "scope": "global",
            "audience": ["developer", "devops", "agent"],
            "depends_on": ["methodology"],
            "supersedes": "knowledge-infra-v1",
            "triggers": ["MCP", "indexing"],
        },
    ],
    "relationships": [
        {"from": "methodology", "to": "knowledge-infra", "type": "enables"},
        {"from": "about", "to": "methodology", "type": "context"},
    ],
}


class TestParser:
    def test_minimal_parse(self):
        m = parse_dict(MINIMAL)
        assert m.project == "test-project"
        assert m.version == "1.0.0"
        assert m.kcp_version is None
        assert len(m.units) == 1
        assert m.units[0].id == "overview"

    def test_kcp_version_parsed(self):
        m = parse_dict(MINIMAL_WITH_KCP_VERSION)
        assert m.kcp_version == "0.1"

    def test_missing_version_does_not_crash(self):
        data = {"project": "test", "units": MINIMAL["units"]}
        m = parse_dict(data)
        assert m.project == "test"
        assert m.version == ""

    def test_complete_parse(self):
        m = parse_dict(COMPLETE)
        assert m.project == "wiki.example.org"
        assert m.kcp_version == "0.1"
        assert m.updated == date(2026, 2, 25)
        assert len(m.units) == 3
        assert len(m.relationships) == 2

    def test_depends_on_parsed(self):
        m = parse_dict(COMPLETE)
        methodology = next(u for u in m.units if u.id == "methodology")
        assert methodology.depends_on == ["about"]

    def test_triggers_parsed(self):
        m = parse_dict(COMPLETE)
        methodology = next(u for u in m.units if u.id == "methodology")
        assert "methodology" in methodology.triggers

    def test_relationships_parsed(self):
        m = parse_dict(COMPLETE)
        assert m.relationships[0].from_id == "methodology"
        assert m.relationships[0].to_id == "knowledge-infra"
        assert m.relationships[0].type == "enables"

    def test_validated_date_parsed(self):
        m = parse_dict(COMPLETE)
        about = next(u for u in m.units if u.id == "about")
        assert about.validated == date(2026, 2, 24)


class TestValidator:
    def test_returns_validation_result(self):
        m = parse_dict(MINIMAL)
        result = validate(m)
        assert isinstance(result, ValidationResult)
        assert hasattr(result, "errors")
        assert hasattr(result, "warnings")

    def test_valid_complete(self):
        m = parse_dict(COMPLETE)
        result = validate(m)
        assert result.errors == []
        assert result.warnings == []
        assert result.is_valid

    def test_missing_kcp_version_produces_warning(self):
        m = parse_dict(MINIMAL)  # no kcp_version
        result = validate(m)
        assert result.errors == []
        assert any("kcp_version" in w for w in result.warnings)

    def test_known_kcp_version_no_warning(self):
        m = parse_dict(MINIMAL_WITH_KCP_VERSION)
        result = validate(m)
        assert not any("kcp_version" in w for w in result.warnings)

    def test_unknown_kcp_version_produces_warning(self):
        m = parse_dict({**MINIMAL, "kcp_version": "99.0"})
        result = validate(m)
        assert result.errors == []
        assert any("kcp_version" in w and "99.0" in w for w in result.warnings)

    def test_missing_project(self):
        data = {**MINIMAL, "project": ""}
        m = parse_dict(data)
        result = validate(m)
        assert any("project" in e for e in result.errors)

    def test_invalid_scope(self):
        data = {**MINIMAL, "units": [{**MINIMAL["units"][0], "scope": "invalid"}]}
        m = parse_dict(data)
        result = validate(m)
        assert any("scope" in e for e in result.errors)

    def test_unknown_depends_on(self):
        data = {
            **MINIMAL,
            "units": [{**MINIMAL["units"][0], "depends_on": ["nonexistent"]}],
        }
        m = parse_dict(data)
        result = validate(m)
        assert any("nonexistent" in w for w in result.warnings)

    def test_invalid_relationship_type(self):
        data = {
            **COMPLETE,
            "relationships": [{"from": "about", "to": "methodology", "type": "invented"}],
        }
        m = parse_dict(data)
        result = validate(m)
        assert any("type" in w for w in result.warnings)

    def test_relationship_unknown_unit(self):
        data = {
            **COMPLETE,
            "relationships": [{"from": "ghost", "to": "methodology", "type": "enables"}],
        }
        m = parse_dict(data)
        result = validate(m)
        assert any("ghost" in w for w in result.warnings)


class TestPathTraversalValidation:
    def test_safe_relative_path(self):
        assert _validate_unit_path("docs/guide.md") == "docs/guide.md"

    def test_safe_flat_path(self):
        assert _validate_unit_path("README.md") == "README.md"

    def test_none_passes_through(self):
        assert _validate_unit_path(None) is None

    def test_absolute_path_rejected(self):
        with pytest.raises(ValueError, match="relative"):
            _validate_unit_path("/etc/passwd")

    def test_traversal_rejected(self):
        with pytest.raises(ValueError, match="escapes"):
            _validate_unit_path("../../etc/shadow")

    def test_single_dotdot_rejected(self):
        with pytest.raises(ValueError, match="escapes"):
            _validate_unit_path("../sibling.md")

    def test_traversal_in_parse_dict_rejected(self):
        data = {**MINIMAL, "units": [{**MINIMAL["units"][0], "path": "../../.env"}]}
        with pytest.raises(ValueError, match="escapes"):
            parse_dict(data)
