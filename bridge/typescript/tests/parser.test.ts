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

describe("parseTrust", () => {
  it("parses root-level trust block", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      trust: {
        provenance: {
          publisher: "Acme Corp",
          publisher_url: "https://acme.com",
          contact: "docs@acme.com",
        },
        audit: {
          agent_must_log: true,
          require_trace_context: false,
        },
      },
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.trust).toBeDefined();
    expect(manifest.trust?.provenance?.publisher).toBe("Acme Corp");
    expect(manifest.trust?.provenance?.publisher_url).toBe("https://acme.com");
    expect(manifest.trust?.provenance?.contact).toBe("docs@acme.com");
    expect(manifest.trust?.audit?.agent_must_log).toBe(true);
    expect(manifest.trust?.audit?.require_trace_context).toBe(false);
  });

  it("absent trust is undefined", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.trust).toBeUndefined();
  });
});

describe("parseAuth", () => {
  it("parses root-level auth block with multiple methods", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      auth: {
        methods: [
          { type: "oauth2", issuer: "https://auth.example.com", scopes: ["read:docs"] },
          { type: "api_key", header: "X-API-Key", registration_url: "https://example.com/register" },
          { type: "none" },
        ],
      },
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.auth).toBeDefined();
    expect(manifest.auth?.methods).toHaveLength(3);
    expect(manifest.auth?.methods[0].type).toBe("oauth2");
    expect(manifest.auth?.methods[0].issuer).toBe("https://auth.example.com");
    expect(manifest.auth?.methods[0].scopes).toEqual(["read:docs"]);
    expect(manifest.auth?.methods[1].type).toBe("api_key");
    expect(manifest.auth?.methods[1].header).toBe("X-API-Key");
    expect(manifest.auth?.methods[2].type).toBe("none");
  });

  it("absent auth is undefined", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.auth).toBeUndefined();
  });
});

describe("parseHints", () => {
  it("parses unit-level hints block", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{
        id: "u",
        path: "f.md",
        intent: "i",
        scope: "global",
        audience: ["agent"],
        hints: {
          token_estimate: 5000,
          load_strategy: "lazy",
          summary_available: true,
          summary_unit: "overview-tldr",
        },
      }],
    });
    expect(manifest.units[0].hints).toBeDefined();
    expect(manifest.units[0].hints?.token_estimate).toBe(5000);
    expect(manifest.units[0].hints?.load_strategy).toBe("lazy");
    expect(manifest.units[0].hints?.summary_available).toBe(true);
  });

  it("parses root-level hints block", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      hints: { total_token_estimate: 50000, unit_count: 5 },
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.hints).toBeDefined();
    expect(manifest.hints?.total_token_estimate).toBe(50000);
  });
});

describe("parsePayment", () => {
  it("parses root-level payment block", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      payment: { default_tier: "free" },
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.payment).toBeDefined();
  });

  it("parses unit-level payment block", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{
        id: "u",
        path: "f.md",
        intent: "i",
        scope: "global",
        audience: ["agent"],
        payment: { default_tier: "metered" },
      }],
    });
    expect(manifest.units[0].payment).toBeDefined();
  });

  it("absent payment is undefined", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.payment).toBeUndefined();
  });
});

describe("path traversal (#12)", () => {
  it("rejects ../secret", () => {
    expect(() => validateUnitPath("../secret")).toThrow("escapes");
  });

  it("rejects ../../etc/passwd", () => {
    expect(() => validateUnitPath("../../etc/passwd")).toThrow("escapes");
  });

  it("rejects absolute /etc/passwd", () => {
    expect(() => validateUnitPath("/etc/passwd")).toThrow("relative");
  });

  it("rejects docs/../../etc/shadow", () => {
    expect(() => validateUnitPath("docs/../../etc/shadow")).toThrow("escapes");
  });

  it("accepts safe nested path", () => {
    expect(validateUnitPath("docs/guide/intro.md")).toBe("docs/guide/intro.md");
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
