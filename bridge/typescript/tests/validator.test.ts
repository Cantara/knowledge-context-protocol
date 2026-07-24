import { describe, it, expect } from "vitest";
import { validate, computeGrantCeiling, applyAuthorityCap } from "../src/validator.js";
import { parseDict } from "../src/parser.js";

function makeManifest(overrides: Record<string, unknown> = {}) {
  return parseDict({
    project: "test",
    version: "1.0.0",
    kcp_version: "0.12",
    units: [
      {
        id: "overview",
        path: "README.md",
        intent: "What is this project?",
        scope: "global",
        audience: ["agent"],
      },
    ],
    ...overrides,
  });
}

describe("validate — kcp_version 0.12", () => {
  it("accepts kcp_version 0.12 without warning", () => {
    const result = validate(makeManifest());
    const versionWarnings = result.warnings.filter((w) =>
      w.includes("kcp_version")
    );
    expect(versionWarnings).toHaveLength(0);
    expect(result.isValid).toBe(true);
  });
});

describe("validate — authority block", () => {
  it("no warnings for well-formed authority values", () => {
    const manifest = makeManifest({
      units: [
        {
          id: "u",
          path: "f.md",
          intent: "i",
          scope: "global",
          audience: ["agent"],
          authority: {
            read: "initiative",
            summarize: "initiative",
            modify: "requires_approval",
            share_externally: "denied",
            execute: "denied",
          },
        },
      ],
    });
    const result = validate(manifest);
    const authorityWarnings = result.warnings.filter((w) =>
      w.includes("authority")
    );
    expect(authorityWarnings).toHaveLength(0);
    expect(result.isValid).toBe(true);
  });

  it("warns on unknown value for a known authority action", () => {
    const manifest = makeManifest({
      units: [
        {
          id: "u",
          path: "f.md",
          intent: "i",
          scope: "global",
          audience: ["agent"],
          authority: { read: "allow" },
        },
      ],
    });
    const result = validate(manifest);
    expect(result.warnings.some((w) => w.includes("authority.read") && w.includes("allow"))).toBe(true);
    // Warn but do not reject — isValid is still true
    expect(result.isValid).toBe(true);
  });

  it("warns on unknown value for a custom authority action", () => {
    const manifest = makeManifest({
      units: [
        {
          id: "u",
          path: "f.md",
          intent: "i",
          scope: "global",
          audience: ["agent"],
          authority: { export_pdf: "yes" },
        },
      ],
    });
    const result = validate(manifest);
    expect(
      result.warnings.some(
        (w) => w.includes("export_pdf") && w.includes("yes")
      )
    ).toBe(true);
    expect(result.isValid).toBe(true);
  });

  it("no warnings for a valid custom authority action value", () => {
    const manifest = makeManifest({
      units: [
        {
          id: "u",
          path: "f.md",
          intent: "i",
          scope: "global",
          audience: ["agent"],
          authority: { export_pdf: "requires_approval" },
        },
      ],
    });
    const result = validate(manifest);
    const authorityWarnings = result.warnings.filter((w) =>
      w.includes("authority")
    );
    expect(authorityWarnings).toHaveLength(0);
  });
});

describe("validate — discovery block", () => {
  it("no warnings for a well-formed observed discovery block", () => {
    const manifest = makeManifest({
      units: [
        {
          id: "u",
          path: "f.md",
          intent: "i",
          scope: "global",
          audience: ["agent"],
          discovery: {
            verification_status: "observed",
            source: "web_traversal",
            observed_at: "2026-03-01T10:00:00Z",
            confidence: 0.72,
          },
        },
      ],
    });
    const result = validate(manifest);
    const discoveryWarnings = result.warnings.filter((w) =>
      w.includes("discovery")
    );
    expect(discoveryWarnings).toHaveLength(0);
    expect(result.isValid).toBe(true);
  });

  it("errors when verification_status is rumored and confidence >= 0.5", () => {
    const manifest = makeManifest({
      units: [
        {
          id: "u",
          path: "f.md",
          intent: "i",
          scope: "global",
          audience: ["agent"],
          discovery: {
            verification_status: "rumored",
            confidence: 0.8,
          },
        },
      ],
    });
    const result = validate(manifest);
    expect(
      result.errors.some(
        (e) => e.includes("rumored") && e.includes("confidence")
      )
    ).toBe(true);
    expect(result.isValid).toBe(false);
  });

  it("no warning when rumored with low confidence (< 0.5)", () => {
    const manifest = makeManifest({
      units: [
        {
          id: "u",
          path: "f.md",
          intent: "i",
          scope: "global",
          audience: ["agent"],
          discovery: { verification_status: "rumored", confidence: 0.3 },
        },
      ],
    });
    const result = validate(manifest);
    const rumoredConfidenceWarnings = result.warnings.filter(
      (w) => w.includes("rumored") && w.includes("confidence")
    );
    expect(rumoredConfidenceWarnings).toHaveLength(0);
  });

  it("warns when verified_at is set but status is rumored", () => {
    const manifest = makeManifest({
      units: [
        {
          id: "u",
          path: "f.md",
          intent: "i",
          scope: "global",
          audience: ["agent"],
          discovery: {
            verification_status: "rumored",
            verified_at: "2026-03-10T00:00:00Z",
          },
        },
      ],
    });
    const result = validate(manifest);
    expect(
      result.warnings.some(
        (w) => w.includes("verified_at") && w.includes("rumored")
      )
    ).toBe(true);
  });

  it("warns when verified_at is set but status is observed", () => {
    const manifest = makeManifest({
      units: [
        {
          id: "u",
          path: "f.md",
          intent: "i",
          scope: "global",
          audience: ["agent"],
          discovery: {
            verification_status: "observed",
            verified_at: "2026-03-10T00:00:00Z",
          },
        },
      ],
    });
    const result = validate(manifest);
    expect(
      result.warnings.some(
        (w) => w.includes("verified_at") && w.includes("observed")
      )
    ).toBe(true);
  });

  it("no warning when verified_at is set and status is verified", () => {
    const manifest = makeManifest({
      units: [
        {
          id: "u",
          path: "f.md",
          intent: "i",
          scope: "global",
          audience: ["agent"],
          discovery: {
            verification_status: "verified",
            verified_at: "2026-03-10T00:00:00Z",
          },
        },
      ],
    });
    const result = validate(manifest);
    const verifiedAtWarnings = result.warnings.filter((w) =>
      w.includes("verified_at")
    );
    expect(verifiedAtWarnings).toHaveLength(0);
  });

  it("warns when contradicted_by references an unknown unit id", () => {
    const manifest = makeManifest({
      units: [
        {
          id: "u",
          path: "f.md",
          intent: "i",
          scope: "global",
          audience: ["agent"],
          discovery: {
            verification_status: "observed",
            contradicted_by: "nonexistent-unit",
          },
        },
      ],
    });
    const result = validate(manifest);
    expect(
      result.warnings.some(
        (w) =>
          w.includes("contradicted_by") && w.includes("nonexistent-unit")
      )
    ).toBe(true);
  });

  it("no warning when contradicted_by references a unit that appears earlier in the list", () => {
    // The validator builds unitIds incrementally — a unit can reference one that
    // was already processed (appears earlier in the units array) without warning.
    const manifest = makeManifest({
      units: [
        {
          id: "v",
          path: "g.md",
          intent: "j",
          scope: "global",
          audience: ["agent"],
        },
        {
          id: "u",
          path: "f.md",
          intent: "i",
          scope: "global",
          audience: ["agent"],
          discovery: {
            verification_status: "observed",
            contradicted_by: "v",
          },
        },
      ],
    });
    const result = validate(manifest);
    const contradictedWarnings = result.warnings.filter((w) =>
      w.includes("contradicted_by")
    );
    expect(contradictedWarnings).toHaveLength(0);
  });
});

describe("validate — §3.13 authority_level / grant_ceiling (RFC-0025, v0.27)", () => {
  const scale = ["observe", "explain", "suggest", "prepare", "commit"];

  it("accepts a well-formed grant_ceiling with inline sources", () => {
    const manifest = makeManifest({
      authority_level_scale: scale,
      task_types: [{ id: "t1", authority_level: "explain" }],
      grant_ceiling: {
        sources: [
          { id: "org-risk", authority_level: "prepare" },
          { id: "task-ceiling", task_type_ref: "t1" },
        ],
      },
    });
    const result = validate(manifest);
    expect(result.errors).toHaveLength(0);
  });

  it("errors on duplicate task_types[].id", () => {
    const manifest = makeManifest({
      task_types: [{ id: "dup" }, { id: "dup" }],
    });
    const result = validate(manifest);
    expect(result.errors.some((e) => e.includes("Duplicate task_types[].id"))).toBe(true);
  });

  it("errors on duplicate agents[].id", () => {
    const manifest = makeManifest({
      agents: [{ id: "dup" }, { id: "dup" }],
    });
    const result = validate(manifest);
    expect(result.errors.some((e) => e.includes("Duplicate agents[].id"))).toBe(true);
  });

  it("errors when grant_ceiling.sources omits a mandatory source", () => {
    const manifest = makeManifest({
      grant_ceiling: {
        sources: [{ id: "a", authority_level: "prepare" }],
        mandatory_sources: ["a", "b"],
      },
    });
    const result = validate(manifest);
    expect(result.errors.some((e) => e.includes("missing mandatory source 'b'"))).toBe(true);
  });

  it("errors when a grant_ceiling source declares both authority_level and a ref", () => {
    const manifest = makeManifest({
      task_types: [{ id: "t1", authority_level: "explain" }],
      grant_ceiling: {
        sources: [{ id: "a", authority_level: "prepare", task_type_ref: "t1" }],
      },
    });
    const result = validate(manifest);
    expect(result.errors.some((e) => e.includes("mutually exclusive"))).toBe(true);
  });

  it("errors when a grant_ceiling source declares neither authority_level nor a ref", () => {
    const manifest = makeManifest({
      grant_ceiling: { sources: [{ id: "a" }] },
    });
    const result = validate(manifest);
    expect(result.errors.some((e) => e.includes("must declare exactly one of"))).toBe(true);
  });

  it("errors when unit_ref/task_type_ref/agent_ref points to an unknown id", () => {
    const manifest = makeManifest({
      grant_ceiling: {
        sources: [
          { id: "a", unit_ref: "nope" },
          { id: "b", task_type_ref: "nope" },
          { id: "c", agent_ref: "nope" },
        ],
      },
    });
    const result = validate(manifest);
    expect(result.errors.filter((e) => e.includes("references unknown"))).toHaveLength(3);
  });

  it("warns on an authority_level value not in the declared scale", () => {
    const manifest = makeManifest({
      authority_level_scale: scale,
      task_types: [{ id: "t1", authority_level: "yolo" }],
    });
    const result = validate(manifest);
    expect(result.warnings.some((w) => w.includes("not in the declared 'authority_level_scale'"))).toBe(true);
  });

  it("warns authority_ceiling_undeclared when scale is declared but a task-type has no ceiling", () => {
    const manifest = makeManifest({
      authority_level_scale: scale,
      task_types: [{ id: "t1" }],
    });
    const result = validate(manifest);
    expect(result.warnings.some((w) => w.includes("authority_ceiling_undeclared"))).toBe(true);
  });

  it("does not warn authority_ceiling_undeclared when a grant_ceiling exists", () => {
    const manifest = makeManifest({
      authority_level_scale: scale,
      task_types: [{ id: "t1" }],
      grant_ceiling: { sources: [{ id: "a", authority_level: "prepare" }] },
    });
    const result = validate(manifest);
    expect(result.warnings.some((w) => w.includes("authority_ceiling_undeclared"))).toBe(false);
  });

  it("computeGrantCeiling: resolves the minimum across sources and names the binding source", () => {
    const manifest = makeManifest({
      authority_level_scale: scale,
      task_types: [{ id: "change-status", authority_level: "explain" }],
      agents: [{ id: "lara", authority_level: "prepare" }],
      grant_ceiling: {
        sources: [
          { id: "org-risk", authority_level: "prepare" },
          { id: "org-data", authority_level: "suggest" },
          { id: "task-ceiling", task_type_ref: "change-status" },
          { id: "agent-ceiling", agent_ref: "lara" },
        ],
      },
    });
    const { effectiveLevel, bindingSourceIds } = computeGrantCeiling(manifest);
    expect(effectiveLevel).toBe("explain");
    expect(bindingSourceIds).toEqual(["task-ceiling"]);
  });

  it("computeGrantCeiling: reports all tied sources", () => {
    const manifest = makeManifest({
      authority_level_scale: scale,
      grant_ceiling: {
        sources: [
          { id: "a", authority_level: "suggest" },
          { id: "b", authority_level: "suggest" },
          { id: "c", authority_level: "prepare" },
        ],
      },
    });
    const { effectiveLevel, bindingSourceIds } = computeGrantCeiling(manifest);
    expect(effectiveLevel).toBe("suggest");
    expect(bindingSourceIds.sort()).toEqual(["a", "b"]);
  });

  it("computeGrantCeiling: a reference to an entity with no declared ceiling is non-binding", () => {
    const manifest = makeManifest({
      authority_level_scale: scale,
      units: [
        { id: "u1", path: "f.md", intent: "i", scope: "global", audience: ["agent"] },
      ],
      grant_ceiling: {
        sources: [
          { id: "org-risk", authority_level: "prepare" },
          { id: "unit-ceiling", unit_ref: "u1" }, // u1 has no authority_level declared
        ],
      },
    });
    const { effectiveLevel, bindingSourceIds } = computeGrantCeiling(manifest);
    expect(effectiveLevel).toBe("prepare");
    expect(bindingSourceIds).toEqual(["org-risk"]);
  });

  it("applyAuthorityCap: caps a declared permission stricter than the effective level allows", () => {
    expect(applyAuthorityCap("initiative", "modify", "suggest")).toBe("requires_approval");
    expect(applyAuthorityCap("initiative", "share_externally", "explain")).toBe("denied");
  });

  it("applyAuthorityCap: never widens a declared permission that is already stricter", () => {
    expect(applyAuthorityCap("denied", "modify", "commit")).toBe("denied");
  });

  it("applyAuthorityCap: passes through when no effective level is in scope", () => {
    expect(applyAuthorityCap("initiative", "modify", undefined)).toBe("initiative");
  });
});
