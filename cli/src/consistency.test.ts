// Single-source consistency guards for facts that are duplicated across
// languages and would otherwise drift silently (the v0.16 QA pass found
// the Java/Python version lists two releases stale, and the CLI/bridge
// validators byte-identical only by discipline). These tests run from the
// cli/ package (cwd = cli) and read sibling files directly.

import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const REPO = resolve(process.cwd(), "..");
const r = (p: string) => resolve(REPO, p);

const VALIDATORS = {
  cli: "cli/src/validator.ts",
  bridge: "bridge/typescript/src/validator.ts",
  python: "parsers/python/kcp/validator.py",
  java: "parsers/java/src/main/java/no/cantara/kcp/KcpValidator.java",
};

/** Extract the version tokens from a validator's KNOWN_KCP_VERSIONS literal. */
function versionSet(file: string): Set<string> {
  const text = readFileSync(r(file), "utf8");
  const start = text.indexOf("KNOWN_KCP_VERSIONS");
  expect(start, `KNOWN_KCP_VERSIONS not found in ${file}`).toBeGreaterThan(-1);
  const close = text.slice(start).search(/[)\]}]/);
  const block = text.slice(start, start + close);
  return new Set(block.match(/\d+\.\d+/g) ?? []);
}

describe("cross-language version-list consistency", () => {
  const specVersion = (() => {
    const m = readFileSync(r("SPEC.md"), "utf8").match(/\*\*Version:\*\*\s*([\d.]+)/);
    if (!m) throw new Error("SPEC.md version header not found");
    return m[1];
  })();

  it("every validator knows the current SPEC.md version", () => {
    for (const [name, file] of Object.entries(VALIDATORS)) {
      expect(versionSet(file).has(specVersion), `${name} missing ${specVersion}`).toBe(true);
    }
  });

  it("all four validators agree on the known-version set", () => {
    const sets = Object.fromEntries(
      Object.entries(VALIDATORS).map(([n, f]) => [n, [...versionSet(f)].sort()])
    );
    for (const name of Object.keys(sets)) {
      expect(sets[name], `${name} diverges from cli`).toEqual(sets.cli);
    }
  });

  it("the JSON schema kcp_version enum includes the current version", () => {
    const schema = readFileSync(r("schema/knowledge-schema.json"), "utf8");
    const block = schema.slice(schema.indexOf('"kcp_version"'));
    const enumTokens = new Set(
      (block.slice(0, block.indexOf("]")).match(/\d+\.\d+/g)) ?? []
    );
    expect(enumTokens.has(specVersion), `schema enum missing ${specVersion}`).toBe(true);
  });
});

describe("validator duplication guard", () => {
  it("cli and bridge TypeScript validators are byte-identical", () => {
    // They are copy-paste twins with no shared module; until that is fixed,
    // assert they never diverge (a one-sided edit would fork validation).
    const a = readFileSync(r(VALIDATORS.cli), "utf8");
    const b = readFileSync(r(VALIDATORS.bridge), "utf8");
    expect(a).toBe(b);
  });
});
