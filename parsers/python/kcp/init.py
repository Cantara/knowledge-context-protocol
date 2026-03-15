"""Generate a starter knowledge.yaml by scanning the current directory."""

import json
import os
import re
from datetime import date
from pathlib import Path
from typing import Optional


# Well-known artifacts to scan for
_WELL_KNOWN_FILES = [
    "README.md",
    "AGENTS.md",
    "CLAUDE.md",
    ".github/copilot-instructions.md",
    "llms.txt",
]

_WELL_KNOWN_DIRS = [
    "docs",
    "openapi",
    ".claude",
]


def _slugify(name: str) -> str:
    """Convert a filename to a valid KCP unit id."""
    stem = Path(name).stem.lower()
    slug = re.sub(r"[^a-z0-9]+", "-", stem).strip("-")
    return slug or "unit"


def _unique_id(base: str, seen: set[str]) -> str:
    """Return a unique id, appending a suffix if needed."""
    if base not in seen:
        seen.add(base)
        return base
    i = 2
    while f"{base}-{i}" in seen:
        i += 1
    key = f"{base}-{i}"
    seen.add(key)
    return key


def _extract_first_heading(filepath: Path) -> Optional[str]:
    """Extract the first markdown heading from a file."""
    try:
        text = filepath.read_text(encoding="utf-8", errors="replace")
        for line in text.splitlines():
            m = re.match(r"^#{1,3}\s+(.+)", line)
            if m:
                return m.group(1).strip()
    except OSError:
        pass
    return None


def _extract_headings(filepath: Path) -> list[str]:
    """Extract H2/H3 headings from a markdown file for trigger keywords."""
    headings = []
    try:
        text = filepath.read_text(encoding="utf-8", errors="replace")
        for line in text.splitlines():
            m = re.match(r"^#{2,3}\s+(.+)", line)
            if m:
                headings.append(m.group(1).strip())
    except OSError:
        pass
    return headings


def _extract_triggers(filepath: Path) -> list[str]:
    """Extract 2-3 trigger keywords from filename and first heading."""
    triggers: list[str] = []
    # Keywords from filename
    stem = filepath.stem.lower()
    parts = re.split(r"[^a-z0-9]+", stem)
    triggers.extend(p for p in parts if len(p) > 2)
    # Keywords from first heading
    heading = _extract_first_heading(filepath)
    if heading:
        words = re.split(r"[^a-zA-Z0-9]+", heading.lower())
        for w in words:
            if len(w) > 2 and w not in triggers:
                triggers.append(w)
    # Return first 3 unique triggers
    return triggers[:3]


def _detect_project_name(directory: Path) -> str:
    """Detect project name from package.json, pom.xml, or directory name."""
    pkg_json = directory / "package.json"
    if pkg_json.exists():
        try:
            data = json.loads(pkg_json.read_text(encoding="utf-8"))
            name = data.get("name")
            if name:
                return str(name)
        except (json.JSONDecodeError, OSError):
            pass

    pom_xml = directory / "pom.xml"
    if pom_xml.exists():
        try:
            text = pom_xml.read_text(encoding="utf-8")
            # Simple regex for <artifactId> (first one after <project>)
            m = re.search(r"<artifactId>([^<]+)</artifactId>", text)
            if m:
                return m.group(1)
        except OSError:
            pass

    return directory.name


def _extract_description(directory: Path) -> str:
    """Extract description from README first paragraph."""
    readme = directory / "README.md"
    if not readme.exists():
        return ""
    try:
        text = readme.read_text(encoding="utf-8", errors="replace")
        lines = text.splitlines()
        # Skip leading headings and blank lines, collect first paragraph
        para_lines: list[str] = []
        past_heading = False
        for line in lines:
            stripped = line.strip()
            if not past_heading:
                if stripped.startswith("#") or stripped == "":
                    continue
                past_heading = True
            if past_heading:
                if stripped == "" and para_lines:
                    break
                if stripped.startswith("#"):
                    break
                para_lines.append(stripped)
        desc = " ".join(para_lines).strip()
        # Truncate to a reasonable length
        if len(desc) > 200:
            desc = desc[:197] + "..."
        return desc
    except OSError:
        return ""


def _discover_artifacts(directory: Path) -> list[Path]:
    """Discover well-known knowledge artifacts in the directory."""
    found: list[Path] = []

    for name in _WELL_KNOWN_FILES:
        p = directory / name
        if p.exists() and p.is_file():
            found.append(p)

    for name in _WELL_KNOWN_DIRS:
        d = directory / name
        if d.exists() and d.is_dir():
            # Add files in the directory (one level deep for docs, recursively for .claude)
            if name == ".claude":
                for child in sorted(d.rglob("*")):
                    if child.is_file() and child.suffix in (".yaml", ".yml", ".md"):
                        found.append(child)
            elif name == "openapi":
                for child in sorted(d.rglob("*")):
                    if child.is_file() and child.suffix in (".yaml", ".yml", ".json"):
                        found.append(child)
            else:
                for child in sorted(d.rglob("*")):
                    if child.is_file() and child.suffix in (".md", ".txt", ".rst"):
                        found.append(child)

    return found


def _intent_from_artifact(filepath: Path, directory: Path) -> str:
    """Generate a placeholder intent from the artifact."""
    heading = _extract_first_heading(filepath)
    if heading:
        return heading
    return "TODO: describe what question this answers"


def _scope_for_artifact(filepath: Path, directory: Path) -> str:
    """Determine scope based on artifact location."""
    rel = filepath.relative_to(directory)
    parts = rel.parts
    if len(parts) == 1:
        return "global"
    return "module"


def _audience_for_artifact(filepath: Path) -> list[str]:
    """Determine audience based on artifact type and location."""
    rel_str = str(filepath)
    if ".claude" in rel_str:
        return ["agent"]
    return ["human", "agent"]


def _token_estimate(filepath: Path) -> int:
    """Estimate token count as file_size_bytes / 4, rounded to nearest 100."""
    try:
        size = filepath.stat().st_size
        estimate = size // 4
        return round(estimate / 100) * 100 or 100
    except OSError:
        return 100


def _yaml_str(value: str) -> str:
    """Format a string for YAML output, quoting if necessary."""
    if not value:
        return '""'
    # Quote if it contains special chars or looks like a YAML value
    needs_quote = any(c in value for c in ":{}\n\t#[]|>&*!%@`")
    needs_quote = needs_quote or value.lower() in ("true", "false", "null", "yes", "no")
    if needs_quote:
        escaped = value.replace("\\", "\\\\").replace('"', '\\"')
        return f'"{escaped}"'
    return f'"{value}"'


def generate_manifest(
    directory: Path,
    level: int = 1,
    scan: bool = False,
) -> tuple[str, list[Path]]:
    """Generate a knowledge.yaml content string.

    Returns a tuple of (yaml_content, discovered_files).
    """
    artifacts = _discover_artifacts(directory)
    project_name = _detect_project_name(directory)
    description = _extract_description(directory)
    today = date.today().isoformat()

    seen_ids: set[str] = set()
    units: list[dict] = []

    for filepath in artifacts:
        rel = filepath.relative_to(directory)
        base_id = _slugify(rel.name)
        unit_id = _unique_id(base_id, seen_ids)

        unit: dict = {
            "id": unit_id,
            "path": str(rel),
            "intent": _intent_from_artifact(filepath, directory),
            "scope": _scope_for_artifact(filepath, directory),
            "audience": _audience_for_artifact(filepath),
        }

        if level >= 2:
            unit["validated"] = today
            unit["hints"] = {"token_estimate": _token_estimate(filepath)}

        if level >= 3:
            triggers = _extract_triggers(filepath)
            if triggers:
                unit["triggers"] = triggers

        units.append(unit)

    # Build YAML manually for clean formatting
    lines: list[str] = []
    lines.append(f'kcp_version: "0.11"')
    lines.append(f"project: {project_name}")
    if description:
        lines.append(f"description: {_yaml_str(description)}")
    lines.append(f'version: "0.1.0"')
    lines.append(f"updated: {_yaml_str(today)}")
    lines.append("")
    lines.append("units:")

    for unit in units:
        lines.append(f"  - id: {unit['id']}")
        lines.append(f"    path: {unit['path']}")
        lines.append(f"    intent: {_yaml_str(unit['intent'])}")
        lines.append(f"    scope: {unit['scope']}")
        audience = ", ".join(unit["audience"])
        lines.append(f"    audience: [{audience}]")

        if "validated" in unit:
            lines.append(f"    validated: {_yaml_str(unit['validated'])}")

        if "hints" in unit:
            lines.append(f"    hints:")
            lines.append(f"      token_estimate: {unit['hints']['token_estimate']}")

        if "triggers" in unit:
            triggers = ", ".join(unit["triggers"])
            lines.append(f"    triggers: [{triggers}]")

        lines.append("")

    content = "\n".join(lines)
    return content, artifacts


def run_init(
    directory: Path,
    level: int = 1,
    scan: bool = False,
    force: bool = False,
) -> int:
    """Run the init command. Returns exit code."""
    output_path = directory / "knowledge.yaml"

    if output_path.exists() and not force:
        print(f"Warning: {output_path} already exists. Use --force to overwrite.")
        return 1

    if scan:
        print("Scanning project...")
        artifacts = _discover_artifacts(directory)
        for a in artifacts:
            rel = a.relative_to(directory)
            print(f"  Found: {rel}")
        print()

    content, artifacts = generate_manifest(directory, level=level, scan=scan)
    output_path.write_text(content, encoding="utf-8")

    print(f"Generated knowledge.yaml with {len(artifacts)} units (Level {level}).")
    print("Review and update the 'intent' fields, then validate:")
    print("  kcp validate knowledge.yaml")

    # Generate .well-known/kcp.json (RFC-0008 §1.4)
    well_known_dir = directory / ".well-known"
    well_known_dir.mkdir(exist_ok=True)
    well_known_path = well_known_dir / "kcp.json"
    if not well_known_path.exists() or force:
        well_known_content = json.dumps({
            "kcp_version": "0.11",
            "manifest": "/knowledge.yaml",
            "network": {"role": "standalone"}
        }, indent=2)
        well_known_path.write_text(well_known_content + "\n", encoding="utf-8")
        print(f"Generated .well-known/kcp.json (role: standalone).")

    # Print llms.txt snippet (RFC-0008 §1.2) — never overwrite automatically
    print()
    print("Add this to your llms.txt to enable cold discovery (RFC-0008):")
    print()
    print("  > knowledge: /knowledge.yaml")
    print()

    return 0
