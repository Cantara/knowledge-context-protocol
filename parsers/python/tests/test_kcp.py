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


class TestDelegationParsing:
    """Tests for v0.7 delegation block parsing."""

    def test_parses_root_delegation(self):
        data = {
            **MINIMAL,
            "delegation": {
                "max_depth": 2,
                "require_capability_attenuation": True,
                "audit_chain": False,
                "human_in_the_loop": {"required": True, "approval_mechanism": "oauth_consent"},
            },
        }
        m = parse_dict(data)
        assert m.delegation is not None
        assert m.delegation.max_depth == 2
        assert m.delegation.require_capability_attenuation is True
        assert m.delegation.audit_chain is False
        assert m.delegation.human_in_the_loop == {"required": True, "approval_mechanism": "oauth_consent"}

    def test_parses_unit_delegation_override(self):
        data = {
            **MINIMAL,
            "units": [{
                **MINIMAL["units"][0],
                "delegation": {
                    "max_depth": 0,
                    "human_in_the_loop": {"required": False, "approval_mechanism": "uma"},
                },
            }],
        }
        m = parse_dict(data)
        u = m.units[0]
        assert u.delegation is not None
        assert u.delegation.max_depth == 0
        assert u.delegation.human_in_the_loop == {"required": False, "approval_mechanism": "uma"}
        assert u.delegation.require_capability_attenuation is None

    def test_absent_delegation_is_none(self):
        m = parse_dict(MINIMAL)
        assert m.delegation is None
        assert m.units[0].delegation is None

    def test_delegation_max_depth_zero_no_delegation(self):
        """max_depth=0 means no delegation is allowed — parsed correctly as integer 0."""
        data = {
            **MINIMAL,
            "delegation": {"max_depth": 0},
        }
        m = parse_dict(data)
        assert m.delegation is not None
        assert m.delegation.max_depth == 0


class TestComplianceParsing:
    """Tests for v0.7 compliance block parsing."""

    def test_parses_root_compliance(self):
        data = {
            **MINIMAL,
            "compliance": {
                "data_residency": ["EU", "NO"],
                "sensitivity": "confidential",
                "regulations": ["GDPR", "NIS2"],
                "restrictions": ["no_ai_training", "no_cross_border"],
            },
        }
        m = parse_dict(data)
        assert m.compliance is not None
        assert m.compliance.data_residency == ["EU", "NO"]
        assert m.compliance.sensitivity == "confidential"
        assert m.compliance.regulations == ["GDPR", "NIS2"]
        assert m.compliance.restrictions == ["no_ai_training", "no_cross_border"]

    def test_parses_unit_compliance_override(self):
        data = {
            **MINIMAL,
            "units": [{
                **MINIMAL["units"][0],
                "compliance": {
                    "sensitivity": "restricted",
                    "regulations": ["AML5D"],
                },
            }],
        }
        m = parse_dict(data)
        u = m.units[0]
        assert u.compliance is not None
        assert u.compliance.sensitivity == "restricted"
        assert u.compliance.regulations == ["AML5D"]
        assert u.compliance.data_residency == []
        assert u.compliance.restrictions == []

    def test_absent_compliance_is_none(self):
        m = parse_dict(MINIMAL)
        assert m.compliance is None
        assert m.units[0].compliance is None

    def test_compliance_empty_lists_default(self):
        """Compliance with only sensitivity — other lists default to empty."""
        data = {
            **MINIMAL,
            "compliance": {"sensitivity": "internal"},
        }
        m = parse_dict(data)
        assert m.compliance is not None
        assert m.compliance.sensitivity == "internal"
        assert m.compliance.data_residency == []
        assert m.compliance.regulations == []
        assert m.compliance.restrictions == []


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


class TestTrustParsing:
    """Tests for trust block parsing (#10)."""

    def test_parses_root_trust(self):
        data = {
            **MINIMAL,
            "trust": {
                "provenance": {
                    "publisher": "Acme Corp",
                    "publisher_url": "https://acme.com",
                    "contact": "docs@acme.com",
                },
                "audit": {
                    "agent_must_log": True,
                    "require_trace_context": False,
                },
            },
        }
        m = parse_dict(data)
        assert m.trust is not None
        assert m.trust.provenance is not None
        assert m.trust.provenance.publisher == "Acme Corp"
        assert m.trust.provenance.publisher_url == "https://acme.com"
        assert m.trust.provenance.contact == "docs@acme.com"
        assert m.trust.audit is not None
        assert m.trust.audit.agent_must_log is True
        assert m.trust.audit.require_trace_context is False

    def test_absent_trust_is_none(self):
        m = parse_dict(MINIMAL)
        assert m.trust is None


class TestAuthParsing:
    """Tests for auth block parsing (#10)."""

    def test_parses_root_auth(self):
        data = {
            **MINIMAL,
            "auth": {
                "methods": [
                    {"type": "oauth2", "issuer": "https://auth.example.com", "scopes": ["read:docs"]},
                    {"type": "api_key", "header": "X-API-Key", "registration_url": "https://example.com/register"},
                    {"type": "none"},
                ],
            },
        }
        m = parse_dict(data)
        assert m.auth is not None
        assert len(m.auth.methods) == 3
        assert m.auth.methods[0].type == "oauth2"
        assert m.auth.methods[0].issuer == "https://auth.example.com"
        assert m.auth.methods[0].scopes == ["read:docs"]
        assert m.auth.methods[1].type == "api_key"
        assert m.auth.methods[1].header == "X-API-Key"
        assert m.auth.methods[2].type == "none"

    def test_absent_auth_is_none(self):
        m = parse_dict(MINIMAL)
        assert m.auth is None

    def test_warns_protected_units_without_auth(self):
        data = {
            **MINIMAL,
            "kcp_version": "0.7",
            "units": [{**MINIMAL["units"][0], "access": "restricted"}],
        }
        m = parse_dict(data)
        result = validate(m)
        assert any("auth" in w for w in result.warnings)


class TestHintsParsing:
    """Tests for hints block parsing (#10)."""

    def test_parses_unit_hints(self):
        data = {
            **MINIMAL,
            "units": [{
                **MINIMAL["units"][0],
                "hints": {
                    "token_estimate": 5000,
                    "load_strategy": "lazy",
                    "priority": "critical",
                    "summary_available": True,
                    "summary_unit": "overview-tldr",
                },
            }],
        }
        m = parse_dict(data)
        assert m.units[0].hints is not None
        assert m.units[0].hints["token_estimate"] == 5000
        assert m.units[0].hints["load_strategy"] == "lazy"
        assert m.units[0].hints["summary_available"] is True

    def test_parses_root_hints(self):
        data = {
            **MINIMAL,
            "hints": {
                "total_token_estimate": 50000,
                "unit_count": 5,
                "recommended_entry_point": "overview",
            },
        }
        m = parse_dict(data)
        assert m.hints is not None
        assert m.hints["total_token_estimate"] == 50000


class TestPaymentParsing:
    """Tests for payment block parsing (#10)."""

    def test_parses_root_payment(self):
        data = {**MINIMAL, "payment": {"default_tier": "free"}}
        m = parse_dict(data)
        assert m.payment is not None

    def test_parses_unit_payment(self):
        data = {
            **MINIMAL,
            "units": [{
                **MINIMAL["units"][0],
                "payment": {"default_tier": "metered"},
            }],
        }
        m = parse_dict(data)
        assert m.units[0].payment is not None

    def test_absent_payment_is_none(self):
        m = parse_dict(MINIMAL)
        assert m.payment is None


class TestDelegationComplianceValidation:
    """Tests for delegation/compliance validation (#9, #11)."""

    def test_invalid_hitl_produces_error(self):
        data = {**MINIMAL, "delegation": {"human_in_the_loop": {"required": True, "approval_mechanism": "invalid-value"}}}
        m = parse_dict(data)
        result = validate(m)
        assert not result.is_valid
        assert any("human_in_the_loop" in e for e in result.errors)

    def test_valid_hitl_values_accepted(self):
        for mech in ["oauth_consent", "uma", "custom"]:
            data = {**MINIMAL, "delegation": {"human_in_the_loop": {"required": True, "approval_mechanism": mech}}}
            m = parse_dict(data)
            result = validate(m)
            assert not any("human_in_the_loop" in e for e in result.errors), \
                f"human_in_the_loop approval_mechanism='{mech}' should be valid"

    def test_unit_max_depth_exceeding_root_produces_error(self):
        data = {
            **MINIMAL,
            "delegation": {"max_depth": 2},
            "units": [{**MINIMAL["units"][0], "delegation": {"max_depth": 5}}],
        }
        m = parse_dict(data)
        result = validate(m)
        assert not result.is_valid
        assert any("max_depth" in e for e in result.errors)

    def test_invalid_compliance_sensitivity_produces_error(self):
        data = {**MINIMAL, "compliance": {"sensitivity": "top-secret"}}
        m = parse_dict(data)
        result = validate(m)
        assert not result.is_valid
        assert any("compliance.sensitivity" in e for e in result.errors)

    def test_summary_available_without_summary_unit_warns(self):
        data = {
            **MINIMAL,
            "kcp_version": "0.7",
            "units": [{
                **MINIMAL["units"][0],
                "hints": {"summary_available": True},
            }],
        }
        m = parse_dict(data)
        result = validate(m)
        assert any("summary_available" in w and "summary_unit" in w for w in result.warnings)

    def test_chunk_index_without_chunk_of_warns(self):
        data = {
            **MINIMAL,
            "kcp_version": "0.7",
            "units": [{
                **MINIMAL["units"][0],
                "hints": {"chunk_index": 1},
            }],
        }
        m = parse_dict(data)
        result = validate(m)
        assert any("chunk_index" in w and "chunk_of" in w for w in result.warnings)


# ---------------------------------------------------------------------------
# Federation tests (§3.6, v0.9)
# ---------------------------------------------------------------------------


class TestFederation:
    """Tests for federation features: manifests block, external_depends_on,
    external_relationships, and governs relationship type."""

    def test_parses_manifests_block(self):
        data = {
            **MINIMAL,
            "kcp_version": "0.9",
            "manifests": [{
                "id": "platform",
                "url": "https://platform.example.com/knowledge.yaml",
                "label": "Platform Engineering",
                "relationship": "foundation",
                "update_frequency": "weekly",
                "local_mirror": "./mirrors/platform.yaml",
            }],
        }
        m = parse_dict(data)
        assert len(m.manifests) == 1
        assert m.manifests[0].id == "platform"
        assert m.manifests[0].url == "https://platform.example.com/knowledge.yaml"
        assert m.manifests[0].label == "Platform Engineering"
        assert m.manifests[0].relationship == "foundation"
        assert m.manifests[0].update_frequency == "weekly"
        assert m.manifests[0].local_mirror == "./mirrors/platform.yaml"

    def test_parses_manifest_ref_with_auth(self):
        data = {
            **MINIMAL,
            "kcp_version": "0.9",
            "manifests": [{
                "id": "secure",
                "url": "https://secure.example.com/knowledge.yaml",
                "auth": {"methods": [{"type": "api_key", "header": "X-KCP-Key"}]},
            }],
        }
        m = parse_dict(data)
        assert m.manifests[0].auth is not None
        assert len(m.manifests[0].auth.methods) == 1
        assert m.manifests[0].auth.methods[0].type == "api_key"

    def test_absent_manifests_block_is_empty_list(self):
        m = parse_dict(MINIMAL)
        assert m.manifests == []

    def test_parses_external_depends_on(self):
        data = {
            **MINIMAL,
            "units": [{
                **MINIMAL["units"][0],
                "external_depends_on": [{
                    "manifest": "security",
                    "unit": "gdpr-policy",
                    "on_failure": "degrade",
                }],
            }],
        }
        m = parse_dict(data)
        assert len(m.units[0].external_depends_on) == 1
        assert m.units[0].external_depends_on[0].manifest == "security"
        assert m.units[0].external_depends_on[0].unit == "gdpr-policy"
        assert m.units[0].external_depends_on[0].on_failure == "degrade"

    def test_external_depends_on_defaults_on_failure_to_skip(self):
        data = {
            **MINIMAL,
            "units": [{
                **MINIMAL["units"][0],
                "external_depends_on": [{"manifest": "platform", "unit": "api-guide"}],
            }],
        }
        m = parse_dict(data)
        assert m.units[0].external_depends_on[0].on_failure == "skip"

    def test_absent_external_depends_on_is_empty_list(self):
        m = parse_dict(MINIMAL)
        assert m.units[0].external_depends_on == []

    def test_parses_external_relationships(self):
        data = {
            **MINIMAL,
            "kcp_version": "0.9",
            "external_relationships": [{
                "from_manifest": "security",
                "from_unit": "gdpr-policy",
                "to_unit": "data-handling",
                "type": "governs",
            }],
        }
        m = parse_dict(data)
        assert len(m.external_relationships) == 1
        assert m.external_relationships[0].from_manifest == "security"
        assert m.external_relationships[0].from_unit == "gdpr-policy"
        assert m.external_relationships[0].to_manifest is None
        assert m.external_relationships[0].to_unit == "data-handling"
        assert m.external_relationships[0].type == "governs"

    def test_absent_external_relationships_is_empty_list(self):
        m = parse_dict(MINIMAL)
        assert m.external_relationships == []

    def test_governs_relationship_type_is_valid(self):
        data = {
            **MINIMAL,
            "kcp_version": "0.9",
            "relationships": [{"from": "overview", "to": "overview", "type": "governs"}],
        }
        m = parse_dict(data)
        result = validate(m)
        assert not any("governs" in w for w in result.warnings)

    def test_kcp_version_09_is_recognised(self):
        data = {**MINIMAL, "kcp_version": "0.9"}
        m = parse_dict(data)
        result = validate(m)
        assert not any("unknown kcp_version" in w for w in result.warnings)

    def test_manifest_id_must_match_pattern(self):
        data = {
            **MINIMAL,
            "manifests": [{"id": "INVALID ID!", "url": "https://example.com/knowledge.yaml"}],
        }
        m = parse_dict(data)
        result = validate(m)
        assert not result.is_valid
        assert any("INVALID ID!" in e for e in result.errors)

    def test_manifest_url_must_be_https(self):
        data = {
            **MINIMAL,
            "manifests": [{"id": "platform", "url": "http://insecure.example.com/knowledge.yaml"}],
        }
        m = parse_dict(data)
        result = validate(m)
        assert not result.is_valid
        assert any("HTTPS" in e for e in result.errors)

    def test_duplicate_manifest_id_produces_error(self):
        data = {
            **MINIMAL,
            "manifests": [
                {"id": "platform", "url": "https://a.example.com/knowledge.yaml"},
                {"id": "platform", "url": "https://b.example.com/knowledge.yaml"},
            ],
        }
        m = parse_dict(data)
        result = validate(m)
        assert not result.is_valid
        assert any("duplicate" in e for e in result.errors)

    def test_external_depends_on_unknown_manifest_warns(self):
        data = {
            **MINIMAL,
            "units": [{
                **MINIMAL["units"][0],
                "external_depends_on": [{"manifest": "nonexistent", "unit": "some-unit"}],
            }],
        }
        m = parse_dict(data)
        result = validate(m)
        assert any("nonexistent" in w for w in result.warnings)

    def test_external_relationships_unknown_manifest_warns(self):
        data = {
            **MINIMAL,
            "external_relationships": [{
                "from_manifest": "unknown-src",
                "from_unit": "a",
                "to_unit": "b",
                "type": "governs",
            }],
        }
        m = parse_dict(data)
        result = validate(m)
        assert any("unknown-src" in w for w in result.warnings)

    def test_full_federation_round_trip(self):
        data = {
            "kcp_version": "0.9",
            "project": "federation-test",
            "version": "1.0.0",
            "manifests": [{
                "id": "security",
                "url": "https://security.example.com/knowledge.yaml",
                "label": "Security Team",
                "relationship": "governs",
            }],
            "units": [{
                "id": "data-handling",
                "path": "data.md",
                "intent": "Data handling",
                "scope": "global",
                "audience": ["agent"],
                "external_depends_on": [{
                    "manifest": "security",
                    "unit": "gdpr-policy",
                    "on_failure": "warn",
                }],
            }],
            "external_relationships": [{
                "from_manifest": "security",
                "from_unit": "gdpr-policy",
                "to_unit": "data-handling",
                "type": "governs",
            }],
        }
        m = parse_dict(data)
        assert m.kcp_version == "0.9"
        assert len(m.manifests) == 1
        assert m.manifests[0].id == "security"
        assert len(m.units[0].external_depends_on) == 1
        assert m.units[0].external_depends_on[0].on_failure == "warn"
        assert len(m.external_relationships) == 1
        assert m.external_relationships[0].type == "governs"

        result = validate(m)
        assert result.is_valid, f"Expected valid: {result.errors}"
