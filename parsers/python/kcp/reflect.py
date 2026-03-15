"""Session-end skill lifecycle reflection checklist."""

from datetime import datetime
from pathlib import Path
from typing import Optional


_CHECKLIST = [
    "Did this session reveal a repeated pattern?",
    "Is there a skill that should be updated or created?",
    "Are any skills now overlapping or stale?",
    "Did you remove duplicated guidance elsewhere?",
]

_SKILL_TEMPLATE_REMINDER = """A well-maintained skill should have:
  - narrow trigger  (one clear entry condition)
  - do_not_use_for  (explicit exclusion list)
  - lessons_learned (short notes from real sessions)
  - owner           (who maintains this, or "shared")
"""


def _find_recent_skills(
    skills_dir: Path, within_minutes: int = 120
) -> list[tuple[Path, float]]:
    """Return skills modified within the last N minutes, sorted newest first."""
    if not skills_dir.exists():
        return []
    now = datetime.now().timestamp()
    cutoff = now - within_minutes * 60
    recent = []
    for f in sorted(skills_dir.glob("*.yaml")):
        mtime = f.stat().st_mtime
        if mtime >= cutoff:
            age_min = (now - mtime) / 60
            recent.append((f, age_min))
    return sorted(recent, key=lambda x: x[1])


def _find_stale_skills(
    skills_dir: Path, stale_days: int = 21
) -> list[tuple[Path, int]]:
    """Return skills not modified in more than N days, up to 5."""
    if not skills_dir.exists():
        return []
    now = datetime.now().timestamp()
    cutoff = now - stale_days * 86400
    stale = []
    for f in sorted(skills_dir.glob("*.yaml")):
        mtime = f.stat().st_mtime
        if mtime < cutoff:
            age_days = int((now - mtime) / 86400)
            stale.append((f, age_days))
    return stale[:5]


def run_reflect(
    skills_dir: Optional[Path] = None,
    log: bool = False,
    session_notes: str = "",
) -> int:
    """Run the reflection checklist. Returns exit code."""
    if skills_dir is None:
        skills_dir = Path.home() / ".claude" / "skills"

    print("KCP Skill Lifecycle Reflection")
    print("=" * 40)
    print()

    recent = _find_recent_skills(skills_dir)
    if recent:
        print("Skills modified in the last 2 hours:")
        for path, age_min in recent:
            print(f"  \u2192 {path.name}  ({age_min:.0f} min ago)")
        print()

    stale = _find_stale_skills(skills_dir)
    if stale:
        print("Potentially stale skills (not updated in 3+ weeks):")
        for path, age_days in stale:
            print(f"  \u26a0  {path.name}  ({age_days} days ago \u2014 consider reviewing)")
        print()

    print("Session close checklist:")
    for question in _CHECKLIST:
        print(f"  [ ] {question}")
    print()

    print(_SKILL_TEMPLATE_REMINDER.rstrip())
    print()

    if log:
        log_path = Path(".kcp") / "reflect-log.md"
        log_path.parent.mkdir(exist_ok=True)
        timestamp = datetime.now().strftime("%Y-%m-%d %H:%M")
        lines = [f"\n## {timestamp}\n"]
        if session_notes:
            lines.append(f"{session_notes}\n")
        if recent:
            lines.append("**Skills touched:**")
            for path, _ in recent:
                lines.append(f"- {path.name}")
            lines.append("")
        with open(log_path, "a") as f:
            f.write("\n".join(lines) + "\n")
        print(f"Appended reflection entry to {log_path}")

    return 0
