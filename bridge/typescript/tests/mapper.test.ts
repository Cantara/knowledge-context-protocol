import { describe, it, expect } from "vitest";
import {
  toProjectSlug,
  buildUnitUri,
  buildManifestUri,
  resolveMimeType,
  isBinaryMime,
  mapAudience,
  buildDescription,
  buildUnitResource,
  buildManifestResource,
  scopePriority,
  manifestToJson,
} from "../src/mapper.js";
import { parseDict } from "../src/parser.js";
import type { KnowledgeUnit } from "../src/model.js";

function makeUnit(overrides: Partial<KnowledgeUnit> = {}): KnowledgeUnit {
  return {
    id: "spec",
    path: "SPEC.md",
    intent: "What are the rules?",
    scope: "global",
    audience: ["human", "agent"],
    depends_on: [],
    triggers: [],
    ...overrides,
  };
}

describe("toProjectSlug", () => {
  it("lowercases and replaces spaces with hyphens", () => {
    expect(toProjectSlug("My Project")).toBe("my-project");
  });

  it("collapses multiple special chars", () => {
    expect(toProjectSlug("Knowledge Context Protocol")).toBe(
      "knowledge-context-protocol"
    );
  });

  it("handles already-slug-like names", () => {
    expect(toProjectSlug("kcp")).toBe("kcp");
    expect(toProjectSlug("my-project")).toBe("my-project");
  });
});

describe("buildUnitUri / buildManifestUri", () => {
  it("builds correct unit URI", () => {
    expect(buildUnitUri("my-project", "spec")).toBe(
      "knowledge://my-project/spec"
    );
  });

  it("builds correct manifest URI", () => {
    expect(buildManifestUri("my-project")).toBe(
      "knowledge://my-project/manifest"
    );
  });
});

describe("resolveMimeType", () => {
  it("uses content_type when present", () => {
    const unit = makeUnit({ content_type: "application/schema+json" });
    expect(resolveMimeType(unit)).toBe("application/schema+json");
  });

  it("uses format lookup when no content_type", () => {
    const unit = makeUnit({ format: "openapi" });
    expect(resolveMimeType(unit)).toBe("application/vnd.oai.openapi+yaml");
  });

  it("falls back to file extension", () => {
    const unit = makeUnit({ path: "docs/guide.md" });
    expect(resolveMimeType(unit)).toBe("text/markdown");
  });

  it("falls back to text/plain for unknown extension", () => {
    const unit = makeUnit({ path: "somefile.xyz" });
    expect(resolveMimeType(unit)).toBe("text/plain");
  });

  it("content_type wins over format", () => {
    const unit = makeUnit({
      content_type: "application/schema+json",
      format: "markdown",
    });
    expect(resolveMimeType(unit)).toBe("application/schema+json");
  });
});

describe("isBinaryMime", () => {
  it("identifies PDF as binary", () => {
    expect(isBinaryMime("application/pdf")).toBe(true);
  });

  it("identifies images as binary", () => {
    expect(isBinaryMime("image/png")).toBe(true);
  });

  it("identifies text/markdown as non-binary", () => {
    expect(isBinaryMime("text/markdown")).toBe(false);
  });

  it("identifies application/json as non-binary", () => {
    expect(isBinaryMime("application/json")).toBe(false);
  });
});

describe("mapAudience", () => {
  it("maps agent to assistant", () => {
    expect(mapAudience(["agent"])).toContain("assistant");
  });

  it("maps human to user", () => {
    expect(mapAudience(["human"])).toContain("user");
  });

  it("maps mixed audience to both", () => {
    const result = mapAudience(["human", "agent"]);
    expect(result).toContain("user");
    expect(result).toContain("assistant");
  });

  it("defaults to user for empty audience", () => {
    expect(mapAudience([])).toEqual(["user"]);
  });

  it("maps developer and architect to user", () => {
    expect(mapAudience(["developer"])).toContain("user");
    expect(mapAudience(["architect"])).toContain("user");
  });
});

describe("scopePriority", () => {
  it("global = 1.0", () => expect(scopePriority("global")).toBe(1.0));
  it("project = 0.7", () => expect(scopePriority("project")).toBe(0.7));
  it("module = 0.5", () => expect(scopePriority("module")).toBe(0.5));
  it("unknown defaults to 0.5", () =>
    expect(scopePriority("unknown")).toBe(0.5));
});

describe("buildDescription", () => {
  it("includes intent as the first line", () => {
    const unit = makeUnit({ intent: "What is this project?" });
    expect(buildDescription(unit).startsWith("What is this project?")).toBe(
      true
    );
  });

  it("includes audience and scope", () => {
    const desc = buildDescription(makeUnit());
    expect(desc).toContain("Audience: human, agent");
    expect(desc).toContain("Scope: global");
  });

  it("includes triggers when present", () => {
    const unit = makeUnit({ triggers: ["spec", "rules"] });
    expect(buildDescription(unit)).toContain("Triggers: spec, rules");
  });

  it("includes depends_on when present", () => {
    const unit = makeUnit({ depends_on: ["overview"] });
    expect(buildDescription(unit)).toContain("Depends on: overview");
  });

  it("omits optional fields when absent", () => {
    const desc = buildDescription(makeUnit());
    expect(desc).not.toContain("Validated:");
    expect(desc).not.toContain("Depends on:");
    expect(desc).not.toContain("Triggers:");
  });
});

describe("buildUnitResource", () => {
  it("returns null when agentOnly and no agent audience", () => {
    const unit = makeUnit({ audience: ["human"] });
    expect(buildUnitResource(unit, "slug", true)).toBeNull();
  });

  it("returns resource when agentOnly and agent in audience", () => {
    const unit = makeUnit({ audience: ["agent"] });
    const r = buildUnitResource(unit, "slug", true);
    expect(r).not.toBeNull();
    expect(r!.uri).toBe("knowledge://slug/spec");
  });

  it("returns resource for human-only unit when agentOnly=false", () => {
    const unit = makeUnit({ audience: ["human"] });
    const r = buildUnitResource(unit, "slug", false);
    expect(r).not.toBeNull();
  });

  it("truncates long intent in title", () => {
    const longIntent = "A".repeat(90);
    const unit = makeUnit({ intent: longIntent });
    const r = buildUnitResource(unit, "slug")!;
    expect(r.title.length).toBeLessThanOrEqual(80);
    expect(r.title.endsWith("...")).toBe(true);
  });

  it("sets lastModified from validated", () => {
    const unit = makeUnit({ validated: "2026-02-27" });
    const r = buildUnitResource(unit, "slug")!;
    expect(r.annotations.lastModified).toBe("2026-02-27T00:00:00Z");
  });

  it("sets priority from scope", () => {
    const r = buildUnitResource(makeUnit({ scope: "module" }), "slug")!;
    expect(r.annotations.priority).toBe(0.5);
  });
});

describe("buildManifestResource", () => {
  it("always returns a resource with name=manifest", () => {
    const manifest = parseDict({
      project: "test",
      version: "1.0.0",
      units: [],
    });
    const r = buildManifestResource(manifest, "test");
    expect(r.name).toBe("manifest");
    expect(r.uri).toBe("knowledge://test/manifest");
    expect(r.mimeType).toBe("application/json");
    expect(r.annotations.priority).toBe(1.0);
  });
});

describe("manifestToJson", () => {
  it("serializes units with correct fields", () => {
    const manifest = parseDict({
      project: "test",
      version: "1.0.0",
      units: [
        {
          id: "spec",
          path: "SPEC.md",
          intent: "What is this?",
          scope: "global",
          audience: ["agent"],
          validated: "2026-02-27",
          triggers: ["spec"],
        },
      ],
      relationships: [{ from: "spec", to: "spec", type: "context" }],
    });
    const json = JSON.parse(manifestToJson(manifest, "test"));
    expect(json.project).toBe("test");
    expect(json.units[0].id).toBe("spec");
    expect(json.units[0].validated).toBe("2026-02-27");
    expect(json.relationships[0].from).toBe("spec");
  });
});
