import { describe, it, expect } from "vitest";
import { join } from "node:path";
import { parseDict, parseFile, validateUnitPath } from "../src/parser.js";

const MINIMAL_DIR = join(import.meta.dirname, "fixtures/minimal");
const FULL_DIR = join(import.meta.dirname, "fixtures/full");

describe("validateUnitPath", () => {
  it("accepts normal relative paths", () => {
    expect(validateUnitPath("README.md")).toBe("README.md");
    expect(validateUnitPath("docs/spec.md")).toBe("docs/spec.md");
    expect(validateUnitPath("a/b/c.yaml")).toBe("a/b/c.yaml");
  });

  it("rejects absolute paths", () => {
    expect(() => validateUnitPath("/etc/passwd")).toThrow("relative");
    expect(() => validateUnitPath("\\windows\\system32")).toThrow("relative");
  });

  it("rejects path traversal", () => {
    expect(() => validateUnitPath("../secret.txt")).toThrow("escapes");
    expect(() => validateUnitPath("docs/../../etc/passwd")).toThrow("escapes");
  });

  it("accepts paths with internal dots that resolve safely", () => {
    // a/./b.md normalizes to a/b.md — safe
    expect(validateUnitPath("a/./b.md")).toBe("a/./b.md");
  });
});

describe("parseDict", () => {
  it("parses a minimal manifest", () => {
    const manifest = parseDict({
      project: "test",
      version: "1.0.0",
      units: [
        {
          id: "overview",
          path: "README.md",
          intent: "What is this?",
          scope: "global",
          audience: ["human", "agent"],
        },
      ],
    });

    expect(manifest.project).toBe("test");
    expect(manifest.version).toBe("1.0.0");
    expect(manifest.units).toHaveLength(1);
    expect(manifest.units[0].id).toBe("overview");
    expect(manifest.relationships).toEqual([]);
  });

  it("defaults arrays to empty", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [
        {
          id: "u",
          path: "f.md",
          intent: "i",
          scope: "global",
          audience: ["human"],
        },
      ],
    });
    expect(manifest.units[0].depends_on).toEqual([]);
    expect(manifest.units[0].triggers).toEqual([]);
    expect(manifest.relationships).toEqual([]);
  });

  it("normalizes Date objects to ISO strings", () => {
    const d = new Date("2026-02-28T12:00:00Z");
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      updated: d,
      units: [
        {
          id: "u",
          path: "f.md",
          intent: "i",
          scope: "global",
          audience: ["agent"],
          validated: d,
        },
      ],
    });
    expect(manifest.updated).toBe("2026-02-28");
    expect(manifest.units[0].validated).toBe("2026-02-28");
  });

  it("handles relationships with from/to/type", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [],
      relationships: [{ from: "a", to: "b", type: "context" }],
    });
    expect(manifest.relationships[0]).toEqual({
      from_id: "a",
      to_id: "b",
      type: "context",
    });
  });
});

describe("parseDelegation", () => {
  it("parses root-level delegation block", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      delegation: {
        max_depth: 2,
        require_capability_attenuation: true,
        audit_chain: false,
        human_in_the_loop: "required",
      },
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.delegation).toBeDefined();
    expect(manifest.delegation?.max_depth).toBe(2);
    expect(manifest.delegation?.require_capability_attenuation).toBe(true);
    expect(manifest.delegation?.audit_chain).toBe(false);
    expect(manifest.delegation?.human_in_the_loop).toBe("required");
  });

  it("parses per-unit delegation override", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{
        id: "u",
        path: "f.md",
        intent: "i",
        scope: "global",
        audience: ["agent"],
        delegation: { max_depth: 0, human_in_the_loop: "recommended" },
      }],
    });
    const u = manifest.units[0];
    expect(u.delegation).toBeDefined();
    expect(u.delegation?.max_depth).toBe(0);
    expect(u.delegation?.human_in_the_loop).toBe("recommended");
    expect(u.delegation?.require_capability_attenuation).toBeUndefined();
  });

  it("absent delegation is undefined", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.delegation).toBeUndefined();
    expect(manifest.units[0].delegation).toBeUndefined();
  });
});

describe("parseCompliance", () => {
  it("parses root-level compliance block", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      compliance: {
        data_residency: ["EU", "NO"],
        sensitivity: "confidential",
        regulations: ["GDPR", "NIS2"],
        restrictions: ["no_ai_training", "no_cross_border"],
      },
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.compliance).toBeDefined();
    expect(manifest.compliance?.data_residency).toEqual(["EU", "NO"]);
    expect(manifest.compliance?.sensitivity).toBe("confidential");
    expect(manifest.compliance?.regulations).toEqual(["GDPR", "NIS2"]);
    expect(manifest.compliance?.restrictions).toEqual(["no_ai_training", "no_cross_border"]);
  });

  it("parses per-unit compliance override", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{
        id: "u",
        path: "f.md",
        intent: "i",
        scope: "global",
        audience: ["agent"],
        compliance: { sensitivity: "restricted", regulations: ["AML5D"] },
      }],
    });
    const u = manifest.units[0];
    expect(u.compliance).toBeDefined();
    expect(u.compliance?.sensitivity).toBe("restricted");
    expect(u.compliance?.regulations).toEqual(["AML5D"]);
    expect(u.compliance?.data_residency).toBeUndefined();
  });

  it("absent compliance is undefined", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.compliance).toBeUndefined();
    expect(manifest.units[0].compliance).toBeUndefined();
  });

  it("compliance with only sensitivity leaves other fields undefined", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      compliance: { sensitivity: "internal" },
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.compliance?.sensitivity).toBe("internal");
    expect(manifest.compliance?.data_residency).toBeUndefined();
    expect(manifest.compliance?.regulations).toBeUndefined();
    expect(manifest.compliance?.restrictions).toBeUndefined();
  });
});

describe("parseFile", () => {
  it("parses the minimal fixture", () => {
    const manifest = parseFile(join(MINIMAL_DIR, "knowledge.yaml"));
    expect(manifest.project).toBe("my-project");
    expect(manifest.version).toBe("1.0.0");
    expect(manifest.kcp_version).toBe("0.6");
    expect(manifest.units).toHaveLength(1);
    expect(manifest.units[0].id).toBe("overview");
    expect(manifest.units[0].audience).toContain("agent");
  });

  it("parses the full fixture", () => {
    const manifest = parseFile(join(FULL_DIR, "knowledge.yaml"));
    expect(manifest.project).toBe("full-example");
    expect(manifest.units).toHaveLength(3);
    expect(manifest.relationships).toHaveLength(2);

    const spec = manifest.units.find((u) => u.id === "spec");
    expect(spec?.validated).toBe("2026-02-27");
    expect(spec?.triggers).toEqual(["spec", "rules", "normative"]);

    const api = manifest.units.find((u) => u.id === "api-schema");
    expect(api?.content_type).toBe("application/schema+json");
    expect(api?.depends_on).toEqual(["spec"]);
  });
});
