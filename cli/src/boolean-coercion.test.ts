import { describe, it, expect } from "vitest";
import { parseDict } from "../../shared/src/parser.js";
import yaml from "js-yaml";

/**
 * Boolean coercion — the three reference parsers must agree (#151).
 *
 * `Boolean()` on any non-empty string is `true`, and js-yaml implements YAML 1.2, which
 * leaves `yes`/`no`/`on`/`off` as strings. PyYAML and SnakeYAML implement YAML 1.1 and
 * parse them as booleans. So `deprecated: no` was read as **deprecated** in TypeScript
 * and **not deprecated** in Python and Java — the same manifest saying opposite things.
 *
 * The failure is asymmetric in the dangerous direction: every negative became a
 * positive, and so did every typo. `deprecated: flase` was `true`.
 *
 * These tests go through the YAML loader rather than constructing objects directly,
 * because the bug lives in the seam between what js-yaml produces and what the parser
 * does with it. Handing the parser a JavaScript `false` would test nothing.
 */

function unit(fields: string): Record<string, unknown> {
  const doc = yaml.load(`
project: t
version: 1.0.0
kcp_version: "0.29"
units:
  - id: u
    path: u.md
    intent: "i"
    scope: project
    audience: [agent]
${fields}
`) as Record<string, unknown>;
  return parseDict(doc).units[0] as unknown as Record<string, unknown>;
}

describe("YAML 1.1 boolean words agree with the Python and Java parsers (#151)", () => {
  // PyYAML and SnakeYAML both read these as booleans. TypeScript must reach the same
  // value, or a manifest reviewed in one language behaves differently in another.
  const falsey = ["no", "No", "NO", "off", "Off", "OFF", "false", "False"];
  const truthy = ["yes", "Yes", "YES", "on", "On", "ON", "true", "True"];

  for (const v of falsey) {
    it(`deprecated: ${v} → false`, () => {
      expect(unit(`    deprecated: ${v}`).deprecated).toBe(false);
    });
  }

  for (const v of truthy) {
    it(`deprecated: ${v} → true`, () => {
      expect(unit(`    deprecated: ${v}`).deprecated).toBe(true);
    });
  }

  it("the regression this was filed for: `deprecated: no` is not deprecated", () => {
    // Before the fix this was `true`, so a live unit was dropped from every plan as
    // deprecated — in TypeScript only.
    expect(unit("    deprecated: no").deprecated).toBe(false);
  });
});

describe("a value that is not a boolean reads as undeclared, not as true (#151)", () => {
  // The safe direction. An unparseable value must leave the field absent so the unit
  // falls back to its declared default, rather than silently switching a flag on.
  for (const v of ["flase", "nope", "1", "0", "maybe", "[]", "{}"]) {
    it(`deprecated: ${v} → undefined`, () => {
      expect(unit(`    deprecated: ${v}`).deprecated).toBeUndefined();
    });
  }

  it("a QUOTED boolean word is indistinguishable from a bare one, and resolves", () => {
    // An earlier version of this test asserted `"yes"` → undefined, on the reasoning
    // that quoting means the author wrote text. That is unachievable: js-yaml is a YAML
    // 1.2 parser, so bare `yes` and quoted `"yes"` both arrive as the string "yes" —
    // the quoting is gone before the parser sees the value. Distinguishing them would
    // require reading raw bytes, which a manifest parser does not do.
    //
    // The consequence is accepted rather than hidden: quoted boolean words resolve.
    // It is a far narrower surface than `Boolean()`, which accepted every non-empty
    // string including typos.
    expect(unit('    deprecated: "yes"').deprecated).toBe(true);
    expect(unit('    deprecated: "no"').deprecated).toBe(false);
  });

  it("an absent field stays absent", () => {
    expect(unit("    format: markdown").deprecated).toBeUndefined();
  });
});

describe("every boolean field on a unit uses the same coercion (#151)", () => {
  // The bug was 14 separate `Boolean()` calls. Fixing one is not fixing the class, so
  // this asserts the shared helper actually reached the fields that carry risk.
  it("not_for_strict: off → false", () => {
    expect(unit("    not_for_strict: off").not_for_strict).toBe(false);
  });

  it("not_for_strict: flase → undefined", () => {
    expect(unit("    not_for_strict: flase").not_for_strict).toBeUndefined();
  });

  it("trust.agent_requirements.require_attestation: no → false", () => {
    const m = parseDict(
      yaml.load(`
project: t
version: 1.0.0
kcp_version: "0.29"
trust:
  agent_requirements:
    require_attestation: no
    propagate_to_governed: off
units: []
`) as Record<string, unknown>,
    );
    expect(m.trust?.agent_requirements?.require_attestation).toBe(false);
    expect(m.trust?.agent_requirements?.propagate_to_governed).toBe(false);
  });

  it("payment method free_tier: no → false", () => {
    // free_tier sits on a payment *method*, not on payment. An earlier version of this
    // test put it one level too high and passed trivially against undefined — which is
    // its own small lesson about asserting on a field you have not located.
    const m = parseDict(
      yaml.load(`
project: t
version: 1.0.0
kcp_version: "0.29"
payment:
  default_tier: subscription
  methods:
    - type: subscription
      free_tier: no
units: []
`) as Record<string, unknown>,
    );
    expect(m.payment?.methods?.[0]?.free_tier).toBe(false);
  });
});
