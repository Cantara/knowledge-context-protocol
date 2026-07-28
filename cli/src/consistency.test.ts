// Single-source consistency guards for facts that are duplicated across
// languages and would otherwise drift silently (the v0.16 QA pass found
// the Java/Python version lists two releases stale, and the CLI/bridge
// validators byte-identical only by discipline). These tests run from the
// cli/ package (cwd = cli) and read sibling files directly.

import { describe, expect, it } from "vitest";
import { lstatSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { SCAFFOLD_KCP_VERSION } from "./init.js";

const REPO = resolve(process.cwd(), "..");
const r = (p: string) => resolve(REPO, p);

const VALIDATORS = {
  cli: "shared/src/validator.ts",
  bridge: "shared/src/validator.ts",
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

describe("shared-core single-source-of-truth guard", () => {
  // model.ts, parser.ts, validator.ts live in shared/src/ and are symlinked
  // into cli/src/ and bridge/typescript/src/. readFileSync follows symlinks,
  // so reading the symlink paths yields the shared content — any divergence
  // (e.g. someone replaces a symlink with a copy) is caught here.
  for (const name of ["model", "parser", "validator"] as const) {
    it(`cli, bridge, and shared agree on ${name}.ts`, () => {
      const shared = readFileSync(r(`shared/src/${name}.ts`), "utf8");
      const cli = readFileSync(r(`cli/src/${name}.ts`), "utf8");
      const bridge = readFileSync(r(`bridge/typescript/src/${name}.ts`), "utf8");
      expect(shared.length).toBeGreaterThan(100);
      expect(cli, `cli/src/${name}.ts diverged from shared/src/${name}.ts`).toBe(shared);
      expect(bridge, `bridge/typescript/src/${name}.ts diverged from shared/src/${name}.ts`).toBe(shared);
    });
  }
});

describe("packaged schema copy", () => {
  // cli/schema/render-schema.json ships in the npm tarball (package.json
  // "files"). npm pack SKIPS symlinks, so this must be a regular file — the
  // 0.26.0 release shipped without it because it was a symlink, and `kcp
  // sign`/`kcp render` crashed for every npm user. Content equality with the
  // authoritative root schema is guarded so the copy cannot drift.
  it("cli/schema/render-schema.json is a regular file, not a symlink", () => {
    expect(lstatSync(r("cli/schema/render-schema.json")).isSymbolicLink()).toBe(false);
  });

  it("cli/schema/render-schema.json is byte-identical to schema/render-schema.json", () => {
    const root = readFileSync(r("schema/render-schema.json"), "utf8");
    const cli = readFileSync(r("cli/schema/render-schema.json"), "utf8");
    expect(cli).toBe(root);
  });
});

describe("init scaffold version", () => {
  it("SCAFFOLD_KCP_VERSION matches the current SPEC.md version", () => {
    const m = readFileSync(r("SPEC.md"), "utf8").match(/\*\*Version:\*\*\s*([\d.]+)/);
    expect(m, "SPEC.md version header not found").toBeTruthy();
    expect(SCAFFOLD_KCP_VERSION).toBe(m![1]);
  });
});

describe("render-schema.json guards", () => {
  // render-schema.json is the single authoritative source for the renderer
  // whitelist (RENDER_SCHEMA / RFC-0018 §6.1). These guards keep it coherent
  // with knowledge-schema.json so the two files cannot silently diverge.
  const rs = JSON.parse(readFileSync(r("schema/render-schema.json"), "utf8")) as {
    $id: string;
    top_scalars: string[];
    unit: { fields: string[]; free_text: string[] };
    content_structure: { fields: string[] };
    relationship: { fields: string[] };
    federation: { fields: string[] };
    provenance: { fields: string[] };
    agent_requirements: { fields: string[] };
  };
  const ks = JSON.parse(readFileSync(r("schema/knowledge-schema.json"), "utf8")) as {
    properties: Record<string, unknown>;
    definitions: Record<string, { properties?: Record<string, unknown> }>;
  };

  it("render-schema $id matches the RENDER_SCHEMA constant in render.ts", () => {
    const renderTs = readFileSync(r("cli/src/render.ts"), "utf8");
    const m = renderTs.match(/RENDER_SCHEMA\s*=\s*"([^"]+)"/);
    expect(m, "RENDER_SCHEMA constant not found in render.ts").toBeTruthy();
    expect(rs["$id"]).toBe(m![1]);
  });

  it("top_scalars are valid root-level properties in knowledge-schema.json", () => {
    const knownRoot = Object.keys(ks.properties);
    for (const f of rs.top_scalars) {
      expect(knownRoot, `top_scalar '${f}' absent from knowledge-schema root properties`).toContain(f);
    }
  });

  it("unit fields are a subset of knowledge-schema unit properties", () => {
    const knownUnit = Object.keys(ks.definitions.unit.properties ?? {});
    for (const f of rs.unit.fields) {
      expect(knownUnit, `unit field '${f}' absent from knowledge-schema unit definition`).toContain(f);
    }
  });

  it("unit free_text fields are a subset of unit fields", () => {
    const unitFieldSet = new Set(rs.unit.fields);
    for (const f of rs.unit.free_text) {
      expect(unitFieldSet.has(f), `free_text field '${f}' not in unit fields`).toBe(true);
    }
  });

  it("content_structure fields are a subset of knowledge-schema content_structure_object properties", () => {
    const knownCs = Object.keys(ks.definitions.content_structure_object.properties ?? {});
    for (const f of rs.content_structure.fields) {
      expect(knownCs, `content_structure field '${f}' absent from knowledge-schema`).toContain(f);
    }
  });

  it("relationship fields are a subset of knowledge-schema relationship properties", () => {
    const knownRel = Object.keys(ks.definitions.relationship.properties ?? {});
    for (const f of rs.relationship.fields) {
      expect(knownRel, `relationship field '${f}' absent from knowledge-schema`).toContain(f);
    }
  });

  it("federation fields are a subset of knowledge-schema manifest_ref properties", () => {
    const knownFed = Object.keys(ks.definitions.manifest_ref.properties ?? {});
    for (const f of rs.federation.fields) {
      expect(knownFed, `federation field '${f}' absent from knowledge-schema manifest_ref`).toContain(f);
    }
  });

  it("provenance fields are a subset of knowledge-schema trust_provenance properties", () => {
    const knownProv = Object.keys(ks.definitions.trust_provenance.properties ?? {});
    for (const f of rs.provenance.fields) {
      expect(knownProv, `provenance field '${f}' absent from knowledge-schema trust_provenance`).toContain(f);
    }
  });

  it("agent_requirements fields are a subset of knowledge-schema trust_agent_requirements properties", () => {
    const knownAr = Object.keys(ks.definitions.trust_agent_requirements.properties ?? {});
    for (const f of rs.agent_requirements.fields) {
      expect(knownAr, `agent_requirements field '${f}' absent from knowledge-schema trust_agent_requirements`).toContain(f);
    }
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

/**
 * The parser artifacts a bridge depends on (#163).
 *
 * These went unguarded and drifted furthest of anything in the repository: two sat at
 * `0.1.0` since the modules were created and one at `0.21.0`, while the spec reached
 * 0.30. The cost is not cosmetic — `bridge/java` depends on `kcp-parser:0.1.0`, which is
 * the only version that has ever existed, so a bridge could not express "I need a parser
 * that knows action_scope". It resolves whatever was last installed locally, which is how
 * correct mapper code came to fail with `cannot find symbol: actionScope()`.
 */
describe("parser artifact versions track the spec (#163)", () => {
  const specMinor = (() => {
    const m = readFileSync(r("SPEC.md"), "utf8").match(/\*\*Version:\*\*\s*([\d.]+)/);
    return m![1];
  })();

  const artifacts: Array<[string, string, RegExp]> = [
    ["parsers/java", "parsers/java/pom.xml", /<version>([\d.]+)<\/version>/],
    ["parsers/python", "parsers/python/pyproject.toml", /^version\s*=\s*"([\d.]+)"/m],
    ["shared", "shared/package.json", /"version":\s*"([\d.]+)"/],
  ];

  for (const [name, file, pattern] of artifacts) {
    it(`${name} is on the current spec minor`, () => {
      const m = readFileSync(r(file), "utf8").match(pattern);
      expect(m, `no version found in ${file}`).toBeTruthy();
      // Compared on the minor only: the artifact may carry a patch the spec does not.
      const minor = m![1].split(".").slice(0, 2).join(".");
      expect(minor, `${file} is ${m![1]}, spec is ${specMinor}`).toBe(specMinor);
    });
  }

  it("the Java bridge requires a parser that can satisfy it", () => {
    // The dependency must name a version that actually knows the fields the mapper
    // reads. Pinning 0.1.0 forever means the requirement is unstatable.
    const pom = readFileSync(r("bridge/java/pom.xml"), "utf8");
    const dep = pom.match(/<artifactId>kcp-parser<\/artifactId>\s*<version>([\d.]+)<\/version>/);
    expect(dep, "kcp-parser dependency not found in bridge/java/pom.xml").toBeTruthy();
    expect(dep![1].split(".").slice(0, 2).join(".")).toBe(specMinor);
  });

  it("the Python bridge requires a parser that can satisfy it", () => {
    const toml = readFileSync(r("bridge/python/pyproject.toml"), "utf8");
    const dep = toml.match(/"kcp>=([\d.]+)"/);
    expect(dep, "kcp dependency not found in bridge/python/pyproject.toml").toBeTruthy();
    expect(dep![1].split(".").slice(0, 2).join(".")).toBe(specMinor);
  });
});
