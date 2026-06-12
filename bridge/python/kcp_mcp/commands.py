"""
KCP command manifest loader and formatter.
Port of the TypeScript bridge's commands.ts.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import yaml


@dataclass
class KeyFlag:
    flag: str
    description: str
    use_when: str


@dataclass
class PreferredInvocation:
    invocation: str
    use_when: str


@dataclass
class CommandSyntax:
    usage: str
    key_flags: list[KeyFlag] = field(default_factory=list)
    preferred_invocations: list[PreferredInvocation] = field(default_factory=list)


@dataclass
class CommandManifest:
    command: str
    platform: str
    description: str
    syntax: CommandSyntax
    subcommand: Optional[str] = None


def _manifest_key(m: CommandManifest) -> str:
    return f"{m.command} {m.subcommand}" if m.subcommand else m.command


def _parse_one(data: dict) -> Optional[CommandManifest]:
    if not data.get("command") or not data.get("syntax"):
        return None
    raw_syntax = data["syntax"]
    if not isinstance(raw_syntax, dict):
        return None

    key_flags = []
    for f in raw_syntax.get("key_flags") or []:
        if isinstance(f, dict):
            key_flags.append(KeyFlag(
                flag=str(f.get("flag", "")),
                description=str(f.get("description", "")),
                use_when=str(f.get("use_when", "")),
            ))

    preferred = []
    for p in raw_syntax.get("preferred_invocations") or []:
        if isinstance(p, dict):
            preferred.append(PreferredInvocation(
                invocation=str(p.get("invocation", "")),
                use_when=str(p.get("use_when", "")),
            ))

    return CommandManifest(
        command=str(data["command"]),
        subcommand=str(data["subcommand"]) if data.get("subcommand") else None,
        platform=str(data.get("platform", "all")),
        description=str(data.get("description", "")),
        syntax=CommandSyntax(
            usage=str(raw_syntax.get("usage", "")),
            key_flags=key_flags,
            preferred_invocations=preferred,
        ),
    )


def load_command_manifests(directory: Path) -> dict[str, CommandManifest]:
    """Load all YAML command manifests from a directory. Returns key → manifest map."""
    result: dict[str, CommandManifest] = {}
    try:
        entries = sorted(directory.iterdir())
    except (OSError, NotADirectoryError):
        return result

    for entry in entries:
        if entry.suffix not in (".yaml", ".yml"):
            continue
        try:
            data = yaml.safe_load(entry.read_text(encoding="utf-8"))
            if not isinstance(data, dict):
                continue
            m = _parse_one(data)
            if m:
                result[_manifest_key(m)] = m
        except Exception:
            pass

    return result


def lookup_command(manifests: dict[str, CommandManifest], query: str) -> Optional[CommandManifest]:
    """Look up a command by exact match, then prefix/base-command match."""
    normalized = query.strip().lower()

    # 1. Exact match
    for key, m in manifests.items():
        if key.lower() == normalized:
            return m

    # 2. Prefer base command (no subcommand)
    query_cmd = normalized.split()[0] if normalized else ""
    for m in manifests.values():
        if m.command.lower() == query_cmd and not m.subcommand:
            return m

    # 3. First subcommand match
    for m in manifests.values():
        if m.command.lower() == query_cmd:
            return m

    return None


def format_syntax_block(m: CommandManifest) -> str:
    """Format a manifest as a compact syntax block matching the TS/Java output."""
    name = f"{m.command} {m.subcommand}" if m.subcommand else m.command
    lines = [
        f"[kcp] {name}: {m.description}",
        f"Usage: {m.syntax.usage}",
    ]
    if m.syntax.key_flags:
        lines.append("Key flags:")
        for f in m.syntax.key_flags:
            lines.append(f"  {f.flag}: {f.description}  \u2192 {f.use_when}")
    if m.syntax.preferred_invocations:
        lines.append("Preferred:")
        for p in m.syntax.preferred_invocations:
            lines.append(f"  {p.invocation}  # {p.use_when}")
    return "\n".join(lines)
