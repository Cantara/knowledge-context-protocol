import { describe, it, expect } from "vitest";
import { parseDict } from "../../shared/src/parser.js";
import yaml from "js-yaml";

/**
 * Boolean coercion — YAML 1.2, per SPEC.md §2 (#151, #153, #156).
 *
 * §2 mandates YAML 1.2. In 1.2 only `true`/`false` — and their capitalised and all-caps
 * spellings — are booleans; `yes`/`no`/`on`/`off` are plain **strings**. The JSON schema
 * types these fields as `boolean`, so such a string is a schema violation, not a
 * shorthand to be rescued.
 *
 * This landed in three attempts, and the middle one is the instructive failure:
 *
 *  1. `Boolean()` accepted every non-empty string, so every negative and every typo read
 *     as `true` — `deprecated: no` meant deprecated.
 *  2. The fix mapped the YAML 1.1 words to booleans. That made all three parsers agree —
 *     on an answer the schema rejects. Agreement is not correctness.
 *  3. Python and Java now resolve booleans per YAML 1.2 at the *loader*, which is the
 *     only place it can be fixed for them: PyYAML converts `yes` to `True` before any
 *     KCP code runs, so no downstream helper can tell it from `true`.
 *
 * These tests go through the YAML loader rather than constructing objects, because the
 * behaviour under test lives in the seam between what the loader produces and what the
 * parser does with it. Handing the parser a JavaScript `false` would test nothing.
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

describe("YAML 1.2 core-schema booleans are the only booleans (#156)", () => {
  for (const v of ["true", "True", "TRUE"]) {
    it(`deprecated: ${v} → true`, () => {
      expect(unit(`    deprecated: ${v}`).deprecated).toBe(true);
    });
  }
  for (const v of ["false", "False", "FALSE"]) {
    it(`deprecated: ${v} → false`, () => {
      expect(unit(`    deprecated: ${v}`).deprecated).toBe(false);
    });
  }
});

describe("YAML 1.1 boolean words are strings, and read as undeclared (#156)", () => {
  // The heart of the fix. These are valid YAML 1.2 — they are simply strings — and a
  // string in a field the schema types `boolean` is a schema violation. Verified
  // directly against the JSON schema: `'yes' is not of type 'boolean'`.
  for (const v of ["yes", "Yes", "YES", "no", "No", "NO", "on", "On", "off", "Off"]) {
    it(`deprecated: ${v} → undefined`, () => {
      expect(unit(`    deprecated: ${v}`).deprecated).toBeUndefined();
    });
  }

  it("the regression this began with: `deprecated: no` is not deprecated", () => {
    // Originally `true` — a live unit dropped from every plan, in TypeScript only.
    // Then `false` — agreeing with the other parsers, on a value the schema rejects.
    // Now undeclared, which is what the schema says it is.
    expect(unit("    deprecated: no").deprecated).toBeUndefined();
  });
});

describe("anything else reads as undeclared, never as true (#151)", () => {
  for (const v of ["flase", "nope", "1", "0", "maybe", "[]", "{}"]) {
    it(`deprecated: ${v} → undefined`, () => {
      expect(unit(`    deprecated: ${v}`).deprecated).toBeUndefined();
    });
  }

  it("a quoted boolean is a string, and now genuinely rejected", () => {
    // Under the 1.1-word mapping this could not be distinguished from a bare `yes`,
    // because the quoting is gone before the parser sees the value. Rejecting the words
    // outright makes the question moot: `"true"` is a string either way.
    expect(unit('    deprecated: "true"').deprecated).toBeUndefined();
    expect(unit('    deprecated: "yes"').deprecated).toBeUndefined();
  });

  it("an absent field stays absent", () => {
    expect(unit("    format: markdown").deprecated).toBeUndefined();
  });
});

describe("every boolean field shares the coercion (#151)", () => {
  // The original bug was 14 separate `Boolean()` calls. Fixing one is not fixing the
  // class, so this checks the shared helper reached the fields that carry risk.
  it("not_for_strict: off is undeclared; true is true", () => {
    expect(unit("    not_for_strict: off").not_for_strict).toBeUndefined();
    expect(unit("    not_for_strict: true").not_for_strict).toBe(true);
  });

  it("trust.agent_requirements booleans follow the same rule", () => {
    const m = parseDict(
      yaml.load(`
project: t
version: 1.0.0
kcp_version: "0.29"
trust:
  agent_requirements:
    require_attestation: false
    propagate_to_governed: off
units: []
`) as Record<string, unknown>,
    );
    expect(m.trust?.agent_requirements?.require_attestation).toBe(false);
    expect(m.trust?.agent_requirements?.propagate_to_governed).toBeUndefined();
  });

  it("payment method free_tier follows the same rule", () => {
    // free_tier sits on a payment *method*, not on payment — an earlier version of this
    // test asserted one level too high and passed trivially against undefined.
    const m = parseDict(
      yaml.load(`
project: t
version: 1.0.0
kcp_version: "0.29"
payment:
  default_tier: subscription
  methods:
    - type: subscription
      free_tier: false
units: []
`) as Record<string, unknown>,
    );
    expect(m.payment?.methods?.[0]?.free_tier).toBe(false);
  });
});
