import re
from typing import NamedTuple

from .model import KnowledgeManifest

VALID_SCOPES = {"global", "project", "module"}
VALID_AUDIENCES = {"human", "agent", "developer", "operator", "architect", "devops"}
VALID_RELATIONSHIP_TYPES = {"enables", "context", "supersedes", "contradicts"}
KNOWN_KCP_VERSIONS = {"0.1"}
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


def validate(manifest: KnowledgeManifest) -> ValidationResult:
    """Validate a parsed KnowledgeManifest.

    Returns a :class:`ValidationResult` with separate ``errors`` and ``warnings`` lists.
    """
    errors: list[str] = []
    warnings: list[str] = []
    unit_ids = {u.id for u in manifest.units}

    # kcp_version — RECOMMENDED; warn if missing or unknown
    if not manifest.kcp_version:
        warnings.append("manifest: 'kcp_version' not declared; assuming 0.1")
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
