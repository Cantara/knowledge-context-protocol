// Tests for kcp sign (RFC-0018 §4.2 detached profile, RFC-0019 §3.1 hash refresh)

import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { createHash, generateKeyPairSync } from "node:crypto";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import yaml from "js-yaml";

import { refreshContentHashes, signManifestFile } from "./sign.js";
import { renderManifest } from "./render.js";
import { parseFile } from "./parser.js";
import { validate } from "./validator.js";

let dir: string;

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), "kcp-sign-test-"));
});

afterEach(() => {
  rmSync(dir, { recursive: true, force: true });
});

function writeKey(): string {
  const { privateKey } = generateKeyPairSync("ed25519");
  const keyPath = join(dir, "signing.pem");
  writeFileSync(keyPath, privateKey.export({ type: "pkcs8", format: "pem" }));
  return keyPath;
}

const HASHED_MANIFEST = `# top-level comment that must survive
project: test
version: 1.0.0
units:
  - id: setup # inline comment that must survive
    path: docs/setup.md
    intent: "Development environment description"
    content_hash:
      algorithm: sha256
      value: "stale-or-placeholder"
`;

describe("kcp sign", () => {
  it("signs a manifest that kcp render then verifies end-to-end at trusted tier", () => {
    mkdirSync(join(dir, "docs"));
    writeFileSync(join(dir, "docs", "setup.md"), "# Setup\n");
    const manifestPath = join(dir, "knowledge.yaml");
    writeFileSync(manifestPath, HASHED_MANIFEST);

    const result = signManifestFile({
      manifestPath, keyPath: writeKey(), keyId: "test-org-2026", updateHashes: true,
    });
    expect(result.updatedUnits).toEqual(["setup"]);

    const keysPath = join(dir, "trusted-keys.yaml");
    writeFileSync(keysPath, yaml.dump({
      version: 1,
      keys: [{ key_id: "test-org-2026", method: "jws", algorithm: "EdDSA",
        public_key: result.publicKey }],
    }));

    const render = renderManifest({
      manifestPath, keysPath, origin: "github.com/testorg/repo",
    });
    expect(render.ok).toBe(true);
    if (!render.ok) return;
    const doc = yaml.load(render.text) as Record<string, any>;
    expect(doc.trust.tier).toBe("trusted");
    expect(doc.trust.signature.key_id).toBe("test-org-2026");
    expect(doc.units[0].content_verified).toBe(true);
    expect(doc.units[0].load_eligible).toBe(true);
  });

  it("refreshes hashes in place while preserving comments and formatting", () => {
    mkdirSync(join(dir, "docs"));
    writeFileSync(join(dir, "docs", "setup.md"), "content v1\n");
    const manifestPath = join(dir, "knowledge.yaml");
    writeFileSync(manifestPath, HASHED_MANIFEST);

    expect(refreshContentHashes(manifestPath)).toEqual(["setup"]);
    const text = readFileSync(manifestPath, "utf8");
    expect(text).toContain("# top-level comment that must survive");
    expect(text).toContain("# inline comment that must survive");
    const digest = createHash("sha256").update("content v1\n").digest("hex");
    expect(text).toContain(digest);
    expect(text).not.toContain("stale-or-placeholder");

    // idempotent: a second refresh changes nothing
    expect(refreshContentHashes(manifestPath)).toEqual([]);
  });

  it("refuses to sign over a malformed content_hash declaration", () => {
    const manifestPath = join(dir, "knowledge.yaml");
    writeFileSync(manifestPath, HASHED_MANIFEST.replace("sha256", "md5"));
    expect(() =>
      signManifestFile({
        manifestPath, keyPath: writeKey(), keyId: "k", updateHashes: true,
      })
    ).toThrow(/algorithm 'md5'/);
  });

  it("refuses non-Ed25519 keys (MTI profile, RFC-0018 §4.2)", () => {
    const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
    const keyPath = join(dir, "rsa.pem");
    writeFileSync(keyPath, privateKey.export({ type: "pkcs8", format: "pem" }));
    const manifestPath = join(dir, "knowledge.yaml");
    writeFileSync(manifestPath, "project: test\nversion: 1.0.0\nunits: []\n");
    expect(() =>
      signManifestFile({ manifestPath, keyPath, keyId: "k" })
    ).toThrow(/Ed25519/);
  });
});

describe("kcp validate: content_hash (RFC-0019 §3.1)", () => {
  it("recomputes and errors on a stale hash, passes a fresh one", () => {
    mkdirSync(join(dir, "docs"));
    writeFileSync(join(dir, "docs", "setup.md"), "content\n");
    const digest = createHash("sha256").update("content\n").digest("hex");
    const manifestPath = join(dir, "knowledge.yaml");
    writeFileSync(manifestPath, `project: test
version: 1.0.0
units:
  - id: setup
    path: docs/setup.md
    intent: "Docs"
    scope: project
    audience: [agent]
    content_hash:
      algorithm: sha256
      value: "${digest}"
`);
    const fresh = validate(parseFile(manifestPath), dir);
    expect(fresh.errors.filter((e) => e.includes("content_hash"))).toEqual([]);

    writeFileSync(join(dir, "docs", "setup.md"), "edited\n");
    const stale = validate(parseFile(manifestPath), dir);
    expect(stale.errors.some((e) => e.includes("does not match content on disk"))).toBe(true);
  });

  it("errors on bad algorithm and non-hex value", () => {
    const manifestPath = join(dir, "knowledge.yaml");
    writeFileSync(manifestPath, `project: test
version: 1.0.0
units:
  - id: a
    path: docs/a.md
    intent: "Docs"
    scope: project
    audience: [agent]
    content_hash:
      algorithm: md5
      value: "abc"
  - id: b
    path: docs/b.md
    intent: "Docs"
    scope: project
    audience: [agent]
    content_hash:
      algorithm: sha256
      value: "not hex!"
`);
    const result = validate(parseFile(manifestPath), dir);
    expect(result.errors.some((e) => e.includes("content_hash.algorithm"))).toBe(true);
    expect(result.errors.some((e) => e.includes("hex digest"))).toBe(true);
  });
});
