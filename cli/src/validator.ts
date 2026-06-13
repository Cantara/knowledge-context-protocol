// KCP manifest validator
// Mirrors Python validate() and Java KcpValidator.validate()

import { existsSync, readFileSync, readdirSync } from "node:fs";
import { createHash } from "node:crypto";
import { resolve, join } from "node:path";
import type { KnowledgeManifest, Temporal, ValidationResult } from "./model.js";

// --- Per-unit content digest (RFC-0019 §3.2, draft) ---
// Lives here (not in a render module) so the CLI and bridge validator
// copies stay self-contained and byte-identical.

export const HASH_ALGORITHMS = ["sha256", "sha384", "sha512"];

/** POSIX-relative paths of all regular files under root; symlinks not followed. */
function walkRegularFiles(root: string, rel = ""): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const entryRel = rel ? `${rel}/${entry.name}` : entry.name;
    if (entry.isDirectory()) {
      out.push(...walkRegularFiles(join(root, entry.name), entryRel));
    } else if (entry.isFile()) {
      out.push(entryRel);
    }
    // symlinks, sockets, etc. are neither: skipped
  }
  return out;
}

/**
 * RFC-0019 §3.2 digest: a file hashes its raw bytes; a directory hashes
 * the bytewise-sorted concatenation of `relpath \0 hexdigest \n` entries
 * over every regular file beneath it. No exclusions. Returns undefined
 * when the target is missing or unreadable (fails closed at the caller).
 */
export function computeContentDigest(target: string, algorithm: string): string | undefined {
  try {
    const hashFile = (p: string): string =>
      createHash(algorithm).update(readFileSync(p)).digest("hex");
    // readdirSync throws ENOTDIR on files — cheaper than a stat round-trip
    let entries: string[];
    try {
      entries = walkRegularFiles(target);
    } catch (err) {
      if ((err as NodeJS.ErrnoException).code === "ENOTDIR") return hashFile(target);
      throw err;
    }
    entries.sort((a, b) => Buffer.compare(Buffer.from(a), Buffer.from(b)));
    const digest = createHash(algorithm);
    for (const e of entries) digest.update(`${e}\0${hashFile(join(target, e))}\n`);
    return digest.digest("hex");
  } catch {
    return undefined;
  }
}

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
  "0.13",
  "0.14",
  "0.16",
  "0.17",
  "0.18",
  "0.19",
  "0.20",
  "0.21",
]);
// content_structure vocabularies (RFC-0016, v0.17). Unknown values warn but pass through.
const VALID_CONTENT_MODALITIES = new Set([
  "prose",
  "table",
  "code",
  "list",
  "diagram",
  "reference",
  "mixed",
]);
const VALID_DENSITY = new Set(["sparse", "normal", "dense"]);
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

// --- Temporal validation helpers (§4.22, §3.6 manifests[].temporal) ---

/**
 * Detect cycles in a single-successor (functional) graph — the shape of
 * `superseded_by` chains. Returns the ids that participate in a cycle.
 * Mirrors the depends_on cycle detection in the Python/Java validators.
 */
function supersededCycleIds(successor: Map<string, string>): string[] {
  const cycle = new Set<string>();
  const state = new Map<string, number>(); // 0/undefined = unvisited, 1 = in-path, 2 = done
  for (const start of successor.keys()) {
    if (state.get(start) === 2) continue;
    const path: string[] = [];
    let node: string | undefined = start;
    while (node !== undefined && successor.has(node) && state.get(node) !== 2) {
      if (state.get(node) === 1) {
        for (const id of path.slice(path.indexOf(node))) cycle.add(id);
        break;
      }
      state.set(node, 1);
      path.push(node);
      node = successor.get(node);
    }
    for (const id of path) if (state.get(id) === 1) state.set(id, 2);
  }
  return [...cycle].sort();
}

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
        disc.verification_status === "declared" &&
        disc.confidence !== undefined &&
        (disc.confidence < 0.5 || disc.confidence >= 0.8)
      ) {
        warnings.push(
          `${ctx}: discovery.verification_status is 'declared' but confidence is ${disc.confidence}; SHOULD be in [0.5, 0.8) per RFC-0018 §5.1`
        );
      }
      if (
        disc.verified_at !== undefined &&
        (disc.verification_status === "rumored" ||
          disc.verification_status === "declared" ||
          disc.verification_status === "observed")
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

    // not_for validation (RFC-0015, v0.17)
    if (unit.not_for_strict !== undefined && (!unit.not_for || unit.not_for.length === 0)) {
      warnings.push(
        `${ctx}: 'not_for_strict' is set but 'not_for' is empty or absent`
      );
    }

    // content_structure validation (RFC-0016, v0.17) — warn on unknown values, pass through
    if (unit.content_structure) {
      const cs = unit.content_structure;
      if (cs.primary !== undefined && !VALID_CONTENT_MODALITIES.has(cs.primary)) {
        warnings.push(
          `${ctx}: content_structure.primary has unknown value '${cs.primary}'; expected one of prose, table, code, list, diagram, reference, mixed`
        );
      }
      if (cs.contains) {
        for (const modality of cs.contains) {
          if (!VALID_CONTENT_MODALITIES.has(modality)) {
            warnings.push(
              `${ctx}: content_structure.contains has unknown value '${modality}'; expected one of prose, table, code, list, diagram, reference, mixed`
            );
          }
        }
      }
      if (cs.density !== undefined && !VALID_DENSITY.has(cs.density)) {
        warnings.push(
          `${ctx}: content_structure.density has unknown value '${cs.density}'; expected one of sparse, normal, dense`
        );
      }
    }

    // content_hash validation (RFC-0019, draft) — shape, then recompute
    // against disk when a manifest directory is available (§3.1: "kcp
    // validate recomputes and compares"). A stale hash is an error, not a
    // warning: signing over it would brick the unit for every consumer.
    if (unit.content_hash) {
      const ch = unit.content_hash;
      if (!ch.algorithm || !HASH_ALGORITHMS.includes(ch.algorithm)) {
        errors.push(
          `${ctx}: content_hash.algorithm must be one of ${HASH_ALGORITHMS.join(", ")}`
        );
      } else if (!ch.value || !/^[0-9a-fA-F]+$/.test(ch.value)) {
        errors.push(`${ctx}: content_hash.value must be a hex digest`);
      } else if (manifestDir && unit.path) {
        const resolved = resolve(join(manifestDir, unit.path));
        if (resolved.startsWith(resolve(manifestDir)) && existsSync(resolved)) {
          const observed = computeContentDigest(resolved, ch.algorithm);
          if (observed !== ch.value.toLowerCase()) {
            errors.push(
              `${ctx}: content_hash does not match content on disk ` +
                `(declared ${ch.value.slice(0, 12)}…, observed ${observed ? observed.slice(0, 12) + "…" : "unreadable"}); ` +
                `run kcp sign --update-hashes before signing`
            );
          }
        }
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

  // --- Temporal validation (§4.22 unit-level; §3.6 manifests[].temporal) ---
  // Root-level temporal provides defaults; unit-level overrides field-by-field.
  const today = new Date().toISOString().slice(0, 10);
  const effectiveTemporal = (t?: Temporal): Temporal => {
    const r = manifest.temporal ?? {};
    const u = t ?? {};
    return {
      valid_from: u.valid_from ?? r.valid_from,
      valid_until: u.valid_until ?? r.valid_until,
      recorded_at: u.recorded_at ?? r.recorded_at,
      superseded_by: u.superseded_by ?? r.superseded_by,
    };
  };

  // Per-unit window + verification warnings; collect local superseded edges.
  const unitSuccessor = new Map<string, string>();
  for (const unit of manifest.units) {
    const t = effectiveTemporal(unit.temporal);
    if (t.valid_from && t.valid_until && t.valid_until < t.valid_from) {
      warnings.push(
        `Unit '${unit.id}': temporal.valid_until '${t.valid_until}' precedes valid_from '${t.valid_from}' (empty validity window — the unit can never be active)`
      );
    }
    if (t.valid_until && t.valid_until < today && !t.superseded_by) {
      warnings.push(
        `Unit '${unit.id}': temporal.valid_until '${t.valid_until}' is in the past and no superseded_by is set (stale unit with no successor)`
      );
    }
    // superseded_by may use namespace:id to target an unresolved include (§4.22);
    // only local (non-namespaced) refs are checkable here.
    if (t.superseded_by && !t.superseded_by.includes(":")) {
      if (!unitIds.has(t.superseded_by)) {
        warnings.push(
          `Unit '${unit.id}': temporal.superseded_by references unknown unit '${t.superseded_by}'`
        );
      } else {
        unitSuccessor.set(unit.id, t.superseded_by);
      }
    }
    const disc = unit.discovery;
    if (disc?.verification_status === "verified" && !disc.verified_by) {
      warnings.push(
        `Unit '${unit.id}': discovery.verification_status is 'verified' but discovery.verified_by is absent`
      );
    }
  }
  for (const id of supersededCycleIds(unitSuccessor)) {
    errors.push(`temporal.superseded_by cycle detected involving unit '${id}'`);
  }
  if (
    manifest.discovery?.verification_status === "verified" &&
    !manifest.discovery.verified_by
  ) {
    warnings.push(
      "manifest: discovery.verification_status is 'verified' but discovery.verified_by is absent"
    );
  }

  // Federation: manifests[].temporal (§3.6, RFC-0021).
  const refIds = new Set(manifest.manifests.map((m) => m.id));
  const refSuccessor = new Map<string, string>();
  for (const ref of manifest.manifests) {
    const t = ref.temporal;
    if (!t) continue;
    if (t.valid_from && t.valid_until && t.valid_until < t.valid_from) {
      warnings.push(
        `manifests['${ref.id}']: temporal.valid_until '${t.valid_until}' precedes valid_from '${t.valid_from}' (empty validity window)`
      );
    }
    if (t.valid_until && t.valid_until < today && !t.superseded_by) {
      warnings.push(
        `manifests['${ref.id}']: temporal.valid_until '${t.valid_until}' is in the past and no superseded_by is set (stale federation link)`
      );
    }
    if (t.superseded_by) {
      if (!refIds.has(t.superseded_by)) {
        warnings.push(
          `manifests['${ref.id}']: temporal.superseded_by references unknown manifests[].id '${t.superseded_by}'`
        );
      } else {
        refSuccessor.set(ref.id, t.superseded_by);
      }
    }
  }
  for (const id of supersededCycleIds(refSuccessor)) {
    errors.push(`manifests[].temporal.superseded_by cycle detected involving '${id}'`);
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
