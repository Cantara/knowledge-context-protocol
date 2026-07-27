import { describe, it, expect } from "vitest";
import { parseDict } from "../../shared/src/parser.js";
import { validate } from "../../shared/src/validator.js";

/**
 * kind: playbook — §4.3b (v0.29, RFC-0027).
 *
 * The conformance rules here are the ones the RFC states as MUST, and several exist
 * because the adversarial review found them missing. Two in particular are tested
 * from the attack direction rather than the happy path:
 *
 *  - an unresolvable `uses` must be an ERROR, not a warning. A resolvable `uses` is
 *    the entire justification for playbook being a distinct kind rather than
 *    `executable` plus metadata; a dangling reference that lints clean removes the
 *    only thing the new kind buys.
 *  - nesting must be an ERROR pending RFC-0027 OQ1. Left as a warning it is no guard:
 *    nested playbooks form a combined depends_on graph that the per-playbook cycle
 *    check never sees.
 */

function manifest(units: unknown[], extra: Record<string, unknown> = {}) {
  return parseDict({
    project: "example",
    version: "1.0.0",
    kcp_version: "0.29",
    units,
    ...extra,
  });
}

const SKILL = {
  id: "run-test-suite",
  kind: "skill",
  path: "skills/run.md",
  intent: "How do I run the suite?",
  scope: "project",
  audience: ["agent"],
  action_scope: { tools: ["bash"], paths: ["test/**"] },
};

function playbook(steps: unknown[], extra: Record<string, unknown> = {}) {
  return {
    id: "promote",
    kind: "playbook",
    path: "playbooks/promote.md",
    intent: "How do we promote a build?",
    scope: "project",
    audience: ["agent"],
    steps,
    ...extra,
  };
}

describe("parsing", () => {
  it("parses steps with every declared field", () => {
    const m = manifest([
      SKILL,
      playbook([
        {
          id: "verify",
          uses: "run-test-suite",
          depends_on: [],
          authority_level: "observe",
          escalation: ["requires_approval"],
          success_condition: "zero failures",
          on_failure: "abort",
          timeout: "PT10M",
        },
      ]),
    ]);
    const step = m.units[1]!.steps![0]!;
    expect(step.id).toBe("verify");
    expect(step.uses).toBe("run-test-suite");
    expect(step.authority_level).toBe("observe");
    expect(step.escalation).toEqual(["requires_approval"]);
    expect(step.success_condition).toBe("zero failures");
    expect(step.on_failure).toBe("abort");
    expect(step.timeout).toBe("PT10M");
  });

  it("normalises a bare escalation string to a single-element list", () => {
    // §4.3b calls the triggers disjunctive, so a scalar and a one-element list mean
    // the same thing. Normalising at parse time means no consumer has to handle both.
    const m = manifest([SKILL, playbook([{ id: "a", uses: "run-test-suite", escalation: "requires_approval" }])]);
    expect(m.units[1]!.steps![0]!.escalation).toEqual(["requires_approval"]);
  });

  it("absent steps is undefined, not an empty list", () => {
    // "declares no steps" and "declares an empty composition" are different
    // statements; the validator rejects both for a playbook, but the parser must
    // keep them distinguishable.
    const m = manifest([{ ...SKILL, action_scope: undefined }]);
    expect(m.units[0]!.steps).toBeUndefined();
  });

  it("drops steps that are not objects or carry no id", () => {
    // A step without an identity cannot be named by depends_on, so it cannot take
    // part in the graph at all. Half-parsing it would put an id-less entry into a
    // structure that is indexed by id.
    const m = manifest([SKILL, playbook(["not-an-object", { uses: "run-test-suite" }, { id: "ok", action: "x" }])]);
    expect(m.units[1]!.steps!.map((s) => s.id)).toEqual(["ok"]);
  });

  it("a malformed steps block does not take down the parse", () => {
    const m = manifest([SKILL, playbook("steps" as unknown as unknown[])]);
    expect(m.units[1]!.steps).toBeUndefined();
    expect(m.units[1]!.id).toBe("promote");
  });
});

describe("validation — structure", () => {
  it("accepts a well-formed playbook with no errors", () => {
    const r = validate(
      manifest([SKILL, playbook([{ id: "verify", uses: "run-test-suite", authority_level: "observe" }])],
        { authority_level_scale: ["observe", "explain", "suggest", "prepare", "commit"] })
    );
    expect(r.errors).toEqual([]);
  });

  it("errors when a playbook declares no steps", () => {
    const r = validate(manifest([{ ...playbook([]), steps: undefined }]));
    expect(r.errors.some((e) => /MUST declare a non-empty 'steps'/.test(e))).toBe(true);
  });

  it("errors on an empty steps list", () => {
    const r = validate(manifest([playbook([])]));
    expect(r.errors.some((e) => /non-empty 'steps'/.test(e))).toBe(true);
  });

  it("errors when a step declares neither uses nor action", () => {
    const r = validate(manifest([playbook([{ id: "orphan" }])]));
    expect(r.errors.some((e) => /MUST declare either 'uses' or 'action'/.test(e))).toBe(true);
  });

  it("errors on duplicate step ids", () => {
    const r = validate(manifest([SKILL, playbook([
      { id: "a", uses: "run-test-suite" },
      { id: "a", action: "again" },
    ])]));
    expect(r.errors.some((e) => /duplicate step id 'a'/.test(e))).toBe(true);
  });

  it("errors on an unknown on_failure value", () => {
    const r = validate(manifest([SKILL, playbook([{ id: "a", uses: "run-test-suite", on_failure: "retry" }])]));
    expect(r.errors.some((e) => /'on_failure' must be one of/.test(e))).toBe(true);
  });
});

describe("validation — uses resolution", () => {
  it("ERRORS on an unresolvable uses, rather than warning", () => {
    const r = validate(manifest([playbook([{ id: "a", uses: "nonexistent" }])]));
    expect(r.errors.some((e) => /not declared in this manifest/.test(e))).toBe(true);
    expect(r.warnings.some((w) => /nonexistent/.test(w))).toBe(false);
  });

  it("resolves a uses that names a unit declared LATER in the manifest", () => {
    // The check is a second pass for exactly this reason: unitIds is incomplete
    // mid-loop, so an inline check would reject a legal forward reference.
    const r = validate(manifest([playbook([{ id: "a", uses: "run-test-suite" }]), SKILL]));
    expect(r.errors).toEqual([]);
  });

  it("ERRORS when uses names another playbook (nesting is forbidden, OQ1)", () => {
    const inner = { ...playbook([{ id: "x", action: "inner" }]), id: "inner" };
    const outer = { ...playbook([{ id: "a", uses: "inner" }]), id: "outer" };
    const r = validate(manifest([inner, outer]));
    expect(r.errors.some((e) => /nesting is not permitted/.test(e))).toBe(true);
  });

  it("warns — not errors — when uses names a resolvable non-skill unit", () => {
    const doc = { id: "notes", kind: "knowledge", path: "n.md", intent: "x", scope: "project", audience: ["agent"] };
    const r = validate(manifest([doc, playbook([{ id: "a", uses: "notes" }])]));
    expect(r.errors).toEqual([]);
    expect(r.warnings.some((w) => /SHOULD name a kind: skill unit/.test(w))).toBe(true);
  });
});

describe("validation — the depends_on graph", () => {
  it("errors on a dangling depends_on", () => {
    const r = validate(manifest([SKILL, playbook([{ id: "a", uses: "run-test-suite", depends_on: ["ghost"] }])]));
    expect(r.errors.some((e) => /depends_on names unknown step 'ghost'/.test(e))).toBe(true);
  });

  it("errors on a two-step cycle", () => {
    const r = validate(manifest([SKILL, playbook([
      { id: "a", uses: "run-test-suite", depends_on: ["b"] },
      { id: "b", uses: "run-test-suite", depends_on: ["a"] },
    ])]));
    expect(r.errors.some((e) => /contains a cycle/.test(e))).toBe(true);
  });

  it("errors on a longer cycle and names the path", () => {
    const r = validate(manifest([SKILL, playbook([
      { id: "a", uses: "run-test-suite", depends_on: ["c"] },
      { id: "b", uses: "run-test-suite", depends_on: ["a"] },
      { id: "c", uses: "run-test-suite", depends_on: ["b"] },
    ])]));
    const cycle = r.errors.find((e) => /contains a cycle/.test(e));
    expect(cycle).toBeDefined();
    expect(cycle).toMatch(/->/);
  });

  it("errors on a self-dependency", () => {
    const r = validate(manifest([SKILL, playbook([{ id: "a", uses: "run-test-suite", depends_on: ["a"] }])]));
    expect(r.errors.some((e) => /contains a cycle/.test(e))).toBe(true);
  });

  it("accepts a diamond — shared dependencies are not cycles", () => {
    // The classic false positive for a naive visited-set walk: d is reached twice by
    // distinct paths, which is convergence, not a cycle.
    const r = validate(manifest([SKILL, playbook([
      { id: "a", uses: "run-test-suite" },
      { id: "b", uses: "run-test-suite", depends_on: ["a"] },
      { id: "c", uses: "run-test-suite", depends_on: ["a"] },
      { id: "d", uses: "run-test-suite", depends_on: ["b", "c"] },
    ])]));
    expect(r.errors).toEqual([]);
  });

  it("does not stack-overflow on a long chain", () => {
    // Untrusted input: a deep chain must report cleanly, not crash the validator.
    const steps = Array.from({ length: 5000 }, (_, i) => ({
      id: `s${i}`, uses: "run-test-suite", depends_on: i > 0 ? [`s${i - 1}`] : [],
    }));
    const r = validate(manifest([SKILL, playbook(steps)]));
    expect(r.errors.filter((e) => /cycle/.test(e))).toEqual([]);
  });
});

describe("validation — scope verifiability", () => {
  it("warns that inline steps are bounded only by authority_level", () => {
    const r = validate(manifest([playbook([{ id: "a", action: "do the thing" }])]));
    expect(r.warnings.some((w) => /inline .*bounded only by its authority_level/.test(w))).toBe(true);
  });

  it("reports a declared action_scope as UNVERIFIED when any step is inline", () => {
    // §4.3b: an unverifiable declaration that lints clean is worse than none,
    // because it reads as checked.
    const r = validate(manifest([
      SKILL,
      playbook([{ id: "a", uses: "run-test-suite" }, { id: "b", action: "inline" }],
        { action_scope: { tools: ["bash"] } }),
    ]));
    expect(r.warnings.some((w) => /UNVERIFIED/.test(w))).toBe(true);
  });

  it("reports UNVERIFIED when a referenced unit declares no action_scope", () => {
    const scopeless = { ...SKILL, id: "bare", action_scope: undefined };
    const r = validate(manifest([
      scopeless,
      playbook([{ id: "a", uses: "bare" }], { action_scope: { tools: ["bash"] } }),
    ]));
    expect(r.warnings.some((w) => /UNVERIFIED/.test(w))).toBe(true);
  });

  it("does not report UNVERIFIED when every step resolves to a scoped unit", () => {
    const r = validate(manifest([
      SKILL,
      playbook([{ id: "a", uses: "run-test-suite" }], { action_scope: { tools: ["bash"] } }),
    ]));
    expect(r.warnings.some((w) => /UNVERIFIED/.test(w))).toBe(false);
  });

  it("warns when a mutating step omits authority_level", () => {
    const r = validate(manifest([SKILL, playbook([{ id: "a", uses: "run-test-suite" }])]));
    expect(r.warnings.some((w) => /omits 'authority_level'/.test(w))).toBe(true);
  });
});

describe("validation — non-playbook units", () => {
  it("warns when a non-playbook declares steps", () => {
    const r = validate(manifest([{ ...SKILL, steps: [{ id: "a", action: "x" }] }]));
    expect(r.warnings.some((w) => /steps are only enacted for kind: playbook/.test(w))).toBe(true);
  });

  it("playbook is a recognised kind and does not warn as unknown", () => {
    const r = validate(manifest([SKILL, playbook([{ id: "a", uses: "run-test-suite" }])]));
    expect(r.warnings.some((w) => /unknown kind/.test(w))).toBe(false);
  });
});
