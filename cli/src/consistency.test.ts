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

// Version-drift guard. The shipped version string lives in seven places across
// four languages (renderer banner, CLI help banner, cli/package.json, and the
// three bridge package descriptors) plus the SPEC.md minor. Nothing forces them
// to agree, and the v0.16 pass found three of them stale. These assertions pin
// every runtime version source to the cli/package.json version and pin that
// package's minor to SPEC.md — so a release bump that misses a file fails CI.
describe("version-drift guard", () => {
  const specMinor = (() => {
    const m = readFileSync(r("SPEC.md"), "utf8").match(/\*\*Version:\*\*\s*(\d+\.\d+)/);
    if (!m) throw new Error("SPEC.md version header not found");
    return m[1];
  })();

  const cliPkgVersion = (() => {
    const pkg = JSON.parse(readFileSync(r("cli/package.json"), "utf8"));
    return pkg.version as string;
  })();

  it("cli/package.json version matches the SPEC.md minor", () => {
    expect(cliPkgVersion.startsWith(specMinor + "."), `cli ${cliPkgVersion} vs SPEC ${specMinor}`).toBe(true);
  });

  it("RENDERER_VERSION matches the cli package version", () => {
    const text = readFileSync(r("cli/src/render.ts"), "utf8");
    const m = text.match(/RENDERER_VERSION\s*=\s*"kcp-cli\s+([\d.]+)"/);
    expect(m, "RENDERER_VERSION literal not found").toBeTruthy();
    expect(m![1]).toBe(cliPkgVersion);
  });

  it("the CLI help banner matches the cli package version", () => {
    const text = readFileSync(r("cli/src/cli.ts"), "utf8");
    const m = text.match(/KCP Developer CLI — v([\d.]+)/);
    expect(m, "CLI banner version not found").toBeTruthy();
    expect(m![1]).toBe(cliPkgVersion);
  });

  it("every bridge package shares the cli minor version", () => {
    const ts = JSON.parse(readFileSync(r("bridge/typescript/package.json"), "utf8")).version as string;

    const pyText = readFileSync(r("bridge/python/pyproject.toml"), "utf8");
    const py = pyText.match(/^version\s*=\s*"([\d.]+)"/m)?.[1];

    const pomText = readFileSync(r("bridge/java/pom.xml"), "utf8");
    // Project version only — slice before the dependency block so a dependency's
    // <version> can't be mistaken for the artifact version.
    const projectPom = pomText.slice(0, pomText.indexOf("<dependencies"));
    const java = projectPom.match(/<version>([\d.]+)<\/version>/)?.[1];

    const minor = cliPkgVersion.split(".").slice(0, 2).join(".") + ".";
    for (const [name, v] of [["typescript", ts], ["python", py], ["java", java]] as const) {
      expect(v, `bridge/${name} version not found`).toBeTruthy();
      expect(v!.startsWith(minor), `bridge/${name} ${v} vs cli ${cliPkgVersion}`).toBe(true);
    }
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

// Security/correctness invariants that were violated by the v0.19/v0.20
// composition + temporal promotion and corrected in v0.21 (RFC-0022). These
// guard the *spec text* so the insecure/incorrect phrasing cannot silently
// return — each assertion would have FAILED on the pre-RFC-0022 spec.
describe("composition + temporal spec invariants (RFC-0022)", () => {
  const spec = readFileSync(r("SPEC.md"), "utf8");

  it("§3.11 makes composition include integrity enforcing, not advisory", () => {
    // The T10 hole: a trusted composing signature must not launder
    // unauthenticated included content into standing context.
    expect(spec).toContain("Include integrity is enforcing at `trusted` tier");
    // and the old advisory-only disposition must be gone
    expect(spec).not.toContain(
      "advisory warnings, not hard failures. A mismatch\n  produces a §7 warning but does not lower the composed result's trust tier"
    );
  });

  it("§16.5 defines C17 (the enforcing renderer rule)", () => {
    expect(spec).toContain("**C17**");
    expect(spec).toContain(
      "MUST NOT emit `load_eligible: true` for any unit originating from an `unverified` or `failed`"
    );
  });

  it("composition integrity uses the {algorithm, value} hash shape, not a colon string", () => {
    // unifies with RFC-0004 content_integrity.manifest_hash and RFC-0019 content_hash
    expect(spec).not.toContain('manifest_hash: "sha256:');
  });

  it("§4.22 does not warn on recorded_at later than valid_from (RFC-0010 core case)", () => {
    // the removed false-positive bullet — present tense as an active warning
    expect(spec).not.toContain("is later than `valid_from` (manifest authored after the fact");
  });

  it("§4.22 warns on an empty validity window (valid_until before valid_from)", () => {
    expect(spec).toContain("`valid_until` is earlier than `valid_from`");
  });
});
