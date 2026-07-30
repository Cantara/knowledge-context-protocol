import { describe, it, expect } from "vitest";
import { parseDict } from "../../shared/src/parser.js";
import { validate, deniesToken } from "../../shared/src/validator.js";

/**
 * §4.3a skill negative scope + own authority ceiling (SPEC PROPOSAL, design review).
 *
 * Two capabilities a downstream KCP consumer needs from a `kind: skill` unit:
 *
 *  1. The skill carries its OWN `authority_level` — its capability ceiling — so it
 *     participates as a `grant_ceiling` source (§3.13) in the multi-source MIN.
 *     (`authority_level` on a unit already parses and is scale-checked; these tests
 *     pin that it holds for a skill and feeds a grant_ceiling `unit_ref`.)
 *
 *  2. `action_scope.deny` — an explicit negative scope with the same
 *     {tools, paths, capabilities} shape as the allowlist. A deny entry is DENIED
 *     even when the allowlist would grant it: deny overrides allow, fail-closed.
 */

function manifest(units: unknown[], extra: Record<string, unknown> = {}) {
  return parseDict({
    project: "example",
    version: "1.0.0",
    kcp_version: "0.30",
    authority_level_scale: ["observe", "explain", "suggest", "prepare", "commit"],
    units,
    ...extra,
  });
}

const BASE_SKILL = {
  id: "rotate-signing-key",
  kind: "skill",
  path: "skills/rotate.md",
  intent: "How do I rotate the signing key safely?",
  scope: "project",
  audience: ["agent"],
  load_eligible: true,
};

describe("§4.3a — action_scope.deny negative scope", () => {
  it("round-trips deny alongside the allowlist", () => {
    const m = manifest([
      {
        ...BASE_SKILL,
        action_scope: {
          tools: ["kcp-sign", "git"],
          paths: ["schema/**"],
          capabilities: ["key-management"],
          deny: {
            tools: ["shell"],
            paths: ["schema/secrets/**"],
            capabilities: ["network"],
          },
        },
      },
    ]);
    const scope = m.units[0]!.action_scope!;
    expect(scope.tools).toEqual(["kcp-sign", "git"]);
    expect(scope.deny).toBeDefined();
    expect(scope.deny!.tools).toEqual(["shell"]);
    expect(scope.deny!.paths).toEqual(["schema/secrets/**"]);
    expect(scope.deny!.capabilities).toEqual(["network"]);
    // a well-formed deny + allow validates clean
    const r = validate(m);
    expect(r.isValid).toBe(true);
  });

  it("deniesToken adjudicates fail-closed: deny denies even when allow grants", () => {
    const m = manifest([
      {
        ...BASE_SKILL,
        action_scope: { tools: ["git", "shell"], deny: { tools: ["shell"] } },
      },
    ]);
    const scope = m.units[0]!.action_scope;
    expect(deniesToken(scope, "tools", "shell")).toBe(true);
    expect(deniesToken(scope, "tools", "git")).toBe(false);
  });

  it("catches an over-broad allow entry that a deny denies (deny overrides allow)", () => {
    const m = manifest([
      {
        ...BASE_SKILL,
        // 'shell' is both granted and forbidden — the allow is dead, deny wins.
        action_scope: { tools: ["git", "shell"], deny: { tools: ["shell"] } },
      },
    ]);
    const r = validate(m);
    const hit = r.warnings.find(
      (w) => /deny/.test(w) && /shell/.test(w) && /§4\.3a/.test(w)
    );
    expect(hit, `expected a deny-overrides-allow warning, got: ${r.warnings.join(" | ")}`).toBeDefined();
  });

  it("warns on an empty deny — it prohibits nothing (shape lint)", () => {
    const m = manifest([
      {
        ...BASE_SKILL,
        action_scope: { tools: ["git"], deny: {} },
      },
    ]);
    const r = validate(m);
    const hit = r.warnings.find((w) => /deny/.test(w) && /prohibits nothing/.test(w));
    expect(hit, `expected an empty-deny warning, got: ${r.warnings.join(" | ")}`).toBeDefined();
  });
});

describe("§3.13/§4.3a — a skill's own authority_level is a grant_ceiling source", () => {
  it("round-trips authority_level on a skill and scale-checks it", () => {
    const m = manifest([
      {
        ...BASE_SKILL,
        authority_level: "prepare",
        action_scope: { tools: ["kcp-sign"] },
      },
    ]);
    expect(m.units[0]!.authority_level).toBe("prepare");
    const r = validate(m);
    expect(r.isValid).toBe(true);
    // a value off the declared scale warns
    const m2 = manifest([
      { ...BASE_SKILL, authority_level: "yolo", action_scope: { tools: ["kcp-sign"] } },
    ]);
    const r2 = validate(m2);
    expect(
      r2.warnings.some((w) => /authority_level/.test(w) && /yolo/.test(w))
    ).toBe(true);
  });

  it("resolves the skill's authority_level through a grant_ceiling unit_ref", () => {
    const m = manifest(
      [{ ...BASE_SKILL, authority_level: "suggest", action_scope: { tools: ["kcp-sign"] } }],
      {
        grant_ceiling: {
          sources: [
            { id: "org-policy", authority_level: "prepare" },
            { id: "skill-ceiling", unit_ref: "rotate-signing-key" },
          ],
        },
      }
    );
    const r = validate(m);
    // unit_ref resolves to a declared, in-scale level — no unknown-ref error
    expect(r.errors.find((e) => /unit_ref/.test(e))).toBeUndefined();
    expect(r.isValid).toBe(true);
  });
});
