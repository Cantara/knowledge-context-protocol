import { describe, it, expect } from "vitest";
import { parseDict } from "../../shared/src/parser.js";
import { validate, deniesToken, effectiveDeniesToken } from "../../shared/src/validator.js";

/**
 * §4.3b playbook-level prohibitions (SPEC PROPOSAL v0.32, RFC-0030, design review).
 *
 * A `kind: playbook` unit's `action_scope.deny` is a blanket prohibition over EVERY
 * step, inline steps included — the one normative sub-object of the otherwise
 * declarative playbook `action_scope` envelope. The effective denylist for a step is
 * the UNION of the playbook's deny and the used skill's deny: a match in either
 * denies, overriding any allow, fail-closed. Union is the only sound composition —
 * adding a source can only refuse more (the scope-axis mirror of the §3.13 lowest-of).
 */

function manifest(units: unknown[], extra: Record<string, unknown> = {}) {
  return parseDict({
    project: "example",
    version: "1.0.0",
    kcp_version: "0.31",
    authority_level_scale: ["observe", "explain", "suggest", "prepare", "commit"],
    units,
    ...extra,
  });
}

const SLETTEAGENT = {
  id: "sletteagent",
  kind: "skill",
  path: "skills/sletteagent.md",
  intent: "How do I delete customer data compliantly?",
  scope: "project",
  audience: ["agent"],
  load_eligible: true,
  action_scope: {
    tools: ["delete", "read"],
    paths: ["customers/**", "legal/hold/2025/**"],
    deny: { tools: ["transfer_ownership"] },
  },
};

const BASE_PLAYBOOK = {
  id: "pb-002-gdpr-sletting",
  kind: "playbook",
  path: "playbooks/gdpr-sletting.md",
  intent: "How is a GDPR Art.17 deletion request executed?",
  scope: "project",
  audience: ["agent"],
  load_eligible: true,
  authority_level: "commit",
};

describe("§4.3b — playbook action_scope.deny (RFC-0030)", () => {
  it("round-trips deny on a kind: playbook unit", () => {
    const m = manifest([
      SLETTEAGENT,
      {
        ...BASE_PLAYBOOK,
        action_scope: {
          deny: { paths: ["legal/hold/**"], tools: ["transfer_ownership"] },
        },
        steps: [{ id: "slett", uses: "sletteagent", authority_level: "commit" }],
      },
    ]);
    const pb = m.units.find((u) => u.id === "pb-002-gdpr-sletting")!;
    expect(pb.action_scope?.deny?.paths).toEqual(["legal/hold/**"]);
    expect(pb.action_scope?.deny?.tools).toEqual(["transfer_ownership"]);
  });

  it("effective deny is the union: playbook-only, skill-only, both, neither", () => {
    const playbookScope = { deny: { paths: ["legal/hold/**"], tools: ["set_billing"] } };
    const skillScope = SLETTEAGENT.action_scope;

    // playbook-only match
    expect(effectiveDeniesToken([playbookScope, skillScope], "paths", "legal/hold/**")).toBe(true);
    expect(deniesToken(skillScope, "paths", "legal/hold/**")).toBe(false);

    // skill-only match
    expect(effectiveDeniesToken([playbookScope, skillScope], "tools", "transfer_ownership")).toBe(true);
    expect(deniesToken(playbookScope, "tools", "transfer_ownership")).toBe(false);

    // both match — still denied (sources compose, never cancel)
    const both = { deny: { tools: ["transfer_ownership"] } };
    expect(effectiveDeniesToken([both, skillScope], "tools", "transfer_ownership")).toBe(true);

    // neither — allowed tokens pass through
    expect(effectiveDeniesToken([playbookScope, skillScope], "tools", "read")).toBe(false);
  });

  it("adding a deny source never un-denies (monotonicity)", () => {
    const skillScope = SLETTEAGENT.action_scope;
    expect(deniesToken(skillScope, "tools", "transfer_ownership")).toBe(true);
    // a playbook that denies nothing on this dimension cannot relax the skill's deny
    expect(
      effectiveDeniesToken([{ deny: { paths: ["x/**"] } }, skillScope], "tools", "transfer_ownership")
    ).toBe(true);
    expect(effectiveDeniesToken([undefined, skillScope], "tools", "transfer_ownership")).toBe(true);
  });

  it("warns when a step is self-nullified by the effective deny", () => {
    const m = manifest([
      SLETTEAGENT,
      {
        ...BASE_PLAYBOOK,
        action_scope: { deny: { tools: ["delete", "read"] } }, // everything the skill allows
        steps: [{ id: "slett", uses: "sletteagent", authority_level: "commit" }],
      },
    ]);
    const result = validate(m);
    expect(
      result.warnings.some((w) => w.includes("self-nullified") && w.includes("'tools'"))
    ).toBe(true);
    // paths dimension is not fully denied — no warning there
    expect(
      result.warnings.some((w) => w.includes("self-nullified") && w.includes("'paths'"))
    ).toBe(false);
  });

  it("does not warn when the deny only carves a hole", () => {
    const m = manifest([
      SLETTEAGENT,
      {
        ...BASE_PLAYBOOK,
        action_scope: { deny: { paths: ["legal/hold/**"] } },
        steps: [{ id: "slett", uses: "sletteagent", authority_level: "commit" }],
      },
    ]);
    const result = validate(m);
    expect(result.warnings.some((w) => w.includes("self-nullified"))).toBe(false);
  });

  it("skill deny alone can self-nullify a step (union includes the skill's own list)", () => {
    const m = manifest([
      {
        ...SLETTEAGENT,
        action_scope: {
          tools: ["transfer_ownership"],
          deny: { tools: ["transfer_ownership"] },
        },
      },
      {
        ...BASE_PLAYBOOK,
        steps: [{ id: "slett", uses: "sletteagent", authority_level: "commit" }],
      },
    ]);
    const result = validate(m);
    expect(
      result.warnings.some((w) => w.includes("self-nullified") && w.includes("'tools'"))
    ).toBe(true);
  });

  it("empty deny on a playbook draws the §4.3a prohibits-nothing lint", () => {
    const m = manifest([
      SLETTEAGENT,
      {
        ...BASE_PLAYBOOK,
        action_scope: { deny: {} },
        steps: [{ id: "slett", uses: "sletteagent", authority_level: "commit" }],
      },
    ]);
    const result = validate(m);
    expect(
      result.warnings.some((w) => w.includes("prohibits nothing"))
    ).toBe(true);
  });
});
