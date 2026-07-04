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
//
// RFC-0019 (draft) additions: per-unit content_hash verification (C11)
// and origin evidence classes with the trust-escalation cap (C13) —
// together closing the T9 manifest-relocation attack. Corroboration
// (§4.3) verifies the *manifest* against its claimed origin; it cannot
// verify the *checkout*, so escalation that rests on corroboration never
// extends standing-context eligibility to hash-less units (C14).

import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { createHash, createPublicKey, verify as cryptoVerify } from "node:crypto";
import { homedir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import yaml from "js-yaml";
import { lintFreeText, LINT_RULES_VERSION } from "./lint.js";
import { computeContentDigest, HASH_ALGORITHMS } from "./validator.js";

export { computeContentDigest, HASH_ALGORITHMS };

const green = (s: string) => `\x1b[32m${s}\x1b[0m`;
const red = (s: string) => `\x1b[31m${s}\x1b[0m`;
const dim = (s: string) => `\x1b[2m${s}\x1b[0m`;

export const RENDERER_VERSION = "kcp-cli 0.25.0";
export const RENDER_SCHEMA = "kcp-render-schema-0.2";
export const DEFAULT_KEYS_PATH = join(homedir(), ".kcp", "trusted-keys.yaml");

const KNOWN_KINDS = ["knowledge", "schema", "policy", "service", "executable"];
const NEVER_LOAD_KINDS = ["service", "executable"];

// Render-schema whitelist (§6.1) — loaded from the authoritative
// schema/render-schema.json (the file RENDER_SCHEMA names). Update
// that file to change what the renderer emits; do not edit here.
interface RenderSchemaFile {
  top_scalars: string[];
  unit: { fields: string[]; free_text: string[] };
  manifest_blocks: string[];
  content_structure: { fields: string[] };
  relationship: { fields: string[] };
  federation: { fields: string[] };
  provenance: { fields: string[] };
  agent_requirements: { fields: string[] };
}
const _rs = JSON.parse(
  readFileSync(new URL("../schema/render-schema.json", import.meta.url), "utf8")
) as RenderSchemaFile;

const TOP_SCALAR_FIELDS = _rs.top_scalars;
const UNIT_FIELDS = _rs.unit.fields;
// Free-text fields subject to the imperative lint (§6.2). `triggers` and
// `not_for` are list-valued; lintFreeText handles arrays element-wise.
const UNIT_FREE_TEXT_FIELDS = _rs.unit.free_text;
// §4.19 content_structure is a bounded block: only these sub-fields pass,
// sub-whitelisted separately so unknown nested keys cannot smuggle (T5).
const CONTENT_STRUCTURE_FIELDS = _rs.content_structure.fields;
const RELATIONSHIP_FIELDS = _rs.relationship.fields;
const FEDERATION_FIELDS = _rs.federation.fields;
const PROVENANCE_FIELDS = _rs.provenance.fields;
// §3.2 trust.agent_requirements passthrough (v0.22). Surfaced as data only — the
// renderer never dereferences attestation_url/jwks (C19; deterministic + network-free).
const AGENT_REQ_FIELDS = _rs.agent_requirements.fields;
// §4.14/§4.15 economic blocks (payment, rate_limits) surfaced at manifest level as
// data (v0.25). Advisory declarations — the renderer copies the numbers/enums/URLs
// through and never dereferences a wallet, plans_url, or upgrade_url.
const MANIFEST_BLOCK_FIELDS = _rs.manifest_blocks;

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
  /**
   * RFC-0019 §4.3: accept `derived` origin evidence for trust escalation.
   * Off by default — repo-resident bytes may restrict trust, never extend it.
   */
  allowDerivedOrigin?: boolean;
  /** RFC-0019 §3.3: deny standing-context eligibility to hash-less units at trusted tier. */
  requireUnitHashes?: boolean;
  /**
   * RFC-0019 §4.3: outcome of an origin-corroboration attempt, performed
   * by the caller *before* rendering (the renderer itself stays offline
   * and deterministic; the outcome is part of the C1 input).
   */
  corroboration?: CorroborationOutcome;
  /**
   * RFC-0020/0022 §3.11: resolved `composition.includes`, fetched and
   * integrity-checked by the caller *before* rendering. The renderer merges
   * and tiers them deterministically; the network/fs access is the caller's
   * (same offline-core contract as corroboration). When the manifest declares
   * a `composition` block but this is absent, includes are treated as
   * unverified (fail-safe).
   */
  resolvedIncludes?: ResolvedInclude[];
}

export interface CorroborationOutcome {
  url: string;
  result: "matched" | "mismatch" | "unreachable";
}

/**
 * RFC-0022 §2: a `composition.includes[]` entry resolved to its source bytes
 * and an integrity verdict. `verified` (a present pin matched), `unverified`
 * (no integrity declared), or `failed` (a declared pin did not match, or the
 * source was unreachable). C17 reads `verification` to gate load-eligibility.
 */
export interface ResolvedInclude {
  source: string;
  as?: string;
  /** Raw bytes of the included manifest, or null if unreadable/unreachable. */
  bytes: string | null;
  verification: "verified" | "unverified" | "failed";
  reason?: string;
  expected?: string;
  observed?: string;
}

/**
 * RFC-0019 §4.1: who controlled the bytes the origin was derived from.
 * `asserted` (consumer flag) and `fetched` (consumer's own channel) may
 * satisfy trust escalation; `derived` (the checkout's own .git/config)
 * and `none` may only pin.
 */
export type OriginEvidence = "asserted" | "fetched" | "derived" | "none";

export type RenderTier = "trusted" | "known" | "unsigned";

export type RenderResult =
  | { ok: true; tier: RenderTier; origin: string; text: string; warnings: string[] }
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
  /** RFC-0019 §4.2: trusted conditions met, escalation withheld. */
  capped?: string;
}

function sha256(buf: Buffer): string {
  return createHash("sha256").update(buf).digest("hex");
}

// --- Per-unit content hashes (RFC-0019 §3, C11) ---
// The digest implementation lives in validator.ts (which is kept
// byte-identical with the bridge's validator); re-exported here for the
// render/sign/test call sites that historically import it from render.

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
 * a pinning scope. Each derivation carries its RFC-0019 evidence class.
 */
export function deriveOrigin(
  manifestPath: string,
  explicit?: string
): { origin: string; evidence: OriginEvidence } {
  if (explicit) return { origin: explicit, evidence: "asserted" };
  try {
    const dir = dirname(resolve(manifestPath));
    const url = execFileSync("git", ["-C", dir, "remote", "get-url", "origin"], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
    // The remote URL lives in the checkout's own .git/config — bytes the
    // directory's producer may control (tarball with a fabricated .git).
    if (url) return { origin: normalizeOrigin(url), evidence: "derived" };
  } catch {
    // no git, not a repository, or no remote named "origin"
  }
  return { origin: "unknown", evidence: "none" };
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

// §4 + §4.1 tier computation, with the RFC-0019 §4.2 escalation rule.
function computeTier(
  manifestBytes: Buffer,
  sigPath: string,
  allowlist: Allowlist,
  origin: string,
  evidence: OriginEvidence,
  allowDerivedOrigin: boolean
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
    // Valid signature, key not on allowlist. Normally `known` (T4: gate,
    // don't endorse). But on a pinned origin, §4.1 requires a signature
    // from a key scoped to that origin — a non-allowlisted key does not
    // satisfy the pin, so this is the signature-replacement case and fails.
    if (pinned) {
      return {
        tier: "failed", pinned, status: "unknown-key",
        reason: "valid signature from non-allowlisted key on pinned origin (§4.1)",
      };
    }
    return { tier: "known", pinned, status: "unknown-key", keyId: sig.key_id };
  }
  const domains = entry.scope?.domains;
  if (domains && !domains.some((d) => scopeCovers(d, origin))) {
    // Allowlisted key used outside its declared scope (§9): the key may
    // not verify this origin, so it confers no allowlist standing here.
    // On a pinned origin this is again a failed signing expectation (§4.1).
    if (pinned) {
      return {
        tier: "failed", pinned, status: "unknown-key",
        reason: "signing key out of scope for pinned origin (§4.1)",
      };
    }
    return { tier: "known", pinned, status: "unknown-key", keyId: sig.key_id };
  }
  // RFC-0019 §4.2 (C13): all trusted conditions hold, but an in-scope
  // origin read from the checkout's own bytes is not evidence the
  // manifest is actually *at* that origin (T9 relocation). Pinning above
  // accepted any evidence class — strictness may rest on weak evidence;
  // escalation may not.
  if (evidence !== "asserted" && evidence !== "fetched" && !allowDerivedOrigin) {
    return {
      tier: "known", pinned, status: "valid", keyId: sig.key_id,
      keySource: "allowlist", capped: "origin_evidence_derived",
    };
  }
  return { tier: "trusted", pinned, status: "valid", keyId: sig.key_id, keySource: "allowlist" };
}

// --- Render (sanitization §6, output contract §5) ---

interface DropEntry {
  path: string;
  reason: string;
  /** content_hash_mismatch entries (RFC-0019 §3.3) record both digests. */
  algorithm?: string;
  expected?: string;
  observed?: string;
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
  const derived = deriveOrigin(manifestPath, options.origin);
  const origin = derived.origin;
  let evidence = derived.evidence;

  // RFC-0019 §4.3: a matched corroboration upgrades derived evidence —
  // the manifest has now been observed at its claimed origin over the
  // consumer's own channel. It says nothing about the *checkout* (the
  // files around the manifest), which is why the C14 flag below narrows
  // what the upgrade may grant.
  const corroborated =
    evidence === "derived" && options.corroboration?.result === "matched";
  if (corroborated) evidence = "fetched";

  const trust = computeTier(
    manifestBytes, manifestPath + ".sig", allowlist, origin,
    evidence, options.allowDerivedOrigin ?? false
  );
  // C14: does the trusted tier rest on corroboration alone?
  const escalationByCorroboration =
    corroborated && trust.tier === "trusted" && !options.allowDerivedOrigin;
  if (trust.tier === "failed") {
    // Fail-closed (§3.1, R4, C2): emit nothing.
    return { ok: false, tier: "failed", origin, reason: trust.reason ?? "failed" };
  }
  const tier = trust.tier;

  const warnings: string[] = [];
  if (trust.capped) {
    warnings.push(
      "trusted tier withheld: origin evidence is derived from the checkout's own git config (RFC-0019 C13). " +
        "Pass --origin from the component that cloned the repository, or --allow-derived-origin to accept the relocation risk."
    );
  }
  if (options.corroboration && options.corroboration.result !== "matched") {
    warnings.push(
      `origin corroboration ${options.corroboration.result} (${options.corroboration.url}); evidence remains ${evidence} (RFC-0019 §4.3)`
    );
  }
  if (escalationByCorroboration) {
    warnings.push(
      "trusted tier rests on corroboration: the manifest was verified at its claimed origin, the surrounding checkout was not — hash-less units stay out of standing context (RFC-0019 C14)"
    );
  }
  // §16.2: with a non-empty allowlist, an undeterminable origin means no
  // scope can pin this manifest — surface it rather than silently
  // rendering at unsigned (the T7 downgrade the SHOULD-warn exists for).
  if (origin === "unknown" && (allowlist.keys?.length ?? 0) > 0) {
    warnings.push(
      "origin could not be derived (no --origin, no git remote); scope pinning cannot apply, so a manifest that should be pinned renders unauthenticated (§16.2)"
    );
  }

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

  // --- composition resolution (§3.11, RFC-0020/0022) -----------------------
  // Tiering above already used the composing file's signature; here we merge
  // included units. C17 then gates their load-eligibility by the include's
  // integrity verdict — an unverified or substituted include cannot reach
  // standing context even though the composed tier is `trusted`.
  type IncludeVerdict = "verified" | "unverified" | "failed";
  interface MergedUnit { unit: RawMap; include?: { verification: IncludeVerdict }; }
  const mergedUnits: MergedUnit[] = [];
  const composition = doc.composition as RawMap | undefined;
  if (composition && typeof composition === "object" && !Array.isArray(composition)) {
    const includeDefs = (composition.includes as RawMap[] | undefined) ?? [];
    const resolved = options.resolvedIncludes ?? [];
    includeDefs.forEach((inc, idx) => {
      const as = inc.as !== undefined ? String(inc.as) : undefined;
      const r = resolved[idx];
      const verification: IncludeVerdict = r ? r.verification : "unverified";
      if (verification === "failed") {
        warnings.push(
          `composition include '${String(inc.source ?? idx)}' failed integrity` +
            (r?.reason ? ` (${r.reason})` : "") +
            "; its units render as pointers at every tier (RFC-0022 C17)"
        );
      } else if (verification === "unverified" && tier === "trusted") {
        warnings.push(
          `composition include '${String(inc.source ?? idx)}' is unverified (no integrity pin); ` +
            "its units are pointer-only at trusted tier — add integrity.manifest_hash or expected_signer (RFC-0022 C17)"
        );
      }
      let includedUnits: RawMap[] = [];
      if (r && r.bytes) {
        try {
          const idoc = (yaml.load(r.bytes) ?? {}) as RawMap;
          includedUnits = (idoc.units as RawMap[] | undefined) ?? [];
        } catch { /* unparseable include → contributes no units */ }
      }
      for (const u of includedUnits) {
        const nu: RawMap = { ...u };
        if (as !== undefined && nu.id !== undefined) nu.id = `${as}:${String(nu.id)}`;
        mergedUnits.push({ unit: nu, include: { verification } });
      }
    });
    // overrides → excludes (§3.11 resolution order), matched by (namespaced) id
    for (const ov of ((composition.overrides as RawMap[] | undefined) ?? [])) {
      const id = ov.id !== undefined ? String(ov.id) : undefined;
      if (!id) continue;
      const target = mergedUnits.find((m) => String(m.unit.id) === id);
      if (target) {
        for (const [k, v] of Object.entries(ov)) if (k !== "id") target.unit[k] = v;
      } else {
        warnings.push(`composition override references unknown unit id '${id}' (§3.11)`);
      }
    }
    for (const ex of ((composition.excludes as RawMap[] | undefined) ?? [])) {
      const id = ex.id !== undefined ? String(ex.id) : undefined;
      if (!id) continue;
      const before = mergedUnits.length;
      for (let j = mergedUnits.length - 1; j >= 0; j--) {
        if (String(mergedUnits[j].unit.id) === id) mergedUnits.splice(j, 1);
      }
      if (mergedUnits.length === before) {
        warnings.push(`composition exclude references unknown unit id '${id}' (§3.11)`);
      }
    }
    // the composition block is consumed by resolution; never re-emitted
    const compLeaves = countLeaves(composition);
    fieldsIn += compLeaves;
    fieldsDropped += compLeaves;
    dropped.push({ path: "composition", reason: "consumed_by_renderer" });
  }
  // local units are merged last and win on all collisions (§3.11)
  for (const u of ((doc.units as RawMap[] | undefined) ?? [])) mergedUnits.push({ unit: u });

  // --- units ---------------------------------------------------------------
  const units: RawMap[] = [];
  mergedUnits.forEach(({ unit, include }, i) => {
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
    // content_structure (§4.19) is handled separately so its bounded
    // sub-fields can be sub-whitelisted rather than copied verbatim.
    const cs = rest.content_structure;
    delete rest.content_structure;
    // content_hash (RFC-0019 §3) is consumed by verification, never re-emitted.
    const contentHash = rest.content_hash;
    delete rest.content_hash;
    take(rest, UNIT_FIELDS, base, out, UNIT_FREE_TEXT_FIELDS);
    if (cs !== undefined) {
      const csOut: RawMap = {};
      if (cs !== null && typeof cs === "object" && !Array.isArray(cs)) {
        for (const [k, v] of Object.entries(cs as RawMap)) {
          const n = countLeaves(v);
          fieldsIn += n;
          if (CONTENT_STRUCTURE_FIELDS.includes(k)) {
            csOut[k] = v;
            fieldsRendered += n;
          } else {
            dropped.push({ path: `${base}content_structure.${k}`, reason: "not_in_schema" });
            fieldsDropped += n;
          }
        }
        if (Object.keys(csOut).length > 0) out.content_structure = csOut;
      } else {
        // malformed (non-object) content_structure: drop wholesale
        fieldsIn += countLeaves(cs);
        fieldsDropped += countLeaves(cs);
        dropped.push({ path: `${base}content_structure`, reason: "not_in_schema" });
      }
    }
    // content_hash verification (RFC-0019 §3.3, C11). Runs at every tier;
    // tier governs placement, the hash governs whether the bytes at the
    // path are the bytes the key-holder signed.
    let contentVerified: true | "mismatch" | "absent" = "absent";
    if (contentHash !== undefined && include) {
      // included unit: any content_hash is relative to the included source,
      // not this checkout — not locally verifiable. The include's integrity
      // verdict (C17) governs load-eligibility instead.
      const leafCount = countLeaves(contentHash);
      fieldsIn += leafCount;
      fieldsDropped += leafCount;
      dropped.push({ path: `${base}content_hash`, reason: "consumed_by_renderer" });
    } else if (contentHash !== undefined) {
      const leafCount = countLeaves(contentHash);
      fieldsIn += leafCount;
      fieldsDropped += leafCount;
      const ch = contentHash as RawMap;
      const wellFormed =
        ch !== null && typeof ch === "object" && !Array.isArray(ch) &&
        HASH_ALGORITHMS.includes(String(ch.algorithm)) &&
        typeof ch.value === "string" && /^[0-9a-fA-F]+$/.test(ch.value);
      if (!wellFormed) {
        // malformed declarations fail closed, same as a mismatch
        contentVerified = "mismatch";
        dropped.push({ path: `${base}content_hash`, reason: "content_hash_invalid" });
      } else {
        const algorithm = String(ch.algorithm);
        const expected = (ch.value as string).toLowerCase();
        const observed = unit.path
          ? computeContentDigest(
              resolve(dirname(resolve(manifestPath)), String(unit.path)), algorithm)
          : undefined;
        if (observed === expected) {
          contentVerified = true;
          dropped.push({ path: `${base}content_hash`, reason: "consumed_by_renderer" });
        } else {
          contentVerified = "mismatch";
          dropped.push({
            path: `${base}content_hash`, reason: "content_hash_mismatch",
            algorithm, expected, observed: observed ?? "unreadable",
          });
          warnings.push(
            `unit '${String(unit.id ?? `units[${i}]`)}' content does not match its signed content_hash; demoted to pointer (RFC-0019 §3.3)`
          );
        }
      }
    }

    // §6.3 load eligibility — unconditional, even at trusted tier (C4),
    // then narrowed by content verification (RFC-0019 §3.3) and, when the
    // tier rests on corroboration, restricted to hash-verified units (C14:
    // corroboration vouched for the manifest, not the checkout).
    if (unknownKind || NEVER_LOAD_KINDS.includes(kind)) {
      out.load_eligible = false;
      out.invocation = "explicit";
    } else if (include && include.verification !== "verified") {
      // C17 (RFC-0022): a unit from an `unverified` or `failed` composition
      // include is never load-eligible. (Below `trusted` nothing is anyway, so
      // a single `false` covers both the trusted-tier cap and failed-at-every-tier.)
      out.load_eligible = false;
    } else {
      out.load_eligible =
        tier === "trusted" &&
        contentVerified !== "mismatch" &&
        !(contentVerified === "absent" &&
          (options.requireUnitHashes || escalationByCorroboration));
    }
    out.content_verified = contentVerified;
    // C19 (v0.22): mark a restricted unit when the manifest declares require_attestation.
    // The renderer *declares* the requirement as data; it never performs attestation and does
    // not, on this basis alone, set load_eligible: false — that gate is the bridge's (C20).
    const arBlock = (doc.trust as RawMap | undefined)?.["agent_requirements"] as RawMap | undefined;
    if (arBlock?.["require_attestation"] === true && String(unit.access ?? "") === "restricted") {
      out.requires_attestation = true;
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
  let agentRequirements: RawMap | undefined;
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
      } else if (k === "agent_requirements" && v && typeof v === "object" && !Array.isArray(v)) {
        // C19: surface agent_requirements as data; whitelist its fields. The renderer never
        // dereferences attestation_url/jwks — it only copies the declared strings through.
        agentRequirements = {};
        for (const [ak, av] of Object.entries(v as RawMap)) {
          if (AGENT_REQ_FIELDS.includes(ak)) {
            agentRequirements[ak] = av;
            fieldsRendered += countLeaves(av);
          } else {
            dropped.push({ path: `trust.agent_requirements.${ak}`, reason: "not_in_schema" });
            fieldsDropped += countLeaves(av);
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

  // --- manifest-level economic blocks (§4.14/§4.15, v0.25) ---------------------
  // Surfaced as data so a cost-aware agent can plan before loading. Pure economic
  // declarations (tiers, prices, limits, URLs) — no free-text lint, never dereferenced.
  const economics: RawMap = {};
  for (const blk of MANIFEST_BLOCK_FIELDS) {
    const v = doc[blk];
    if (v === undefined) continue;
    const n = countLeaves(v);
    fieldsIn += n;
    if (v && typeof v === "object" && !Array.isArray(v)) {
      economics[blk] = v;
      fieldsRendered += n;
    } else {
      dropped.push({ path: blk, reason: "not_in_schema" });
      fieldsDropped += n;
    }
  }

  // --- remaining top-level blocks ----------------------------------------------
  const handled = new Set([
    ...TOP_SCALAR_FIELDS, "kcp_version", "units", "relationships", "manifests", "trust",
    "composition", // consumed during resolution above (counted there)
    ...MANIFEST_BLOCK_FIELDS, // surfaced as economics above
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
      origin_evidence: evidence, // RFC-0019 §4.1, auditable like origin/pinned (R5)
      pinned: trust.pinned,
      ...(trust.capped ? { reason: trust.capped } : {}),
      ...(options.corroboration
        ? { corroboration: { url: options.corroboration.url, result: options.corroboration.result } }
        : {}),
      signature: {
        method: "jws",
        algorithm: "EdDSA",
        ...(trust.keyId ? { key_id: trust.keyId } : {}),
        ...(trust.keySource ? { key_source: trust.keySource } : {}),
        status: trust.status,
      },
      ...(provenance && Object.keys(provenance).length ? { provenance } : {}),
      ...(agentRequirements && Object.keys(agentRequirements).length ? { agent_requirements: agentRequirements } : {}),
    },
    discovery: {
      verification_status: "declared", // §5.1
      source: "manifest-self-description",
      confidence: TIER_CONFIDENCE[tier],
    },
    project,
    ...(economics.payment !== undefined ? { payment: economics.payment } : {}),
    ...(economics.rate_limits !== undefined ? { rate_limits: economics.rate_limits } : {}),
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
  return { ok: true, tier, origin, text, warnings };
}

// --- Origin corroboration (RFC-0019 §4.3) ---
//
// An evidence-resolution step that precedes rendering: fetch the manifest
// from the derived origin over the consumer's own channel and compare
// bytes. The outcome — never the network — feeds into renderManifest, so
// the render core stays offline and deterministic (C1, C7).

/** Map an origin to its manifest URL: explicit override, forge mapping, generic fallback. */
export function resolveCorroborationUrl(origin: string, explicit?: string): string {
  if (explicit) return explicit;
  const gh = /^github\.com\/([^/]+)\/([^/]+)$/.exec(origin);
  if (gh) return `https://raw.githubusercontent.com/${gh[1]}/${gh[2]}/HEAD/knowledge.yaml`;
  return `https://${origin}/knowledge.yaml`;
}

export async function corroborateOrigin(
  manifestBytes: Buffer,
  origin: string,
  explicitUrl?: string
): Promise<CorroborationOutcome> {
  const url = resolveCorroborationUrl(origin, explicitUrl);
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(10_000), redirect: "follow" });
    if (!res.ok) return { url, result: "unreachable" };
    const remote = Buffer.from(await res.arrayBuffer());
    return { url, result: remote.equals(manifestBytes) ? "matched" : "mismatch" };
  } catch {
    return { url, result: "unreachable" };
  }
}

// --- Composition resolution (§3.11, RFC-0020/0022) ---
//
// A pre-render step, like corroboration: fetch/read each `composition.includes`
// source over the consumer's channel and verify its integrity pin. The
// per-include verdict — never the network — feeds renderManifest, keeping the
// render core deterministic and offline (C1, C7). C17 then gates the included
// units' load-eligibility on that verdict.

export async function resolveComposition(
  doc: RawMap,
  manifestDir: string,
  allowlist?: Allowlist
): Promise<ResolvedInclude[]> {
  const composition = doc.composition as RawMap | undefined;
  if (!composition || typeof composition !== "object") return [];
  const includes = (composition.includes as RawMap[] | undefined) ?? [];
  const out: ResolvedInclude[] = [];

  for (const inc of includes) {
    const source = String(inc.source ?? "");
    const as = inc.as !== undefined ? String(inc.as) : undefined;
    const integrity = inc.integrity as RawMap | undefined;
    const isUrl = /^https?:\/\//i.test(source);

    // Obtain the included source bytes over the consumer's own channel.
    let bytes: string | null = null;
    try {
      if (isUrl) {
        const res = await fetch(source, { signal: AbortSignal.timeout(10_000), redirect: "follow" });
        if (res.ok) bytes = Buffer.from(await res.arrayBuffer()).toString("utf8");
      } else if (source) {
        bytes = readFileSync(resolve(manifestDir, source), "utf8");
      }
    } catch {
      bytes = null;
    }

    if (bytes === null) {
      out.push({ source, as, bytes: null, verification: "failed", reason: "source unreachable" });
      continue;
    }

    // Verify the integrity pin, if any. manifest_hash (exact bytes) is the
    // strong primitive; expected_signer pins a detached signature on the source.
    let verification: ResolvedInclude["verification"] = "unverified";
    let reason: string | undefined;
    let expected: string | undefined;
    let observed: string | undefined;
    const mh = integrity?.manifest_hash as RawMap | undefined;
    const signer = integrity?.expected_signer !== undefined ? String(integrity.expected_signer) : undefined;

    if (mh && typeof mh === "object") {
      const alg = HASH_ALGORITHMS.includes(String(mh.algorithm)) ? String(mh.algorithm) : "sha256";
      expected = String(mh.value ?? "").toLowerCase();
      observed = createHash(alg).update(Buffer.from(bytes, "utf8")).digest("hex");
      verification = observed === expected ? "verified" : "failed";
      if (verification === "failed") reason = "manifest_hash mismatch";
    } else if (signer) {
      // fetch the source's detached .sig and verify it is from the pinned key
      let sigText: string | null = null;
      try {
        if (isUrl) {
          const sres = await fetch(source + ".sig", { signal: AbortSignal.timeout(10_000), redirect: "follow" });
          if (sres.ok) sigText = await sres.text();
        } else {
          sigText = readFileSync(resolve(manifestDir, source) + ".sig", "utf8");
        }
      } catch { sigText = null; }
      const sig = sigText ? (JSON.parse(sigText) as DetachedSignature) : null;
      const ok = !!sig && sig.key_id === signer &&
        verifyDetachedSig(Buffer.from(bytes, "utf8"), sig) &&
        // the pinned key must also be on the consumer allowlist with matching bytes
        (allowlist?.keys ?? []).some((k) => k.key_id === signer && k.public_key === sig.public_key);
      verification = ok ? "verified" : "failed";
      if (!ok) reason = "expected_signer not satisfied";
    }

    out.push({ source, as, bytes, verification, reason, expected, observed });
  }
  return out;
}

// --- CLI entry point ---

export interface RunRenderOptions {
  manifestPath: string;
  keys?: string;
  origin?: string;
  out?: string;
  timestamp?: boolean;
  allowDerivedOrigin?: boolean;
  requireUnitHashes?: boolean;
  corroborate?: boolean;
  corroborateUrl?: string;
}

function expandHome(p: string): string {
  if (p === "~") return homedir();
  if (p.startsWith("~/")) return join(homedir(), p.slice(2));
  return p;
}

export async function runRender(options: RunRenderOptions): Promise<void> {
  const keysPath = options.keys !== undefined ? expandHome(options.keys) : DEFAULT_KEYS_PATH;

  let result: RenderResult;
  try {
    // Corroboration happens here, before the deterministic core, and only
    // when the origin evidence is actually derived (§4.3).
    let corroboration: CorroborationOutcome | undefined;
    if (options.corroborate || options.corroborateUrl) {
      const { origin, evidence } = deriveOrigin(options.manifestPath, options.origin);
      if (evidence === "derived") {
        corroboration = await corroborateOrigin(
          readFileSync(options.manifestPath), origin, options.corroborateUrl
        );
      }
    }
    // Composition resolution also happens here, before the deterministic
    // core: fetch/read and integrity-check each include (§3.11, RFC-0022 C17).
    let resolvedIncludes: ResolvedInclude[] | undefined;
    const parsed = (yaml.load(readFileSync(options.manifestPath, "utf8")) ?? {}) as RawMap;
    if (parsed.composition) {
      resolvedIncludes = await resolveComposition(
        parsed, dirname(resolve(options.manifestPath)), loadAllowlist(keysPath)
      );
    }
    result = renderManifest({
      manifestPath: options.manifestPath,
      keysPath,
      origin: options.origin,
      timestamp: options.timestamp,
      allowDerivedOrigin: options.allowDerivedOrigin,
      requireUnitHashes: options.requireUnitHashes,
      corroboration,
      resolvedIncludes,
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

  for (const w of result.warnings) {
    process.stderr.write(`${dim("⚠")} ${w}\n`);
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
