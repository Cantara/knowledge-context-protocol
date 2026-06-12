// kcp sign — producer-side signing (RFC-0018 §4.2 detached profile)
// with per-unit content-hash refresh (RFC-0019 §3.1).
//
// Writes the same detached envelope `kcp render` verifies:
// { key_id, algorithm: "EdDSA", public_key: <base64 DER SPKI>,
//   signature: <base64> } over the exact manifest bytes.
//
// --update-hashes recomputes every declared content_hash.value before
// signing, so the signature covers the digests of the content as it is
// now. The manifest is edited via a CST round-trip that preserves
// comments and formatting; only changed values are rewritten.
//
// Note: the repo's CI workflow (sign-manifests.yml) writes raw-base64
// signatures, a different detached profile predating this command.

import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { createPrivateKey, createPublicKey, sign as cryptoSign } from "node:crypto";
import { dirname, resolve } from "node:path";
import { isMap, isSeq, parseDocument } from "yaml";
import { computeContentDigest, HASH_ALGORITHMS } from "./render.js";

const green = (s: string) => `\x1b[32m${s}\x1b[0m`;
const red = (s: string) => `\x1b[31m${s}\x1b[0m`;
const dim = (s: string) => `\x1b[2m${s}\x1b[0m`;

export interface SignOptions {
  manifestPath: string;
  /** Path to an Ed25519 private key, PEM (PKCS#8). */
  keyPath: string;
  /** Allowlist join key (RFC-0018 §9 key_id). */
  keyId: string;
  /** Recompute declared content_hash values before signing (RFC-0019 §3.1). */
  updateHashes?: boolean;
  /** Signature output path. Default: `<manifest>.sig`. */
  out?: string;
}

export interface SignResult {
  sigPath: string;
  keyId: string;
  publicKey: string; // base64 DER SPKI, ready for an allowlist entry
  /** Unit ids whose content_hash.value was refreshed. */
  updatedUnits: string[];
}

/**
 * Refresh every declared content_hash.value in place, preserving the
 * manifest's comments and formatting. Returns the updated unit ids.
 * Throws on malformed declarations — a producer must fix those, not
 * sign over them.
 */
export function refreshContentHashes(manifestPath: string): string[] {
  const text = readFileSync(manifestPath, "utf8");
  const doc = parseDocument(text);
  const units = doc.get("units");
  if (!isSeq(units)) return [];
  const manifestDir = dirname(resolve(manifestPath));
  const updated: string[] = [];
  units.items.forEach((unit, i) => {
    if (!isMap(unit) || !unit.has("content_hash")) return;
    const id = String(unit.get("id") ?? `units[${i}]`);
    const ch = unit.get("content_hash");
    if (!isMap(ch)) {
      throw new Error(`unit '${id}': content_hash must be a map with algorithm and value`);
    }
    const algorithm = String(ch.get("algorithm") ?? "");
    if (!HASH_ALGORITHMS.includes(algorithm)) {
      throw new Error(
        `unit '${id}': content_hash.algorithm '${algorithm}' is not one of ${HASH_ALGORITHMS.join(", ")}`
      );
    }
    const unitPath = unit.get("path");
    if (typeof unitPath !== "string" || unitPath.length === 0) {
      throw new Error(`unit '${id}': content_hash declared but the unit has no path`);
    }
    const digest = computeContentDigest(resolve(manifestDir, unitPath), algorithm);
    if (digest === undefined) {
      throw new Error(`unit '${id}': cannot hash '${unitPath}' (missing or unreadable)`);
    }
    if (ch.get("value") !== digest) {
      ch.set("value", digest);
      updated.push(id);
    }
  });
  if (updated.length > 0) writeFileSync(manifestPath, doc.toString());
  return updated;
}

export function signManifestFile(options: SignOptions): SignResult {
  const updatedUnits = options.updateHashes
    ? refreshContentHashes(options.manifestPath)
    : [];

  const key = createPrivateKey(readFileSync(options.keyPath));
  if (key.asymmetricKeyType !== "ed25519") {
    throw new Error(
      `signing key is ${key.asymmetricKeyType}; the mandatory-to-implement algorithm is Ed25519 (RFC-0018 §4.2)`
    );
  }
  const manifestBytes = readFileSync(options.manifestPath);
  const spki = createPublicKey(key)
    .export({ type: "spki", format: "der" })
    .toString("base64");
  const signature = cryptoSign(null, manifestBytes, key).toString("base64");

  const sigPath = options.out ?? options.manifestPath + ".sig";
  writeFileSync(
    sigPath,
    JSON.stringify(
      { key_id: options.keyId, algorithm: "EdDSA", public_key: spki, signature },
      null,
      2
    ) + "\n"
  );
  return { sigPath, keyId: options.keyId, publicKey: spki, updatedUnits };
}

export function runSign(options: SignOptions): void {
  if (!existsSync(options.manifestPath)) {
    process.stderr.write(red(`Error: manifest not found: ${options.manifestPath}\n`));
    process.exit(1);
  }
  let result: SignResult;
  try {
    result = signManifestFile(options);
  } catch (err) {
    process.stderr.write(red(`Error: ${err instanceof Error ? err.message : String(err)}\n`));
    process.exit(1);
  }
  for (const id of result.updatedUnits) {
    process.stdout.write(`${dim("↻")} content_hash refreshed: ${id}\n`);
  }
  process.stdout.write(`${green(`✓ signed`)} ${dim(`→ ${result.sigPath} (key_id: ${result.keyId})`)}\n`);
  process.stdout.write(
    dim(`  allowlist public_key: ${result.publicKey}\n`)
  );
}
