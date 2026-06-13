import { describe, it, expect } from "vitest";
import { validate } from "../src/validator.js";
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

  it("warns when verification_status is rumored and confidence >= 0.5", () => {
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
      result.warnings.some(
        (w) => w.includes("rumored") && w.includes("confidence")
      )
    ).toBe(true);
    expect(result.isValid).toBe(true);
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
