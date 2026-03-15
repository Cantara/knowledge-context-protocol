import os
import time
from pathlib import Path

import pytest

from kcp.reflect import _find_recent_skills, _find_stale_skills, run_reflect


@pytest.fixture
def skills_dir(tmp_path):
    """Create a mock skills directory with a few yaml files."""
    skills = tmp_path / "skills"
    skills.mkdir()
    (skills / "auth-flow.yaml").write_text("trigger: auth")
    (skills / "db-migrations.yaml").write_text("trigger: migration")
    return skills


class TestFindRecentSkills:
    def test_finds_recently_modified(self, skills_dir):
        # Touch one file now
        (skills_dir / "auth-flow.yaml").touch()
        recent = _find_recent_skills(skills_dir, within_minutes=5)
        names = [p.name for p, _ in recent]
        assert "auth-flow.yaml" in names

    def test_excludes_old_files(self, skills_dir):
        # Set mtime to 3 hours ago
        old_time = time.time() - 3 * 3600
        os.utime(skills_dir / "auth-flow.yaml", (old_time, old_time))
        recent = _find_recent_skills(skills_dir, within_minutes=60)
        names = [p.name for p, _ in recent]
        assert "auth-flow.yaml" not in names

    def test_empty_dir(self, tmp_path):
        empty = tmp_path / "empty"
        empty.mkdir()
        assert _find_recent_skills(empty) == []

    def test_missing_dir(self, tmp_path):
        assert _find_recent_skills(tmp_path / "nonexistent") == []


class TestFindStaleSkills:
    def test_finds_stale_files(self, skills_dir):
        old_time = time.time() - 30 * 86400  # 30 days ago
        os.utime(skills_dir / "db-migrations.yaml", (old_time, old_time))
        stale = _find_stale_skills(skills_dir, stale_days=21)
        names = [p.name for p, _ in stale]
        assert "db-migrations.yaml" in names

    def test_excludes_recent_files(self, skills_dir):
        (skills_dir / "auth-flow.yaml").touch()
        stale = _find_stale_skills(skills_dir, stale_days=21)
        names = [p.name for p, _ in stale]
        assert "auth-flow.yaml" not in names

    def test_limits_to_five(self, tmp_path):
        skills = tmp_path / "skills"
        skills.mkdir()
        old_time = time.time() - 30 * 86400
        for i in range(8):
            f = skills / f"skill-{i:02d}.yaml"
            f.write_text("trigger: x")
            os.utime(f, (old_time, old_time))
        stale = _find_stale_skills(skills, stale_days=21)
        assert len(stale) <= 5


class TestRunReflect:
    def test_exits_zero(self, skills_dir, capsys):
        result = run_reflect(skills_dir=skills_dir)
        assert result == 0

    def test_prints_checklist(self, skills_dir, capsys):
        run_reflect(skills_dir=skills_dir)
        out = capsys.readouterr().out
        assert "Session close checklist" in out
        assert "repeated pattern" in out
        assert "skill" in out.lower()

    def test_prints_template_reminder(self, skills_dir, capsys):
        run_reflect(skills_dir=skills_dir)
        out = capsys.readouterr().out
        assert "narrow trigger" in out
        assert "do_not_use_for" in out

    def test_log_writes_file(self, skills_dir, tmp_path, capsys, monkeypatch):
        monkeypatch.chdir(tmp_path)
        run_reflect(skills_dir=skills_dir, log=True, session_notes="test session")
        log_path = tmp_path / ".kcp" / "reflect-log.md"
        assert log_path.exists()
        content = log_path.read_text()
        assert "test session" in content

    def test_missing_skills_dir_still_runs(self, tmp_path, capsys):
        result = run_reflect(skills_dir=tmp_path / "nonexistent")
        assert result == 0
        out = capsys.readouterr().out
        assert "checklist" in out.lower()
