// Tests for temporal validation (§4.22 unit-level; §3.6 manifests[].temporal).
// Covers the §7 advisory warnings and the superseded_by cycle MUST-errors that
// v0.19/v0.21 promoted but no validator implemented until the backlog pass.

import { describe, expect, it } from "vitest";
import { parseDict } from "./parser.js";
import { validate } from "./validator.js";

const PAST = "2000-01-01"; // always in the past
const FUTURE = "2999-12-31"; // always in the future

function manifest(extra: Record<string, unknown>): Record<string, unknown> {
  return { kcp_version: "0.21", project: "test", version: "1.0.0", ...extra };
}

function validateUnits(units: unknown[], extra: Record<string, unknown> = {}) {
  return validate(parseDict(manifest({ units, ...extra })));
}

const unit = (id: string, t?: Record<string, unknown>, extra: Record<string, unknown> = {}) => ({
  id,
  path: `docs/${id}.md`,
  intent: `Unit ${id}`,
  scope: "project",
  audience: ["agent"],
  ...(t ? { temporal: t } : {}),
  ...extra,
});

describe("unit-level temporal validation (§4.22)", () => {
  it("warns on an empty validity window (valid_until before valid_from)", () => {
    const r = validateUnits([unit("a", { valid_from: "2026-06-01", valid_until: "2026-01-01" })]);
    expect(r.warnings.some((w) => w.includes("empty validity window"))).toBe(true);
    expect(r.isValid).toBe(true); // warning, not error
  });

  it("does not warn on a normal window", () => {
    const r = validateUnits([unit("a", { valid_from: "2026-01-01", valid_until: FUTURE })]);
    expect(r.warnings.some((w) => w.includes("empty validity window"))).toBe(false);
    expect(r.warnings.some((w) => w.includes("stale"))).toBe(false);
  });

  it("warns on a stale unit (valid_until past, no superseded_by)", () => {
    const r = validateUnits([unit("a", { valid_until: PAST })]);
    expect(r.warnings.some((w) => w.includes("stale unit with no successor"))).toBe(true);
  });

  it("does not warn stale when superseded_by is set", () => {
    const r = validateUnits([
      unit("a", { valid_until: PAST, superseded_by: "b" }),
      unit("b"),
    ]);
    expect(r.warnings.some((w) => w.includes("stale unit"))).toBe(false);
  });

  it("warns on a dangling superseded_by reference", () => {
    const r = validateUnits([unit("a", { superseded_by: "ghost" })]);
    expect(r.warnings.some((w) => w.includes("superseded_by references unknown unit 'ghost'"))).toBe(true);
  });

  it("does not flag a namespaced superseded_by (targets an unresolved include)", () => {
    const r = validateUnits([unit("a", { superseded_by: "platform:newer" })]);
    expect(r.warnings.some((w) => w.includes("superseded_by references unknown"))).toBe(false);
  });

  it("errors on a superseded_by cycle (MUST)", () => {
    const r = validateUnits([
      unit("a", { superseded_by: "b" }),
      unit("b", { superseded_by: "a" }),
    ]);
    expect(r.isValid).toBe(false);
    expect(r.errors.some((e) => e.includes("superseded_by cycle"))).toBe(true);
  });

  it("does not error on a linear superseded_by chain", () => {
    const r = validateUnits([
      unit("a", { superseded_by: "b" }),
      unit("b", { superseded_by: "c" }),
      unit("c"),
    ]);
    expect(r.errors.some((e) => e.includes("superseded_by cycle"))).toBe(false);
  });

  it("applies root-level temporal defaults field-by-field", () => {
    // root sets valid_until in the past; unit sets only valid_from -> effective valid_until past
    const r = validateUnits([unit("a", { valid_from: "1999-01-01" })], {
      temporal: { valid_until: PAST },
    });
    expect(r.warnings.some((w) => w.includes("stale unit"))).toBe(true);
  });

  it("warns when verification_status is verified without verified_by (unit and root)", () => {
    const r = validateUnits([unit("a", {}, { discovery: { verification_status: "verified" } })], {
      discovery: { verification_status: "verified" },
    });
    expect(r.warnings.filter((w) => w.includes("verified_by is absent")).length).toBe(2);
  });

  it("does not warn when verified_by is present", () => {
    const r = validateUnits([
      unit("a", {}, { discovery: { verification_status: "verified", verified_by: "key-1" } }),
    ]);
    expect(r.warnings.some((w) => w.includes("verified_by is absent"))).toBe(false);
  });
});

describe("federation temporal validation (§3.6 manifests[].temporal)", () => {
  const ref = (id: string, t?: Record<string, unknown>) => ({
    id,
    url: `https://example.com/${id}/knowledge.yaml`,
    relationship: "governs",
    ...(t ? { temporal: t } : {}),
  });

  function validateManifests(manifests: unknown[]) {
    return validate(parseDict(manifest({ units: [unit("local")], manifests })));
  }

  it("exposes manifests[].temporal through the parser", () => {
    const m = parseDict(manifest({ units: [unit("local")], manifests: [ref("a", { valid_from: "2020-01-01" })] }));
    expect(m.manifests[0].temporal?.valid_from).toBe("2020-01-01");
  });

  it("warns on a stale federation link", () => {
    const r = validateManifests([ref("a", { valid_until: PAST })]);
    expect(r.warnings.some((w) => w.includes("stale federation link"))).toBe(true);
  });

  it("warns on an empty window and a dangling successor", () => {
    const r = validateManifests([
      ref("a", { valid_from: "2026-06-01", valid_until: "2026-01-01", superseded_by: "ghost" }),
    ]);
    expect(r.warnings.some((w) => w.includes("empty validity window"))).toBe(true);
    expect(r.warnings.some((w) => w.includes("unknown manifests[].id 'ghost'"))).toBe(true);
  });

  it("errors on a superseded_by cycle among manifests (MUST)", () => {
    const r = validateManifests([
      ref("a", { superseded_by: "b" }),
      ref("b", { superseded_by: "a" }),
    ]);
    expect(r.isValid).toBe(false);
    expect(r.errors.some((e) => e.includes("manifests[].temporal.superseded_by cycle"))).toBe(true);
  });
});
