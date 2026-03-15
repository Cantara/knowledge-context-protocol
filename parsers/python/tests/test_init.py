import os
import tempfile
from pathlib import Path

import pytest
import yaml

from kcp.init import (
    _detect_project_name,
    _discover_artifacts,
    _extract_triggers,
    _slugify,
    _token_estimate,
    _unique_id,
    generate_manifest,
    run_init,
)


@pytest.fixture
def project_dir(tmp_path):
    """Create a minimal project directory with common artifacts."""
    (tmp_path / "README.md").write_text(
        "# My Test Project\n\nThis is a test project for KCP.\n\n## Getting Started\n\nRun it.\n"
    )
    docs = tmp_path / "docs"
    docs.mkdir()
    (docs / "architecture.md").write_text(
        "# Architecture\n\nThe system uses a layered architecture.\n"
    )
    return tmp_path


class TestSlugify:
    def test_simple_name(self):
        assert _slugify("README.md") == "readme"

    def test_hyphenated_name(self):
        assert _slugify("copilot-instructions.md") == "copilot-instructions"

    def test_dots_and_spaces(self):
        assert _slugify("my file.name.txt") == "my-file-name"


class TestUniqueId:
    def test_first_use(self):
        seen = set()
        assert _unique_id("readme", seen) == "readme"
        assert "readme" in seen

    def test_duplicate_gets_suffix(self):
        seen = {"readme"}
        assert _unique_id("readme", seen) == "readme-2"

    def test_triple_duplicate(self):
        seen = {"readme", "readme-2"}
        assert _unique_id("readme", seen) == "readme-3"


class TestDetectProjectName:
    def test_from_package_json(self, tmp_path):
        (tmp_path / "package.json").write_text('{"name": "my-npm-project"}')
        assert _detect_project_name(tmp_path) == "my-npm-project"

    def test_from_pom_xml(self, tmp_path):
        (tmp_path / "pom.xml").write_text(
            '<project><artifactId>my-maven-project</artifactId></project>'
        )
        assert _detect_project_name(tmp_path) == "my-maven-project"

    def test_fallback_to_dir_name(self, tmp_path):
        assert _detect_project_name(tmp_path) == tmp_path.name


class TestDiscoverArtifacts:
    def test_finds_readme(self, project_dir):
        artifacts = _discover_artifacts(project_dir)
        rel_paths = [str(a.relative_to(project_dir)) for a in artifacts]
        assert "README.md" in rel_paths

    def test_finds_docs(self, project_dir):
        artifacts = _discover_artifacts(project_dir)
        rel_paths = [str(a.relative_to(project_dir)) for a in artifacts]
        assert any("architecture.md" in p for p in rel_paths)

    def test_empty_dir(self, tmp_path):
        assert _discover_artifacts(tmp_path) == []


class TestGenerateManifest:
    def test_level1_generates_valid_yaml(self, project_dir):
        content, artifacts = generate_manifest(project_dir, level=1)
        data = yaml.safe_load(content)
        assert data["kcp_version"] == "0.11"
        assert data["version"] == "0.1.0"
        assert len(data["units"]) == len(artifacts)
        for unit in data["units"]:
            assert "id" in unit
            assert "path" in unit
            assert "intent" in unit
            assert "scope" in unit
            assert "audience" in unit

    def test_level1_no_validated(self, project_dir):
        content, _ = generate_manifest(project_dir, level=1)
        data = yaml.safe_load(content)
        for unit in data["units"]:
            assert "validated" not in unit
            assert "hints" not in unit

    def test_level2_has_validated_and_hints(self, project_dir):
        content, _ = generate_manifest(project_dir, level=2)
        data = yaml.safe_load(content)
        for unit in data["units"]:
            assert "validated" in unit
            assert "hints" in unit
            assert "token_estimate" in unit["hints"]

    def test_level3_has_triggers(self, project_dir):
        content, _ = generate_manifest(project_dir, level=3)
        data = yaml.safe_load(content)
        # At least one unit should have triggers
        has_triggers = any("triggers" in u for u in data["units"])
        assert has_triggers

    def test_project_name_detected(self, project_dir):
        content, _ = generate_manifest(project_dir, level=1)
        data = yaml.safe_load(content)
        assert data["project"] == project_dir.name

    def test_description_extracted(self, project_dir):
        content, _ = generate_manifest(project_dir, level=1)
        data = yaml.safe_load(content)
        assert "description" in data
        assert "test project" in data["description"].lower()

    def test_unique_ids(self, tmp_path):
        """Two files with the same base name get unique ids."""
        (tmp_path / "README.md").write_text("# Top-level\n")
        docs = tmp_path / "docs"
        docs.mkdir()
        (docs / "README.md").write_text("# Docs readme\n")
        content, _ = generate_manifest(tmp_path, level=1)
        data = yaml.safe_load(content)
        ids = [u["id"] for u in data["units"]]
        assert len(ids) == len(set(ids)), f"Duplicate ids: {ids}"


class TestRunInit:
    def test_creates_knowledge_yaml(self, project_dir):
        result = run_init(project_dir, level=1)
        assert result == 0
        assert (project_dir / "knowledge.yaml").exists()

    def test_refuses_to_overwrite(self, project_dir):
        (project_dir / "knowledge.yaml").write_text("existing: content\n")
        result = run_init(project_dir, level=1)
        assert result == 1
        # Content should be unchanged
        assert (project_dir / "knowledge.yaml").read_text() == "existing: content\n"

    def test_force_overwrites(self, project_dir):
        (project_dir / "knowledge.yaml").write_text("existing: content\n")
        result = run_init(project_dir, level=1, force=True)
        assert result == 0
        content = (project_dir / "knowledge.yaml").read_text()
        assert "kcp_version" in content

    def test_scan_prints_files(self, project_dir, capsys):
        result = run_init(project_dir, level=1, scan=True)
        assert result == 0
        captured = capsys.readouterr()
        assert "Scanning project..." in captured.out
        assert "README.md" in captured.out

    def test_output_is_parseable(self, project_dir):
        run_init(project_dir, level=2)
        data = yaml.safe_load((project_dir / "knowledge.yaml").read_text())
        assert data["kcp_version"] == "0.11"
        assert len(data["units"]) > 0


class TestExtractTriggers:
    def test_extracts_from_filename(self, tmp_path):
        f = tmp_path / "architecture.md"
        f.write_text("# Architecture Overview\n\nSome content.\n")
        triggers = _extract_triggers(f)
        assert "architecture" in triggers

    def test_limits_to_three(self, tmp_path):
        f = tmp_path / "very-long-name-with-many-parts.md"
        f.write_text("# One Two Three Four Five\n")
        triggers = _extract_triggers(f)
        assert len(triggers) <= 3
