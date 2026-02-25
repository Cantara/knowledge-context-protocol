from .model import KnowledgeManifest

VALID_SCOPES = {"global", "project", "module"}
VALID_AUDIENCES = {"human", "agent", "developer", "operator", "architect", "devops"}
VALID_RELATIONSHIP_TYPES = {"enables", "context", "supersedes", "contradicts"}


def validate(manifest: KnowledgeManifest) -> list[str]:
    """Validate a parsed KnowledgeManifest. Returns a list of error strings."""
    errors: list[str] = []
    unit_ids = {u.id for u in manifest.units}

    if not manifest.project:
        errors.append("manifest: 'project' is required")
    if not manifest.version:
        errors.append("manifest: 'version' is required")
    if not manifest.units:
        errors.append("manifest: 'units' must not be empty")

    for unit in manifest.units:
        p = f"unit '{unit.id}'"
        if not unit.id:
            errors.append("unit: 'id' is required")
            continue
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
            errors.append(
                f"{p}: unknown audience value(s): {sorted(invalid_audience)}"
            )
        for dep in unit.depends_on:
            if dep not in unit_ids:
                errors.append(f"{p}: 'depends_on' references unknown unit '{dep}'")

    for rel in manifest.relationships:
        p = f"relationship '{rel.from_id}' -> '{rel.to_id}'"
        if rel.from_id not in unit_ids:
            errors.append(f"{p}: 'from' references unknown unit '{rel.from_id}'")
        if rel.to_id not in unit_ids:
            errors.append(f"{p}: 'to' references unknown unit '{rel.to_id}'")
        if rel.type not in VALID_RELATIONSHIP_TYPES:
            errors.append(
                f"{p}: 'type' must be one of {sorted(VALID_RELATIONSHIP_TYPES)}, got '{rel.type}'"
            )

    return errors
