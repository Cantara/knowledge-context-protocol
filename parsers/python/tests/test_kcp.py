import os
import tempfile

import pytest
from datetime import date
from kcp import parse_dict, validate, ValidationResult
from kcp.model import KnowledgeManifest, KnowledgeUnit, Relationship
from kcp.parser import _validate_unit_path
from kcp.validator import _detect_cycles


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

MINIMAL_WITH_KCP_VERSION = {**MINIMAL, "kcp_version": "0.3"}

COMPLETE = {
    "project": "wiki.example.org",
    "version": "1.0.0",
    "kcp_version": "0.3",
    "updated": "2026-02-25",
    "language": "en",
    "license": "Apache-2.0",
    "indexing": "open",
    "units": [
        {
            "id": "about",
            "path": "about.md",
            "intent": "Who maintains this?",
            "scope": "global",
            "audience": ["human", "agent"],
            "validated": "2026-02-24",
            "update_frequency": "monthly",
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
            "language": "en",
            "format": "markdown",
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
        {
            "id": "api-spec",
            "path": "api/openapi.yaml",
            "kind": "schema",
            "intent": "What endpoints does the API expose?",
            "format": "openapi",
            "content_type": "application/vnd.oai.openapi+yaml;version=3.1",
            "scope": "module",
            "audience": ["developer", "agent"],
            "validated": "2026-02-20",
            "indexing": "no-train",
            "license": {
                "spdx": "CC-BY-4.0",
                "url": "https://creativecommons.org/licenses/by/4.0/",
                "attribution_required": True,
            },
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
        assert m.kcp_version == "0.3"

    def test_missing_version_does_not_crash(self):
        data = {"project": "test", "units": MINIMAL["units"]}
        m = parse_dict(data)
        assert m.project == "test"
        assert m.version == ""

    def test_complete_parse(self):
        m = parse_dict(COMPLETE)
        assert m.project == "wiki.example.org"
        assert m.kcp_version == "0.3"
        assert m.updated == date(2026, 2, 25)
        assert m.language == "en"
        assert m.license == "Apache-2.0"
        assert m.indexing == "open"
        assert len(m.units) == 4
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

    def test_kind_parsed(self):
        m = parse_dict(COMPLETE)
        api_spec = next(u for u in m.units if u.id == "api-spec")
        assert api_spec.kind == "schema"
        # Default is None (treated as knowledge)
        about = next(u for u in m.units if u.id == "about")
        assert about.kind is None

    def test_format_parsed(self):
        m = parse_dict(COMPLETE)
        api_spec = next(u for u in m.units if u.id == "api-spec")
        assert api_spec.format == "openapi"
        methodology = next(u for u in m.units if u.id == "methodology")
        assert methodology.format == "markdown"

    def test_content_type_parsed(self):
        m = parse_dict(COMPLETE)
        api_spec = next(u for u in m.units if u.id == "api-spec")
        assert api_spec.content_type == "application/vnd.oai.openapi+yaml;version=3.1"

    def test_language_parsed(self):
        m = parse_dict(COMPLETE)
        assert m.language == "en"
        methodology = next(u for u in m.units if u.id == "methodology")
        assert methodology.language == "en"

    def test_license_string_parsed(self):
        m = parse_dict(COMPLETE)
        assert m.license == "Apache-2.0"

    def test_license_object_parsed(self):
        m = parse_dict(COMPLETE)
        api_spec = next(u for u in m.units if u.id == "api-spec")
        assert isinstance(api_spec.license, dict)
        assert api_spec.license["spdx"] == "CC-BY-4.0"
        assert api_spec.license["attribution_required"] is True

    def test_update_frequency_parsed(self):
        m = parse_dict(COMPLETE)
        about = next(u for u in m.units if u.id == "about")
        assert about.update_frequency == "monthly"

    def test_indexing_parsed(self):
        m = parse_dict(COMPLETE)
        assert m.indexing == "open"
        api_spec = next(u for u in m.units if u.id == "api-spec")
        assert api_spec.indexing == "no-train"

    def test_indexing_object_parsed(self):
        data = {
            **MINIMAL,
            "kcp_version": "0.3",
            "units": [{
                **MINIMAL["units"][0],
                "indexing": {"allow": ["read", "index"], "deny": ["train"]},
            }],
        }
        m = parse_dict(data)
        assert isinstance(m.units[0].indexing, dict)
        assert m.units[0].indexing["allow"] == ["read", "index"]


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

    def test_duplicate_id_produces_warning(self):
        data = {
            **MINIMAL,
            "kcp_version": "0.3",
            "units": [
                MINIMAL["units"][0],
                {**MINIMAL["units"][0], "path": "other.md", "intent": "Duplicate"},
            ],
        }
        m = parse_dict(data)
        result = validate(m)
        assert result.is_valid  # warning, not error
        assert any("duplicate" in w for w in result.warnings)

    def test_invalid_id_format_produces_warning(self):
        data = {
            **MINIMAL,
            "kcp_version": "0.3",
            "units": [{**MINIMAL["units"][0], "id": "Has_Uppercase!"}],
        }
        m = parse_dict(data)
        result = validate(m)
        assert any("id" in w and "lowercase" in w for w in result.warnings)

    def test_valid_id_formats(self):
        for valid_id in ["overview", "my-unit", "v2.0", "a.b-c.1"]:
            data = {
                **MINIMAL,
                "kcp_version": "0.3",
                "units": [{**MINIMAL["units"][0], "id": valid_id}],
            }
            m = parse_dict(data)
            result = validate(m)
            assert not any("lowercase" in w for w in result.warnings), f"ID '{valid_id}' flagged incorrectly"

    def test_trigger_exceeding_60_chars_produces_warning(self):
        long_trigger = "a" * 61
        data = {
            **MINIMAL,
            "kcp_version": "0.3",
            "units": [{**MINIMAL["units"][0], "triggers": [long_trigger]}],
        }
        m = parse_dict(data)
        result = validate(m)
        assert any("exceeds" in w for w in result.warnings)

    def test_more_than_20_triggers_produces_warning(self):
        data = {
            **MINIMAL,
            "kcp_version": "0.3",
            "units": [{**MINIMAL["units"][0], "triggers": [f"t{i}" for i in range(25)]}],
        }
        m = parse_dict(data)
        result = validate(m)
        assert any("more than 20" in w for w in result.warnings)

    # -- v0.3 field validation tests --

    def test_unknown_kind_produces_warning(self):
        data = {
            **MINIMAL,
            "kcp_version": "0.3",
            "units": [{**MINIMAL["units"][0], "kind": "imaginary"}],
        }
        m = parse_dict(data)
        result = validate(m)
        assert result.is_valid
        assert any("kind" in w and "imaginary" in w for w in result.warnings)

    def test_valid_kind_no_warning(self):
        for kind in ["knowledge", "schema", "service", "policy", "executable"]:
            data = {
                **MINIMAL,
                "kcp_version": "0.3",
                "units": [{**MINIMAL["units"][0], "kind": kind}],
            }
            m = parse_dict(data)
            result = validate(m)
            assert not any("kind" in w for w in result.warnings), f"kind '{kind}' flagged incorrectly"

    def test_unknown_format_produces_warning(self):
        data = {
            **MINIMAL,
            "kcp_version": "0.3",
            "units": [{**MINIMAL["units"][0], "format": "docx"}],
        }
        m = parse_dict(data)
        result = validate(m)
        assert result.is_valid
        assert any("format" in w and "docx" in w for w in result.warnings)

    def test_valid_format_no_warning(self):
        for fmt in ["markdown", "pdf", "openapi", "json-schema", "jupyter", "html", "text"]:
            data = {
                **MINIMAL,
                "kcp_version": "0.3",
                "units": [{**MINIMAL["units"][0], "format": fmt}],
            }
            m = parse_dict(data)
            result = validate(m)
            assert not any("format" in w for w in result.warnings), f"format '{fmt}' flagged incorrectly"

    def test_unknown_update_frequency_produces_warning(self):
        data = {
            **MINIMAL,
            "kcp_version": "0.3",
            "units": [{**MINIMAL["units"][0], "update_frequency": "biweekly"}],
        }
        m = parse_dict(data)
        result = validate(m)
        assert result.is_valid
        assert any("update_frequency" in w and "biweekly" in w for w in result.warnings)

    def test_valid_update_frequency_no_warning(self):
        for freq in ["hourly", "daily", "weekly", "monthly", "rarely", "never"]:
            data = {
                **MINIMAL,
                "kcp_version": "0.3",
                "units": [{**MINIMAL["units"][0], "update_frequency": freq}],
            }
            m = parse_dict(data)
            result = validate(m)
            assert not any("update_frequency" in w for w in result.warnings)

    def test_unknown_indexing_shorthand_produces_warning(self):
        data = {
            **MINIMAL,
            "kcp_version": "0.3",
            "units": [{**MINIMAL["units"][0], "indexing": "custom-unknown"}],
        }
        m = parse_dict(data)
        result = validate(m)
        assert result.is_valid
        assert any("indexing" in w and "custom-unknown" in w for w in result.warnings)

    def test_valid_indexing_shorthand_no_warning(self):
        for idx in ["open", "read-only", "no-train", "none"]:
            data = {
                **MINIMAL,
                "kcp_version": "0.3",
                "units": [{**MINIMAL["units"][0], "indexing": idx}],
            }
            m = parse_dict(data)
            result = validate(m)
            assert not any("indexing" in w for w in result.warnings)

    def test_indexing_object_no_warning(self):
        """Structured indexing objects should not produce warnings."""
        data = {
            **MINIMAL,
            "kcp_version": "0.3",
            "units": [{
                **MINIMAL["units"][0],
                "indexing": {"allow": ["read", "index"], "deny": ["train"]},
            }],
        }
        m = parse_dict(data)
        result = validate(m)
        assert not any("indexing" in w for w in result.warnings)

    def test_v02_kcp_version_accepted(self):
        """v0.2 manifests should be accepted without warning."""
        m = parse_dict({**MINIMAL, "kcp_version": "0.2"})
        result = validate(m)
        assert result.is_valid
        assert not any("kcp_version" in w for w in result.warnings)


class TestCycleDetection:
    """Tests for depends_on cycle detection (SPEC.md §4.7)."""

    def test_no_cycle_returns_empty(self):
        """A linear dependency chain has no cycles."""
        units = [
            KnowledgeUnit(id="a", path="a.md", intent="A", scope="global", audience=["agent"]),
            KnowledgeUnit(id="b", path="b.md", intent="B", scope="global", audience=["agent"], depends_on=["a"]),
            KnowledgeUnit(id="c", path="c.md", intent="C", scope="global", audience=["agent"], depends_on=["b"]),
        ]
        cycle_edges = _detect_cycles(units)
        assert cycle_edges == set()

    def test_simple_two_node_cycle(self):
        """A depends on B, B depends on A — one cycle-closing edge detected."""
        units = [
            KnowledgeUnit(id="a", path="a.md", intent="A", scope="global", audience=["agent"], depends_on=["b"]),
            KnowledgeUnit(id="b", path="b.md", intent="B", scope="global", audience=["agent"], depends_on=["a"]),
        ]
        cycle_edges = _detect_cycles(units)
        assert len(cycle_edges) == 1

    def test_three_node_cycle(self):
        """A -> B -> C -> A — one cycle-closing edge detected."""
        units = [
            KnowledgeUnit(id="a", path="a.md", intent="A", scope="global", audience=["agent"], depends_on=["b"]),
            KnowledgeUnit(id="b", path="b.md", intent="B", scope="global", audience=["agent"], depends_on=["c"]),
            KnowledgeUnit(id="c", path="c.md", intent="C", scope="global", audience=["agent"], depends_on=["a"]),
        ]
        cycle_edges = _detect_cycles(units)
        assert len(cycle_edges) >= 1

    def test_self_cycle(self):
        """A unit that depends on itself — self-cycle detected."""
        units = [
            KnowledgeUnit(id="a", path="a.md", intent="A", scope="global", audience=["agent"], depends_on=["a"]),
        ]
        cycle_edges = _detect_cycles(units)
        assert ("a", "a") in cycle_edges

    def test_cycle_does_not_cause_validation_error(self):
        """Per §4.7, cycles are silently ignored — no error, no warning required."""
        data = {
            **MINIMAL,
            "kcp_version": "0.3",
            "units": [
                {"id": "a", "path": "a.md", "intent": "A", "scope": "global", "audience": ["agent"], "depends_on": ["b"]},
                {"id": "b", "path": "b.md", "intent": "B", "scope": "global", "audience": ["agent"], "depends_on": ["a"]},
            ],
        }
        m = parse_dict(data)
        result = validate(m)
        assert result.is_valid
        assert result.errors == []

    def test_cycle_with_non_cyclic_units_mixed(self):
        """A graph with both cyclic and non-cyclic units validates correctly."""
        data = {
            **MINIMAL,
            "kcp_version": "0.3",
            "units": [
                {"id": "root", "path": "root.md", "intent": "Root", "scope": "global", "audience": ["agent"]},
                {"id": "a", "path": "a.md", "intent": "A", "scope": "global", "audience": ["agent"], "depends_on": ["root", "b"]},
                {"id": "b", "path": "b.md", "intent": "B", "scope": "global", "audience": ["agent"], "depends_on": ["a"]},
            ],
        }
        m = parse_dict(data)
        result = validate(m)
        assert result.is_valid


class TestPathExistenceChecking:
    """Tests for path existence warnings (SPEC.md §4.3 / §7)."""

    def test_existing_path_no_warning(self):
        """When path exists, no warning is produced."""
        with tempfile.TemporaryDirectory() as tmpdir:
            readme = os.path.join(tmpdir, "README.md")
            with open(readme, "w") as f:
                f.write("# Test")

            data = {
                **MINIMAL,
                "kcp_version": "0.3",
                "units": [{**MINIMAL["units"][0], "path": "README.md"}],
            }
            m = parse_dict(data)
            result = validate(m, manifest_dir=tmpdir)
            assert result.is_valid
            assert not any("does not exist" in w for w in result.warnings)

    def test_missing_path_produces_warning(self):
        """When path does not exist, a warning is produced."""
        with tempfile.TemporaryDirectory() as tmpdir:
            data = {
                **MINIMAL,
                "kcp_version": "0.3",
                "units": [{**MINIMAL["units"][0], "path": "nonexistent.md"}],
            }
            m = parse_dict(data)
            result = validate(m, manifest_dir=tmpdir)
            assert result.is_valid  # warning, not error
            assert any("does not exist" in w for w in result.warnings)

    def test_missing_nested_path_produces_warning(self):
        """A nested path like docs/guide.md also produces a warning when missing."""
        with tempfile.TemporaryDirectory() as tmpdir:
            data = {
                **MINIMAL,
                "kcp_version": "0.3",
                "units": [{**MINIMAL["units"][0], "path": "docs/guide.md"}],
            }
            m = parse_dict(data)
            result = validate(m, manifest_dir=tmpdir)
            assert result.is_valid
            assert any("does not exist" in w for w in result.warnings)

    def test_no_path_check_without_manifest_dir(self):
        """When manifest_dir is None, no path existence check is performed."""
        data = {
            **MINIMAL,
            "kcp_version": "0.3",
            "units": [{**MINIMAL["units"][0], "path": "definitely-does-not-exist.md"}],
        }
        m = parse_dict(data)
        result = validate(m)  # no manifest_dir
        assert result.is_valid
        assert not any("does not exist" in w for w in result.warnings)

    def test_multiple_units_mixed_existence(self):
        """One existing and one missing path: only the missing one gets a warning."""
        with tempfile.TemporaryDirectory() as tmpdir:
            readme = os.path.join(tmpdir, "exists.md")
            with open(readme, "w") as f:
                f.write("# Exists")

            data = {
                **MINIMAL,
                "kcp_version": "0.3",
                "units": [
                    {**MINIMAL["units"][0], "id": "exists", "path": "exists.md"},
                    {**MINIMAL["units"][0], "id": "missing", "path": "missing.md"},
                ],
            }
            m = parse_dict(data)
            result = validate(m, manifest_dir=tmpdir)
            assert result.is_valid
            path_warnings = [w for w in result.warnings if "does not exist" in w]
            assert len(path_warnings) == 1
            assert "missing.md" in path_warnings[0]


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
