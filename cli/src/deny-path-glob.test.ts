import { describe, it, expect } from "vitest";
import { parseDict } from "../../shared/src/parser.js";
import { validate, deniesToken, effectiveDeniesToken, pathGlobMatches } from "../../shared/src/validator.js";

/**
 * §4.3a (v0.32.1) — deny.paths entries are PATTERNS, matched structurally.
 *
 * Exact-string comparison never fires the `schema/secrets/**` carve-out the spec
 * promises: no requested path is ever the literal string `schema/secrets/**`. These
 * tests pin the glob semantics (`**` crosses segments, `*` stays within one) for
 * deniesToken, for the union (effectiveDeniesToken), and for the §4.3b
 * self-nullified-step lint — and pin that tools/capabilities stay exact-match.
 */

describe("pathGlobMatches", () => {
  it("** crosses segment boundaries", () => {
    expect(pathGlobMatches("legal/hold/**", "legal/hold/2025/case.pdf")).toBe(true);
    expect(pathGlobMatches("legal/hold/**", "legal/hold/x")).toBe(true);
    expect(pathGlobMatches("legal/hold/**", "legal/holdings/x")).toBe(false);
  });
  it("* stays within a single segment", () => {
    expect(pathGlobMatches("customers/*/pii", "customers/acme/pii")).toBe(true);
    expect(pathGlobMatches("customers/*/pii", "customers/a/b/pii")).toBe(false);
  });
  it("literal characters are escaped, not regex", () => {
    expect(pathGlobMatches("a.b/c", "a.b/c")).toBe(true);
    expect(pathGlobMatches("a.b/c", "axb/c")).toBe(false);
  });
});

describe("§4.3a — deny.paths matches structurally", () => {
  const scope = {
    paths: ["schema/**"],
    deny: { paths: ["schema/secrets/**", "legal/hold/**"], tools: ["delete"] },
  };

  it("a deny glob denies every path beneath it", () => {
    expect(deniesToken(scope, "paths", "legal/hold/2025/case.pdf")).toBe(true);
    expect(deniesToken(scope, "paths", "schema/secrets/key.pem")).toBe(true);
  });

  it("the carve-out fires: allowed region, prohibited hole", () => {
    expect(deniesToken(scope, "paths", "schema/api.json")).toBe(false);
    expect(deniesToken(scope, "paths", "schema/secrets/nested/key.pem")).toBe(true);
  });

  it("tools and capabilities remain exact tokens", () => {
    expect(deniesToken(scope, "tools", "delete")).toBe(true);
    expect(deniesToken(scope, "tools", "delete_all")).toBe(false);
  });

  it("the union (§4.3b) inherits glob matching from either source", () => {
    const pbScope = { deny: { paths: ["legal/hold/**"] } };
    const skillScope = { paths: ["customers/**"], deny: {} };
    expect(effectiveDeniesToken([pbScope, skillScope], "paths", "legal/hold/2025/x")).toBe(true);
    expect(effectiveDeniesToken([pbScope, skillScope], "paths", "customers/acme/x")).toBe(false);
  });
});

describe("§4.3b — self-nullified lint sees glob containment", () => {
  function manifest(skillPaths: string[], pbDenyPaths: string[]) {
    return parseDict({
      project: "example",
      version: "1.0.0",
      kcp_version: "0.32",
      authority_level_scale: ["observe", "explain", "suggest", "prepare", "commit"],
      units: [
        {
          id: "sletteagent", kind: "skill", path: "skills/s.md",
          intent: "How do I delete compliantly?", scope: "project", audience: ["agent"],
          load_eligible: true,
          action_scope: { tools: ["read"], paths: skillPaths },
        },
        {
          id: "pb", kind: "playbook", path: "playbooks/p.md",
          intent: "How is deletion executed?", scope: "project", audience: ["agent"],
          load_eligible: true, authority_level: "commit",
          action_scope: { deny: { paths: pbDenyPaths } },
          steps: [{ id: "slett", uses: "sletteagent", authority_level: "commit" }],
        },
      ],
    });
  }

  it("warns when a broader deny glob covers every allowed path", () => {
    const result = validate(manifest(["legal/hold/2025/**"], ["legal/hold/**"]));
    expect(result.warnings.some((w) => w.includes("self-nullified") && w.includes("'paths'"))).toBe(true);
  });

  it("does not warn when the deny only carves a hole", () => {
    const result = validate(manifest(["customers/**"], ["customers/pii/**"]));
    expect(result.warnings.some((w) => w.includes("self-nullified"))).toBe(false);
  });
});
