import os
import re
from pathlib import Path
from typing import NamedTuple, Optional

from .model import KnowledgeManifest

VALID_SCOPES = {"global", "project", "module"}
VALID_AUDIENCES = {"human", "agent", "developer", "operator", "architect", "devops"}
VALID_RELATIONSHIP_TYPES = {"enables", "context", "supersedes", "contradicts"}
VALID_KINDS = {"knowledge", "schema", "service", "policy", "executable"}
VALID_FORMATS = {
    "markdown", "pdf", "openapi", "json-schema", "jupyter",
    "html", "asciidoc", "rst", "vtt", "yaml", "json", "csv", "text",
}
VALID_UPDATE_FREQUENCIES = {"hourly", "daily", "weekly", "monthly", "rarely", "never"}
VALID_INDEXING_SHORTHANDS = {"open", "read-only", "no-train", "none"}
KNOWN_KCP_VERSIONS = {"0.1", "0.2", "0.3"}
_ID_PATTERN = re.compile(r"^[a-z0-9.\-]+$")
_MAX_TRIGGER_LENGTH = 60
_MAX_TRIGGERS_PER_UNIT = 20


class ValidationResult(NamedTuple):
    """Result of validating a KCP manifest.

    ``errors``   — conditions that make the manifest invalid (MUST fix).
    ``warnings`` — conditions that are permitted but suspicious (SHOULD fix).
    """
    errors: list[str]
    warnings: list[str]

    @property
    def is_valid(self) -> bool:
        return len(self.errors) == 0


def _detect_cycles(units: list) -> set[tuple[str, str]]:
    """Detect cycles in the depends_on graph using DFS.

    Returns the set of (from_id, to_id) edges that would close a cycle.
    These edges should be silently ignored per SPEC.md section 4.7.
    """
    # Build adjacency list
    adj: dict[str, list[str]] = {}
    unit_ids = {u.id for u in units}
    for unit in units:
        adj[unit.id] = [dep for dep in unit.depends_on if dep in unit_ids]

    cycle_edges: set[tuple[str, str]] = set()
    # Track global visit state: 0 = unvisited, 1 = in current path, 2 = completed
    state: dict[str, int] = {uid: 0 for uid in unit_ids}

    def dfs(node: str, path_set: set[str]) -> None:
        state[node] = 1
        path_set.add(node)
        for dep in adj.get(node, []):
            if state[dep] == 1:
                # dep is in the current DFS path — this edge closes a cycle
                cycle_edges.add((node, dep))
            elif state[dep] == 0:
                dfs(dep, path_set)
        path_set.discard(node)
        state[node] = 2

    for uid in unit_ids:
        if state[uid] == 0:
            dfs(uid, set())

    return cycle_edges


def validate(manifest: KnowledgeManifest, manifest_dir: Optional[str] = None) -> ValidationResult:
    """Validate a parsed KnowledgeManifest.

    Args:
        manifest: The parsed manifest to validate.
        manifest_dir: Optional directory containing the manifest file. When provided,
            the validator checks that declared unit paths exist on disk and emits
            warnings for missing paths (SPEC.md section 4.3 / section 7).

    Returns a :class:`ValidationResult` with separate ``errors`` and ``warnings`` lists.
    """
    errors: list[str] = []
    warnings: list[str] = []
    unit_ids = {u.id for u in manifest.units}

    # Cycle detection (§4.7) — detect and silently ignore cycle-closing edges.
    # No error or warning is required by the spec, but we run the detection
    # so that traversal code can rely on it.
    _detect_cycles(manifest.units)

    # kcp_version — RECOMMENDED; warn if missing or unknown
    if not manifest.kcp_version:
        warnings.append("manifest: 'kcp_version' not declared; assuming 0.3")
    elif manifest.kcp_version not in KNOWN_KCP_VERSIONS:
        warnings.append(
            f"manifest: unknown kcp_version '{manifest.kcp_version}'; "
            f"processing as {max(KNOWN_KCP_VERSIONS)}"
        )

    # Required root fields
    if not manifest.project:
        errors.append("manifest: 'project' is required")
    if not manifest.units:
        errors.append("manifest: 'units' must not be empty")

    # Duplicate ID detection (§7: SHOULD warn, use first occurrence)
    seen_ids: set[str] = set()

    for unit in manifest.units:
        p = f"unit '{unit.id}'"
        if not unit.id:
            errors.append("unit: 'id' is required")
            continue

        # Duplicate ID check
        if unit.id in seen_ids:
            warnings.append(f"{p}: duplicate 'id' (first occurrence takes precedence)")
        seen_ids.add(unit.id)

        # ID format check (§4.2: lowercase a-z, digits, hyphens, dots)
        if not _ID_PATTERN.match(unit.id):
            warnings.append(
                f"{p}: 'id' should contain only lowercase a-z, digits, hyphens, and dots"
            )

        if not unit.path:
            errors.append(f"{p}: 'path' is required")
        elif manifest_dir is not None:
            # Path existence check (§4.3 / §7: SHOULD warn if path does not exist)
            resolved = Path(manifest_dir) / unit.path
            if not resolved.exists():
                warnings.append(f"{p}: path '{unit.path}' does not exist")
        if not unit.intent:
            errors.append(f"{p}: 'intent' is required")
        if not unit.scope:
            errors.append(f"{p}: 'scope' is required")
        elif unit.scope not in VALID_SCOPES:
            errors.append(
                f"{p}: 'scope' must be one of {sorted(VALID_SCOPES)}, got '{unit.scope}'"
            )
        invalid_audience = set(unit.audience) - VALID_AUDIENCES
        if invalid_audience:
            warnings.append(
                f"{p}: unknown audience value(s): {sorted(invalid_audience)}"
            )

        # kind validation (§4.3a)
        if unit.kind is not None and unit.kind not in VALID_KINDS:
            warnings.append(
                f"{p}: unknown 'kind' value '{unit.kind}'"
            )

        # format validation (§4.4a)
        if unit.format is not None and unit.format not in VALID_FORMATS:
            warnings.append(
                f"{p}: unknown 'format' value '{unit.format}'"
            )

        # update_frequency validation (§4.6b)
        if unit.update_frequency is not None and unit.update_frequency not in VALID_UPDATE_FREQUENCIES:
            warnings.append(
                f"{p}: unknown 'update_frequency' value '{unit.update_frequency}'"
            )

        # indexing validation (§4.6c)
        if unit.indexing is not None and isinstance(unit.indexing, str):
            if unit.indexing not in VALID_INDEXING_SHORTHANDS:
                warnings.append(
                    f"{p}: unknown 'indexing' shorthand '{unit.indexing}'"
                )

        for dep in unit.depends_on:
            if dep not in unit_ids:
                warnings.append(f"{p}: 'depends_on' references unknown unit '{dep}'")

        # Trigger constraints (§4.9)
        if len(unit.triggers) > _MAX_TRIGGERS_PER_UNIT:
            warnings.append(
                f"{p}: more than {_MAX_TRIGGERS_PER_UNIT} triggers "
                f"({len(unit.triggers)}); excess will be ignored"
            )
        for trigger in unit.triggers:
            if len(trigger) > _MAX_TRIGGER_LENGTH:
                warnings.append(
                    f"{p}: trigger '{trigger[:30]}...' exceeds {_MAX_TRIGGER_LENGTH} characters"
                )

    for rel in manifest.relationships:
        p = f"relationship '{rel.from_id}' -> '{rel.to_id}'"
        if rel.from_id not in unit_ids:
            warnings.append(f"{p}: 'from' references unknown unit '{rel.from_id}'")
        if rel.to_id not in unit_ids:
            warnings.append(f"{p}: 'to' references unknown unit '{rel.to_id}'")
        if rel.type not in VALID_RELATIONSHIP_TYPES:
            warnings.append(
                f"{p}: 'type' must be one of {sorted(VALID_RELATIONSHIP_TYPES)}, got '{rel.type}'"
            )

    return ValidationResult(errors=errors, warnings=warnings)
