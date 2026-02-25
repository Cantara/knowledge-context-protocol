import pytest
from datetime import date
from kcp import parse_dict, validate
from kcp.model import KnowledgeManifest, KnowledgeUnit, Relationship


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

COMPLETE = {
    "project": "wiki.example.org",
    "version": "1.0.0",
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
        assert len(m.units) == 1
        assert m.units[0].id == "overview"

    def test_complete_parse(self):
        m = parse_dict(COMPLETE)
        assert m.project == "wiki.example.org"
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
    def test_valid_minimal(self):
        m = parse_dict(MINIMAL)
        assert validate(m) == []

    def test_valid_complete(self):
        m = parse_dict(COMPLETE)
        assert validate(m) == []

    def test_missing_project(self):
        data = {**MINIMAL, "project": ""}
        m = parse_dict(data)
        errors = validate(m)
        assert any("project" in e for e in errors)

    def test_invalid_scope(self):
        data = {**MINIMAL, "units": [{**MINIMAL["units"][0], "scope": "invalid"}]}
        m = parse_dict(data)
        errors = validate(m)
        assert any("scope" in e for e in errors)

    def test_unknown_depends_on(self):
        data = {
            **MINIMAL,
            "units": [{**MINIMAL["units"][0], "depends_on": ["nonexistent"]}],
        }
        m = parse_dict(data)
        errors = validate(m)
        assert any("nonexistent" in e for e in errors)

    def test_invalid_relationship_type(self):
        data = {
            **COMPLETE,
            "relationships": [{"from": "about", "to": "methodology", "type": "invented"}],
        }
        m = parse_dict(data)
        errors = validate(m)
        assert any("type" in e for e in errors)

    def test_relationship_unknown_unit(self):
        data = {
            **COMPLETE,
            "relationships": [{"from": "ghost", "to": "methodology", "type": "enables"}],
        }
        m = parse_dict(data)
        errors = validate(m)
        assert any("ghost" in e for e in errors)
