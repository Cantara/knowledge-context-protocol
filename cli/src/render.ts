// kcp render — RFC-0018 Trusted Render Pipeline
//
// Consumes a knowledge.yaml and emits a derived artifact — never the
// original — with trust decisions made, recorded, and machine-checkable
// before any content reaches an agent.
//
// Deterministic (C1), LLM-free (C7), fail-closed (C2): a manifest at
// `failed` tier (invalid signature, or unsigned from a pinned origin)
// emits nothing and exits 2.
//
// Signature: detached `<manifest>.sig` JSON file
// { key_id, algorithm: "EdDSA", public_key: <base64 DER SPKI>,
//   signature: <base64> } over the exact manifest bytes — the
// detached-JWS EdDSA profile of RFC-0018 §4.2.

import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { createHash, createPublicKey, verify as cryptoVerify } from "node:crypto";
import { homedir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import yaml from "js-yaml";
import { lintFreeText, LINT_RULES_VERSION } from "./lint.js";

const green = (s: string) => `\x1b[32m${s}\x1b[0m`;
const red = (s: string) => `\x1b[31m${s}\x1b[0m`;
const dim = (s: string) => `\x1b[2m${s}\x1b[0m`;

export const RENDERER_VERSION = "kcp-cli 0.16.0";
export const RENDER_SCHEMA = "kcp-render-schema-0.1";
export const DEFAULT_KEYS_PATH = join(homedir(), ".kcp", "trusted-keys.yaml");

const KNOWN_KINDS = ["knowledge", "schema", "policy", "service", "executable"];
const NEVER_LOAD_KINDS = ["service", "executable"];

// Render-schema whitelist (§6.1): identifiers, paths, enums, dates,
// bounded-semantics fields only.
const TOP_SCALAR_FIELDS = ["project", "version", "updated", "language", "license"];
const UNIT_FIELDS = [
  "id", "kind", "path", "intent", "format", "content_type", "language",
  "scope", "audience", "license", "validated", "update_frequency",
  "triggers", "not_for",
];
const UNIT_FREE_TEXT_FIELDS = ["intent", "description", "label"];
const RELATIONSHIP_FIELDS = ["from", "to", "type"];
const FEDERATION_FIELDS = ["id", "url", "relationship"];
const PROVENANCE_FIELDS = ["publisher", "publisher_url", "contact"];

// §5.1: default tier→confidence mapping, monotone in tier.
const TIER_CONFIDENCE: Record<string, number> = { trusted: 0.7, known: 0.6, unsigned: 0.5 };

type RawMap = Record<string, unknown>;

export interface RenderOptions {
  manifestPath: string;
  keysPath?: string;
  /** Explicit origin override (§4.1 priority 1). Derived from git otherwise. */
  origin?: string;
  /** Opt-in `rendered_at` field; excluded from the determinism contract (C1). */
  timestamp?: boolean;
}

export type RenderTier = "trusted" | "known" | "unsigned";

export type RenderResult =
  | { ok: true; tier: RenderTier; origin: string; text: string }
  | { ok: false; tier: "failed"; origin: string; reason: string };

interface AllowlistKey {
  key_id?: string;
  public_key?: string;
  scope?: { domains?: string[] };
}

interface Allowlist {
  keys?: AllowlistKey[];
}

interface DetachedSignature {
  key_id?: string;
  algorithm?: string;
  public_key?: string;
  signature?: string;
}

interface TierDecision {
  tier: RenderTier | "failed";
  pinned: boolean;
  status: "valid" | "unknown-key" | "absent" | "invalid";
  keyId?: string;
  keySource?: string;
  reason?: string;
}

function sha256(buf: Buffer): string {
  return createHash("sha256").update(buf).digest("hex");
}

// --- Origin determination (§4.1, normative) ---

/**
 * Normalize a repository URL into an origin string: scheme and
 * credentials stripped, host lowercased, `.git` suffix removed.
 */
export function normalizeOrigin(url: string): string {
  let s = url.trim();
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(s)) {
    s = s.replace(/^[a-z][a-z0-9+.-]*:\/\//i, "");
  } else {
    // scp-like syntax: [user@]host:path
    const m = /^(?:[^@\s]+@)?([^:/\s]+):(.+)$/.exec(s);
    if (m) s = `${m[1]}/${m[2]}`;
  }
  const slash = s.indexOf("/");
  let host = slash === -1 ? s : s.slice(0, slash);
  const rest = slash === -1 ? "" : s.slice(slash);
  const at = host.lastIndexOf("@");
  if (at !== -1) host = host.slice(at + 1); // strip credentials
  s = host.toLowerCase() + rest;
  return s.replace(/\/+$/, "").replace(/\.git$/i, "");
}

/**
 * Derive the manifest origin per §4.1, in priority order:
 * (1) explicit --origin; (2) (federation fetches — not applicable to
 * local rendering); (3) the git remote named `origin` of the manifest's
 * directory, normalized. Falls back to "unknown", which can never match
 * a pinning scope.
 */
export function deriveOrigin(manifestPath: string, explicit?: string): string {
  if (explicit) return explicit;
  try {
    const dir = dirname(resolve(manifestPath));
    const url = execFileSync("git", ["-C", dir, "remote", "get-url", "origin"], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
    if (url) return normalizeOrigin(url);
  } catch {
    // no git, not a repository, or no remote named "origin"
  }
  return "unknown";
}

// --- Allowlist and scope pinning (§4.1, §9) ---

// §9: exact per-path-segment matching — no bare prefix match.
function scopeCovers(domain: string, origin: string): boolean {
  return origin === domain || origin.startsWith(domain + "/");
}

function loadAllowlist(keysPath: string | undefined): Allowlist {
  if (!keysPath || !existsSync(keysPath)) return { keys: [] };
  const doc = yaml.load(readFileSync(keysPath, "utf8"));
  if (!doc || typeof doc !== "object" || Array.isArray(doc)) return { keys: [] };
  return doc as Allowlist;
}

function originIsPinned(allowlist: Allowlist, origin: string): boolean {
  if (origin === "unknown") return false; // unknown origin never matches a scope
  return (allowlist.keys ?? []).some((k) =>
    (k.scope?.domains ?? []).some((d) => scopeCovers(d, origin))
  );
}

// --- Signature verification (§4.2 EdDSA profile) ---

function verifyDetachedSig(manifestBytes: Buffer, sig: DetachedSignature): boolean {
  try {
    if (!sig.public_key || !sig.signature) return false;
    const pub = createPublicKey({
      key: Buffer.from(sig.public_key, "base64"),
      format: "der",
      type: "spki",
    });
    return cryptoVerify(null, manifestBytes, pub, Buffer.from(sig.signature, "base64"));
  } catch {
    return false;
  }
}

// §4 + §4.1 tier computation.
function computeTier(
  manifestBytes: Buffer,
  sigPath: string,
  allowlist: Allowlist,
  origin: string
): TierDecision {
  const pinned = originIsPinned(allowlist, origin);
  if (!existsSync(sigPath)) {
    if (pinned) {
      return {
        tier: "failed", pinned, status: "absent",
        reason: "unsigned manifest from pinned origin (§4.1)",
      };
    }
    return { tier: "unsigned", pinned, status: "absent" };
  }
  let sig: DetachedSignature;
  try {
    sig = JSON.parse(readFileSync(sigPath, "utf8")) as DetachedSignature;
  } catch {
    return { tier: "failed", pinned, status: "invalid", reason: "unparseable signature file" };
  }
  if (!verifyDetachedSig(manifestBytes, sig)) {
    return { tier: "failed", pinned, status: "invalid", reason: "signature verification failed" };
  }
  const entry = (allowlist.keys ?? []).find(
    (k) => k.key_id === sig.key_id && k.public_key === sig.public_key
  );
  if (!entry) {
    // T4: valid signature, unknown key — gate, don't endorse.
    return { tier: "known", pinned, status: "unknown-key", keyId: sig.key_id };
  }
  const domains = entry.scope?.domains;
  if (domains && !domains.some((d) => scopeCovers(d, origin))) {
    // Allowlisted key used outside its declared scope (§9): the key may
    // not verify this origin, so it confers no allowlist standing here.
    return { tier: "known", pinned, status: "unknown-key", keyId: sig.key_id };
  }
  return { tier: "trusted", pinned, status: "valid", keyId: sig.key_id, keySource: "allowlist" };
}

// --- Render (sanitization §6, output contract §5) ---

interface DropEntry {
  path: string;
  reason: string;
}

interface QuarantineEntry {
  path: string;
  reason: string;
  rule?: string;
  original_sha256: string;
  action: string;
}

export function renderManifest(options: RenderOptions): RenderResult {
  const { manifestPath } = options;
  const manifestBytes = readFileSync(manifestPath);
  const allowlist = loadAllowlist(options.keysPath);
  const origin = deriveOrigin(manifestPath, options.origin);

  const trust = computeTier(manifestBytes, manifestPath + ".sig", allowlist, origin);
  if (trust.tier === "failed") {
    // Fail-closed (§3.1, R4, C2): emit nothing.
    return { ok: false, tier: "failed", origin, reason: trust.reason ?? "failed" };
  }
  const tier = trust.tier;

  const doc = (yaml.load(manifestBytes.toString("utf8")) ?? {}) as RawMap;
  const dropped: DropEntry[] = [];
  const quarantined: QuarantineEntry[] = [];
  let fieldsIn = 0;
  let fieldsRendered = 0;
  // Leaf-based counters (§5.2) so the bookkeeping identity holds exactly:
  // fields_in = fields_rendered + fields_dropped + fields_quarantined.
  let fieldsDropped = 0;
  let fieldsQuarantined = 0;

  // Every input leaf is counted exactly once as rendered, dropped,
  // quarantined, or consumed-by-renderer (signing/provenance metadata).
  const countLeaves = (v: unknown): number => {
    if (Array.isArray(v)) {
      // an array of scalars counts as one leaf (§5.2)
      return v.every((x) => typeof x !== "object")
        ? 1
        : v.reduce((n: number, x) => n + countLeaves(x), 0);
    }
    if (v && typeof v === "object") {
      return Object.values(v).reduce((n: number, x) => n + countLeaves(x), 0);
    }
    return 1;
  };

  const take = (
    src: RawMap,
    allowed: string[],
    basePath: string,
    out: RawMap,
    freeTextFields: string[] = []
  ): void => {
    for (const [k, v] of Object.entries(src)) {
      const leafCount = countLeaves(v);
      fieldsIn += leafCount;
      if (!allowed.includes(k)) {
        dropped.push({ path: `${basePath}${k}`, reason: "not_in_schema" });
        fieldsDropped += leafCount;
        continue;
      }
      if (freeTextFields.includes(k)) {
        const verdict = lintFreeText(v);
        if (verdict.flagged) {
          fieldsQuarantined += leafCount;
          quarantined.push({
            path: `${basePath}${k}`,
            reason: "imperative_mood",
            ...(verdict.rule ? { rule: verdict.rule } : {}),
            original_sha256: sha256(Buffer.from(String(v), "utf8")),
            action: "held_for_review",
          });
          continue;
        }
      }
      out[k] = v;
      fieldsRendered += leafCount;
    }
  };

  // --- project block -----------------------------------------------------
  const project: RawMap = {};
  const topScalars: RawMap = {};
  for (const f of TOP_SCALAR_FIELDS) if (doc[f] !== undefined) topScalars[f] = doc[f];
  take(topScalars, TOP_SCALAR_FIELDS, "", project);
  if (project.project !== undefined) {
    project.name = project.project;
    delete project.project;
  }

  // --- units ---------------------------------------------------------------
  const units: RawMap[] = [];
  ((doc.units as RawMap[] | undefined) ?? []).forEach((unit, i) => {
    const out: RawMap = {};
    const base = `units[${i}].`;
    // kind is enum-checked before the generic whitelist pass
    const kind = unit.kind === undefined ? "knowledge" : String(unit.kind);
    let unknownKind = false;
    if (!KNOWN_KINDS.includes(kind)) {
      // §6.3: unknown kinds fail closed in the renderer (diverges from
      // SPEC.md §4.3a parser leniency, deliberately).
      fieldsIn += 1;
      fieldsDropped += 1;
      dropped.push({ path: `${base}kind`, reason: "unknown_kind" });
      unknownKind = true;
    }
    const rest: RawMap = { ...unit };
    if (unknownKind) delete rest.kind;
    take(rest, UNIT_FIELDS, base, out, UNIT_FREE_TEXT_FIELDS);
    // §6.3 load eligibility — unconditional, even at trusted tier (C4)
    if (unknownKind || NEVER_LOAD_KINDS.includes(kind)) {
      out.load_eligible = false;
      out.invocation = "explicit";
    } else {
      out.load_eligible = tier === "trusted";
    }
    units.push(out);
  });

  // --- relationships -------------------------------------------------------
  const relationships: RawMap[] = [];
  ((doc.relationships as RawMap[] | undefined) ?? []).forEach((rel, i) => {
    const out: RawMap = {};
    take(rel, RELATIONSHIP_FIELDS, `relationships[${i}].`, out);
    relationships.push(out);
  });

  // --- federation (§7) -------------------------------------------------------
  const federation: RawMap[] = [];
  ((doc.manifests as RawMap[] | undefined) ?? []).forEach((edge, i) => {
    const out: RawMap = {};
    take(edge, FEDERATION_FIELDS, `manifests[${i}].`, out);
    out.target_tier = "unrendered"; // trust never inherited (C5)
    federation.push(out);
  });

  // --- trust passthrough -------------------------------------------------------
  let provenance: RawMap | undefined;
  if (doc.trust && typeof doc.trust === "object" && !Array.isArray(doc.trust)) {
    for (const [k, v] of Object.entries(doc.trust as RawMap)) {
      const leafCount = countLeaves(v);
      fieldsIn += leafCount;
      if (k === "provenance" && v && typeof v === "object" && !Array.isArray(v)) {
        provenance = {};
        for (const [pk, pv] of Object.entries(v as RawMap)) {
          if (PROVENANCE_FIELDS.includes(pk)) {
            provenance[pk] = pv;
            fieldsRendered += countLeaves(pv);
          } else {
            dropped.push({ path: `trust.provenance.${pk}`, reason: "not_in_schema" });
            fieldsDropped += countLeaves(pv);
          }
        }
      } else if (k === "content_integrity") {
        // consumed by tier evaluation, never re-emitted
        dropped.push({ path: `trust.${k}`, reason: "consumed_by_renderer" });
        fieldsDropped += leafCount;
      } else {
        dropped.push({ path: `trust.${k}`, reason: "not_in_schema" });
        fieldsDropped += leafCount;
      }
    }
  }

  // --- remaining top-level blocks ----------------------------------------------
  const handled = new Set([
    ...TOP_SCALAR_FIELDS, "kcp_version", "units", "relationships", "manifests", "trust",
  ]);
  for (const [k, v] of Object.entries(doc)) {
    if (handled.has(k)) continue;
    fieldsIn += countLeaves(v);
    fieldsDropped += countLeaves(v);
    dropped.push({ path: k, reason: "not_in_schema" });
  }

  // --- assemble (deterministic order; no timestamp by default, C1) --------------
  const renderBlock: RawMap = {
    kcp_version: String(doc.kcp_version ?? "unspecified"),
    renderer: RENDERER_VERSION,
    lint_rules: LINT_RULES_VERSION,
    source: { path: basename(manifestPath), sha256: sha256(manifestBytes) },
  };
  if (options.timestamp) renderBlock.rendered_at = new Date().toISOString();

  const output: RawMap = {
    render: renderBlock,
    trust: {
      tier,
      origin,
      pinned: trust.pinned,
      signature: {
        method: "jws",
        algorithm: "EdDSA",
        ...(trust.keyId ? { key_id: trust.keyId } : {}),
        ...(trust.keySource ? { key_source: trust.keySource } : {}),
        status: trust.status,
      },
      ...(provenance && Object.keys(provenance).length ? { provenance } : {}),
    },
    discovery: {
      verification_status: "declared", // §5.1
      source: "manifest-self-description",
      confidence: TIER_CONFIDENCE[tier],
    },
    project,
    units,
    ...(relationships.length ? { relationships } : {}),
    ...(federation.length ? { federation } : {}),
    sanitization: {
      schema: RENDER_SCHEMA,
      dropped,
      quarantined,
      stats: {
        fields_in: fieldsIn,
        fields_rendered: fieldsRendered,
        fields_dropped: fieldsDropped,
        fields_quarantined: fieldsQuarantined,
      },
    },
  };

  const text = yaml.dump(output, { lineWidth: -1, noRefs: true });
  return { ok: true, tier, origin, text };
}

// --- CLI entry point ---

export interface RunRenderOptions {
  manifestPath: string;
  keys?: string;
  origin?: string;
  out?: string;
  timestamp?: boolean;
}

function expandHome(p: string): string {
  if (p === "~") return homedir();
  if (p.startsWith("~/")) return join(homedir(), p.slice(2));
  return p;
}

export function runRender(options: RunRenderOptions): void {
  const keysPath = options.keys !== undefined ? expandHome(options.keys) : DEFAULT_KEYS_PATH;

  let result: RenderResult;
  try {
    result = renderManifest({
      manifestPath: options.manifestPath,
      keysPath,
      origin: options.origin,
      timestamp: options.timestamp,
    });
  } catch (err) {
    process.stderr.write(red(`Error: ${err instanceof Error ? err.message : String(err)}\n`));
    process.exit(1);
  }

  if (!result.ok) {
    // Fail-closed (R4, C2): nothing emitted, exit 2.
    process.stderr.write(red(`✗ render refused: tier=failed (${result.reason})\n`));
    process.exit(2);
  }

  if (options.out) {
    writeFileSync(options.out, result.text);
    process.stdout.write(
      `${green(`✓ rendered (tier: ${result.tier})`)} ${dim(`→ ${options.out}`)}\n`
    );
  } else {
    process.stdout.write(result.text);
  }
}
