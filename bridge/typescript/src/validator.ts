// KCP manifest validator
// Mirrors Python validate() and Java KcpValidator.validate()

import { existsSync } from "node:fs";
import { resolve, join } from "node:path";
import type { KnowledgeManifest, ValidationResult } from "./model.js";

const VALID_SCOPES = new Set(["global", "project", "module"]);
const VALID_KINDS = new Set([
  "knowledge",
  "schema",
  "service",
  "policy",
  "executable",
]);
const VALID_REL_TYPES = new Set([
  "enables",
  "context",
  "supersedes",
  "contradicts",
]);

export function validate(
  manifest: KnowledgeManifest,
  manifestDir?: string
): ValidationResult {
  const errors: string[] = [];
  const warnings: string[] = [];

  // Root fields
  if (!manifest.project) errors.push("Root field 'project' is required");
  if (!manifest.version) errors.push("Root field 'version' is required");
  if (manifest.units.length === 0) warnings.push("Manifest has no units");

  const unitIds = new Set<string>();

  for (const unit of manifest.units) {
    const ctx = `Unit '${unit.id}'`;

    if (!unit.id) {
      errors.push("A unit is missing required field 'id'");
    } else if (unitIds.has(unit.id)) {
      errors.push(`Duplicate unit id: '${unit.id}'`);
    } else {
      unitIds.add(unit.id);
    }

    if (!unit.path) errors.push(`${ctx}: missing required field 'path'`);
    if (!unit.intent) errors.push(`${ctx}: missing required field 'intent'`);
    if (!unit.scope) errors.push(`${ctx}: missing required field 'scope'`);
    if (!unit.audience || unit.audience.length === 0)
      errors.push(`${ctx}: missing required field 'audience'`);

    if (unit.scope && !VALID_SCOPES.has(unit.scope)) {
      warnings.push(
        `${ctx}: unknown scope '${unit.scope}' (expected: global, project, module)`
      );
    }

    if (unit.kind && !VALID_KINDS.has(unit.kind)) {
      warnings.push(`${ctx}: unknown kind '${unit.kind}'`);
    }

    // File existence check (only if manifestDir is provided)
    if (manifestDir && unit.path) {
      const resolved = resolve(join(manifestDir, unit.path));
      if (!resolved.startsWith(resolve(manifestDir))) {
        errors.push(`${ctx}: path traversal rejected: '${unit.path}'`);
      } else if (!existsSync(resolved)) {
        warnings.push(`${ctx}: file not found on disk: '${unit.path}'`);
      }
    }
  }

  // Relationship validation
  for (const rel of manifest.relationships) {
    if (!unitIds.has(rel.from_id)) {
      warnings.push(
        `Relationship references unknown unit id '${rel.from_id}'`
      );
    }
    if (!unitIds.has(rel.to_id)) {
      warnings.push(`Relationship references unknown unit id '${rel.to_id}'`);
    }
    if (rel.type && !VALID_REL_TYPES.has(rel.type)) {
      warnings.push(`Relationship type '${rel.type}' is not in the known set`);
    }
  }

  // depends_on reference check
  for (const unit of manifest.units) {
    for (const dep of unit.depends_on) {
      if (!unitIds.has(dep)) {
        warnings.push(
          `Unit '${unit.id}': depends_on references unknown unit '${dep}'`
        );
      }
    }
  }

  return { errors, warnings, isValid: errors.length === 0 };
}
