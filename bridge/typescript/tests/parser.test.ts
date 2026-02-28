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

describe("parseFile", () => {
  it("parses the minimal fixture", () => {
    const manifest = parseFile(join(MINIMAL_DIR, "knowledge.yaml"));
    expect(manifest.project).toBe("my-project");
    expect(manifest.version).toBe("1.0.0");
    expect(manifest.kcp_version).toBe("0.3");
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
