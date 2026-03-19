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
  "depends_on",
  "governs",
]);
const VALID_ACCESS_VALUES = new Set(["public", "authenticated", "restricted"]);
const VALID_SENSITIVITY_VALUES = new Set([
  "public",
  "internal",
  "confidential",
  "restricted",
]);
// human_in_the_loop is an object per spec §3.4 — no HITL enum, validation done inline
const KNOWN_KCP_VERSIONS = new Set([
  "0.1",
  "0.2",
  "0.3",
  "0.4",
  "0.5",
  "0.6",
  "0.7",
  "0.8",
  "0.9",
  "0.10",
  "0.11",
  "0.12",
]);
const VALID_MANIFEST_RELATIONSHIPS = new Set([
  "child",
  "foundation",
  "governs",
  "peer",
  "archive",
]);
const VALID_VERSION_POLICIES = new Set(["exact", "minimum", "compatible"]);
const VALID_ON_FAILURE_VALUES = new Set(["skip", "warn", "degrade"]);
const VALID_UPDATE_FREQUENCIES = new Set([
  "hourly",
  "daily",
  "weekly",
  "monthly",
  "rarely",
  "never",
]);
const ID_PATTERN = /^[a-z0-9.\-]+$/;

export function validate(
  manifest: KnowledgeManifest,
  manifestDir?: string
): ValidationResult {
  const errors: string[] = [];
  const warnings: string[] = [];

  // Root fields
  if (!manifest.project) errors.push("Root field 'project' is required");
  if (!manifest.version) warnings.push("manifest: 'version' not declared; RECOMMENDED per §6.2");
  if (manifest.units.length === 0) warnings.push("Manifest has no units");

  // kcp_version — RECOMMENDED; warn if absent or unknown (§6.1)
  if (!manifest.kcp_version) {
    warnings.push("manifest: 'kcp_version' not declared; assuming 0.8");
  } else if (!KNOWN_KCP_VERSIONS.has(manifest.kcp_version)) {
    warnings.push(
      `manifest: unknown kcp_version '${manifest.kcp_version}'; processing as 0.8`
    );
  }

  const unitIds = new Set<string>();

  for (const unit of manifest.units) {
    const ctx = `Unit '${unit.id}'`;

    if (!unit.id) {
      errors.push("A unit is missing required field 'id'");
    } else if (unitIds.has(unit.id)) {
      warnings.push(`Duplicate unit id: '${unit.id}'`);
    } else {
      unitIds.add(unit.id);
    }

    if (!unit.path) errors.push(`${ctx}: missing required field 'path'`);
    if (!unit.intent) errors.push(`${ctx}: missing required field 'intent'`);
    if (!unit.scope) errors.push(`${ctx}: missing required field 'scope'`);
    if (!unit.audience || unit.audience.length === 0)
      errors.push(`${ctx}: missing required field 'audience'`);

    if (unit.scope && !VALID_SCOPES.has(unit.scope)) {
      errors.push(
        `${ctx}: 'scope' must be one of [global, module, project], got '${unit.scope}'`
      );
    }

    if (unit.kind && !VALID_KINDS.has(unit.kind)) {
      warnings.push(`${ctx}: unknown kind '${unit.kind}'`);
    }

    // access validation (§4.11)
    if (unit.access && !VALID_ACCESS_VALUES.has(unit.access)) {
      warnings.push(
        `${ctx}: unknown 'access' value '${unit.access}'; treating as 'restricted'`
      );
    }

    // auth_scope validation (§4.11)
    if (unit.auth_scope && unit.access !== "restricted") {
      warnings.push(
        `${ctx}: 'auth_scope' is only meaningful when access is 'restricted'`
      );
    }

    // sensitivity validation (§4.12)
    if (unit.sensitivity && !VALID_SENSITIVITY_VALUES.has(unit.sensitivity)) {
      warnings.push(`${ctx}: unknown 'sensitivity' value '${unit.sensitivity}'`);
    }

    // delegation validation (§3.4)
    if (unit.delegation) {
      const hitl = unit.delegation.human_in_the_loop;
      if (hitl !== undefined) {
        const mech = hitl.approval_mechanism;
        if (mech !== undefined && !["oauth_consent", "uma", "custom"].includes(mech)) {
          errors.push(
            `${ctx}: delegation.human_in_the_loop.approval_mechanism must be one of [oauth_consent, uma, custom], got '${mech}'`
          );
        }
      }
      if (
        manifest.delegation?.max_depth != null &&
        unit.delegation.max_depth != null &&
        unit.delegation.max_depth > manifest.delegation.max_depth
      ) {
        errors.push(
          `${ctx}: unit delegation.max_depth (${unit.delegation.max_depth}) must not exceed root delegation.max_depth (${manifest.delegation.max_depth})`
        );
      }
    }

    // compliance validation (§3.5)
    if (unit.compliance?.sensitivity) {
      if (!VALID_SENSITIVITY_VALUES.has(unit.compliance.sensitivity)) {
        errors.push(
          `${ctx}: compliance.sensitivity must be one of [confidential, internal, public, restricted], got '${unit.compliance.sensitivity}'`
        );
      }
    }

    // hints validation (§4.10)
    if (unit.hints) {
      const h = unit.hints as Record<string, unknown>;
      if (h.summary_available === true && !h.summary_unit) {
        warnings.push(
          `${ctx}: summary_available is true but no summary_unit declared`
        );
      }
      if (typeof h.summary_unit === "string" && !unitIds.has(h.summary_unit)) {
        warnings.push(
          `${ctx}: summary_unit references non-existent unit '${h.summary_unit}'`
        );
      }
      if (typeof h.chunk_of === "string" && !unitIds.has(h.chunk_of)) {
        warnings.push(
          `${ctx}: chunk_of references non-existent unit '${h.chunk_of}'`
        );
      }
      if (h.chunk_index != null && !h.chunk_of) {
        warnings.push(
          `${ctx}: chunk_index is present without chunk_of`
        );
      }
    }

    // authority validation (§4.17)
    if (unit.authority) {
      const KNOWN_AUTHORITY_ACTIONS = new Set([
        "read", "summarize", "modify", "share_externally", "execute",
      ]);
      const VALID_AUTHORITY_VALUES = new Set([
        "initiative", "requires_approval", "denied",
      ]);
      for (const [action, value] of Object.entries(unit.authority)) {
        if (value !== undefined && !VALID_AUTHORITY_VALUES.has(value)) {
          if (KNOWN_AUTHORITY_ACTIONS.has(action)) {
            warnings.push(
              `${ctx}: authority.${action} has unknown value '${value}'; expected initiative, requires_approval, or denied`
            );
          } else {
            warnings.push(
              `${ctx}: authority custom action '${action}' has unknown value '${value}'; expected initiative, requires_approval, or denied`
            );
          }
        }
      }
    }

    // discovery validation (§4.18)
    if (unit.discovery) {
      const disc = unit.discovery;
      if (
        disc.verification_status === "rumored" &&
        disc.confidence !== undefined &&
        disc.confidence >= 0.5
      ) {
        warnings.push(
          `${ctx}: discovery.verification_status is 'rumored' but confidence is ${disc.confidence} (>=0.5); consider upgrading status to 'observed'`
        );
      }
      if (
        disc.verified_at !== undefined &&
        (disc.verification_status === "rumored" || disc.verification_status === "observed")
      ) {
        warnings.push(
          `${ctx}: discovery.verified_at is set but verification_status is '${disc.verification_status}'; verified_at implies status should be 'verified'`
        );
      }
      if (disc.contradicted_by !== undefined && !unitIds.has(disc.contradicted_by)) {
        warnings.push(
          `${ctx}: discovery.contradicted_by references unknown unit id '${disc.contradicted_by}'`
        );
      }
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

  // Root-level delegation validation (§3.4)
  if (manifest.delegation?.human_in_the_loop !== undefined) {
    const hitl = manifest.delegation.human_in_the_loop;
    const mech = hitl.approval_mechanism;
    if (mech !== undefined && !["oauth_consent", "uma", "custom"].includes(mech)) {
      errors.push(
        `manifest: delegation.human_in_the_loop.approval_mechanism must be one of [oauth_consent, uma, custom], got '${mech}'`
      );
    }
  }

  // Root-level compliance validation (§3.5)
  if (manifest.compliance?.sensitivity) {
    if (!VALID_SENSITIVITY_VALUES.has(manifest.compliance.sensitivity)) {
      errors.push(
        `manifest: compliance.sensitivity must be one of [confidential, internal, public, restricted], got '${manifest.compliance.sensitivity}'`
      );
    }
  }

  // Warn if any unit requires auth but no root-level auth block is present (§7)
  const hasProtected = manifest.units.some(
    (u) => u.access === "authenticated" || u.access === "restricted"
  );
  if (hasProtected && (!manifest.auth || !manifest.auth.methods.length)) {
    warnings.push(
      "manifest: units with access 'authenticated' or 'restricted' exist but no 'auth' block is declared"
    );
  }

  // Federation validation (§3.6)
  const manifestIds = new Set<string>();
  for (const ref of manifest.manifests) {
    const ctx = `manifests['${ref.id}']`;
    if (!ref.id) {
      errors.push("manifests: entry missing required 'id'");
      continue;
    }
    if (!ID_PATTERN.test(ref.id)) {
      errors.push(`${ctx}: 'id' must match ^[a-z0-9.\\-]+$, got '${ref.id}'`);
    }
    if (manifestIds.has(ref.id)) {
      errors.push(`${ctx}: duplicate manifest id`);
    }
    manifestIds.add(ref.id);
    if (!ref.url) {
      errors.push(`${ctx}: 'url' is required`);
    } else if (!ref.url.startsWith("https://")) {
      errors.push(`${ctx}: 'url' must use HTTPS, got '${ref.url}'`);
    }
    if (ref.relationship && !VALID_MANIFEST_RELATIONSHIPS.has(ref.relationship)) {
      warnings.push(`${ctx}: unknown 'relationship' value '${ref.relationship}'`);
    }
    if (ref.update_frequency && !VALID_UPDATE_FREQUENCIES.has(ref.update_frequency)) {
      warnings.push(`${ctx}: unknown 'update_frequency' value '${ref.update_frequency}'`);
    }
    if (ref.version_policy && !VALID_VERSION_POLICIES.has(ref.version_policy)) {
      warnings.push(`${ctx}: unknown 'version_policy' value '${ref.version_policy}'; treating as 'compatible'`);
    }
    if (ref.version_pin && !ref.version_policy) {
      warnings.push(`${ctx}: 'version_pin' is set but 'version_policy' is not declared; defaulting to 'compatible'`);
    }
  }

  // Validate external_depends_on references in units
  for (const unit of manifest.units) {
    const ctx = `Unit '${unit.id}'`;
    for (const extDep of unit.external_depends_on) {
      const ep = `${ctx}.external_depends_on['${extDep.manifest}/${extDep.unit}']`;
      if (!extDep.manifest) {
        errors.push(`${ep}: 'manifest' is required`);
      } else if (!manifestIds.has(extDep.manifest)) {
        warnings.push(`${ep}: references unknown manifest id '${extDep.manifest}'`);
      }
      if (!extDep.unit) {
        errors.push(`${ep}: 'unit' is required`);
      }
      if (extDep.on_failure && !VALID_ON_FAILURE_VALUES.has(extDep.on_failure)) {
        warnings.push(`${ep}: unknown 'on_failure' value '${extDep.on_failure}'; treating as 'skip'`);
      }
    }
  }

  // Validate external_relationships
  for (const extRel of manifest.external_relationships) {
    const ep = `external_relationship['${extRel.from_unit}' -> '${extRel.to_unit}']`;
    if (!extRel.from_unit) {
      errors.push(`${ep}: 'from_unit' is required`);
    }
    if (!extRel.to_unit) {
      errors.push(`${ep}: 'to_unit' is required`);
    }
    if (!extRel.type) {
      errors.push(`${ep}: 'type' is required`);
    }
    if (extRel.from_manifest && !manifestIds.has(extRel.from_manifest)) {
      warnings.push(`${ep}: 'from_manifest' references unknown manifest id '${extRel.from_manifest}'`);
    }
    if (extRel.to_manifest && !manifestIds.has(extRel.to_manifest)) {
      warnings.push(`${ep}: 'to_manifest' references unknown manifest id '${extRel.to_manifest}'`);
    }
  }

  return { errors, warnings, isValid: errors.length === 0 };
}
