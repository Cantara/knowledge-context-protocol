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
  mapAuthority,
  mapDiscovery,
  mapVisibility,
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

describe("mapAuthority", () => {
  it("maps all known action fields", () => {
    const result = mapAuthority({
      read: "initiative",
      summarize: "initiative",
      modify: "requires_approval",
      share_externally: "denied",
      execute: "denied",
    });
    expect(result["read"]).toBe("initiative");
    expect(result["summarize"]).toBe("initiative");
    expect(result["modify"]).toBe("requires_approval");
    expect(result["share_externally"]).toBe("denied");
    expect(result["execute"]).toBe("denied");
  });

  it("maps custom action fields", () => {
    const result = mapAuthority({ read: "initiative", export_pdf: "requires_approval" });
    expect(result["export_pdf"]).toBe("requires_approval");
  });

  it("omits undefined values", () => {
    const result = mapAuthority({ read: "initiative", modify: undefined });
    expect(Object.keys(result)).toEqual(["read"]);
  });
});

describe("mapDiscovery", () => {
  it("maps a fully populated discovery block", () => {
    const result = mapDiscovery({
      verification_status: "observed",
      source: "web_traversal",
      observed_at: "2026-03-01T10:00:00Z",
      verified_at: "2026-03-10T00:00:00Z",
      confidence: 0.72,
      contradicted_by: "other-unit",
    });
    expect(result["verification_status"]).toBe("observed");
    expect(result["source"]).toBe("web_traversal");
    expect(result["observed_at"]).toBe("2026-03-01T10:00:00Z");
    expect(result["verified_at"]).toBe("2026-03-10T00:00:00Z");
    expect(result["confidence"]).toBe(0.72);
    expect(result["contradicted_by"]).toBe("other-unit");
  });

  it("omits absent optional fields", () => {
    const result = mapDiscovery({
      verification_status: "observed",
      confidence: 0.72,
    });
    expect(Object.keys(result)).toEqual(["verification_status", "confidence"]);
  });
});

describe("mapVisibility", () => {
  it("maps a visibility block with default and conditions", () => {
    const result = mapVisibility({
      default: "internal",
      conditions: [
        {
          when: { environment: "production", agent_role: "auditor" },
          then: { sensitivity: "confidential", requires_auth: true },
        },
      ],
    });
    expect(result["default"]).toBe("internal");
    const conditions = result["conditions"] as Array<Record<string, unknown>>;
    expect(conditions).toHaveLength(1);
    const when = conditions[0]["when"] as Record<string, unknown>;
    expect(when["environment"]).toBe("production");
    expect(when["agent_role"]).toBe("auditor");
    const then = conditions[0]["then"] as Record<string, unknown>;
    expect(then["sensitivity"]).toBe("confidential");
    expect(then["requires_auth"]).toBe(true);
  });

  it("maps nested authority within a visibility condition then block", () => {
    const result = mapVisibility({
      conditions: [
        {
          when: { environment: "production" },
          then: { authority: { read: "initiative", modify: "denied" } },
        },
      ],
    });
    const conditions = result["conditions"] as Array<Record<string, unknown>>;
    const then = conditions[0]["then"] as Record<string, unknown>;
    const authority = then["authority"] as Record<string, string>;
    expect(authority["read"]).toBe("initiative");
    expect(authority["modify"]).toBe("denied");
  });

  it("omits conditions key when conditions array is empty", () => {
    const result = mapVisibility({ default: "public", conditions: [] });
    expect(Object.keys(result)).toEqual(["default"]);
  });
});

describe("manifestToJson — authority, discovery, visibility in unit output", () => {
  it("includes authority in unit JSON output", () => {
    const manifest = parseDict({
      project: "test",
      version: "1.0.0",
      units: [
        {
          id: "u",
          path: "f.md",
          intent: "i",
          scope: "global",
          audience: ["agent"],
          authority: { read: "initiative", modify: "requires_approval" },
        },
      ],
    });
    const json = JSON.parse(manifestToJson(manifest, "test"));
    expect(json.units[0].authority).toBeDefined();
    expect(json.units[0].authority.read).toBe("initiative");
    expect(json.units[0].authority.modify).toBe("requires_approval");
  });

  it("includes discovery in unit JSON output", () => {
    const manifest = parseDict({
      project: "test",
      version: "1.0.0",
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
            confidence: 0.72,
          },
        },
      ],
    });
    const json = JSON.parse(manifestToJson(manifest, "test"));
    expect(json.units[0].discovery).toBeDefined();
    expect(json.units[0].discovery.verification_status).toBe("observed");
    expect(json.units[0].discovery.confidence).toBe(0.72);
  });

  it("includes visibility in unit JSON output", () => {
    const manifest = parseDict({
      project: "test",
      version: "1.0.0",
      units: [
        {
          id: "u",
          path: "f.md",
          intent: "i",
          scope: "global",
          audience: ["agent"],
          visibility: {
            default: "internal",
            conditions: [
              {
                when: { environment: "production" },
                then: { sensitivity: "confidential" },
              },
            ],
          },
        },
      ],
    });
    const json = JSON.parse(manifestToJson(manifest, "test"));
    expect(json.units[0].visibility).toBeDefined();
    expect(json.units[0].visibility.default).toBe("internal");
    expect(json.units[0].visibility.conditions[0].when.environment).toBe("production");
  });

  it("includes authority and discovery at manifest level in JSON output", () => {
    const manifest = parseDict({
      project: "test",
      version: "1.0.0",
      authority: { read: "initiative", modify: "denied" },
      discovery: { verification_status: "verified", confidence: 1.0 },
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    const json = JSON.parse(manifestToJson(manifest, "test"));
    expect(json.authority.read).toBe("initiative");
    expect(json.discovery.verification_status).toBe("verified");
    expect(json.discovery.confidence).toBe(1.0);
  });

  it("omits authority, discovery, visibility when absent", () => {
    const manifest = parseDict({
      project: "test",
      version: "1.0.0",
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    const json = JSON.parse(manifestToJson(manifest, "test"));
    expect(json.units[0].authority).toBeUndefined();
    expect(json.units[0].discovery).toBeUndefined();
    expect(json.units[0].visibility).toBeUndefined();
    expect(json.authority).toBeUndefined();
    expect(json.discovery).toBeUndefined();
  });
});

describe("manifestToJson — root authority model (§3.13, RFC-0025 v0.27)", () => {
  // The bridge PARSED authority_level_scale / task_types / agents / grant_ceiling
  // (parser.ts §765, model.ts §386-389) but the mapper root projection omitted all
  // four — so the entire root authority model was invisible over MCP. A consumer
  // could not see the ordinal scale, the declared ceilings, or the multi-source
  // minimum computation that governs whether a procedure may act. Built through
  // parseDict so this covers parse -> map, not just map.
  function authorityManifest() {
    return parseDict({
      project: "example",
      version: "1.0.0",
      kcp_version: "0.27",
      authority_level_scale: ["observe", "explain", "suggest", "prepare", "commit"],
      task_types: [
        { id: "change-status", intent: "flip a status flag", authority_level: "explain" },
      ],
      agents: [{ id: "lara", name: "LARA", authority_level: "prepare" }],
      grant_ceiling: {
        sources: [
          { id: "org-risk", authority_level: "prepare" },
          { id: "task-ceiling", task_type_ref: "change-status" },
          { id: "agent-ceiling", agent_ref: "lara" },
        ],
        mandatory_sources: ["org-risk"],
      },
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
  }

  it("includes authority_level_scale at manifest root", () => {
    const json = JSON.parse(manifestToJson(authorityManifest(), "example"));
    expect(json.authority_level_scale).toEqual([
      "observe",
      "explain",
      "suggest",
      "prepare",
      "commit",
    ]);
  });

  it("includes task_types at manifest root", () => {
    const json = JSON.parse(manifestToJson(authorityManifest(), "example"));
    expect(json.task_types).toHaveLength(1);
    expect(json.task_types[0].id).toBe("change-status");
    expect(json.task_types[0].authority_level).toBe("explain");
  });

  it("includes agents at manifest root", () => {
    const json = JSON.parse(manifestToJson(authorityManifest(), "example"));
    expect(json.agents).toHaveLength(1);
    expect(json.agents[0].id).toBe("lara");
    expect(json.agents[0].authority_level).toBe("prepare");
  });

  it("includes grant_ceiling (sources + mandatory_sources) at manifest root", () => {
    const json = JSON.parse(manifestToJson(authorityManifest(), "example"));
    expect(json.grant_ceiling).toBeDefined();
    expect(json.grant_ceiling.sources).toHaveLength(3);
    expect(json.grant_ceiling.sources.map((s: { id: string }) => s.id)).toEqual([
      "org-risk",
      "task-ceiling",
      "agent-ceiling",
    ]);
    expect(json.grant_ceiling.mandatory_sources).toEqual(["org-risk"]);
  });

  it("omits root authority fields when the manifest declares none", () => {
    const manifest = parseDict({
      project: "example",
      version: "1.0.0",
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    const json = JSON.parse(manifestToJson(manifest, "example"));
    expect(json.authority_level_scale).toBeUndefined();
    expect(json.task_types).toBeUndefined();
    expect(json.agents).toBeUndefined();
    expect(json.grant_ceiling).toBeUndefined();
  });
});

describe("manifestToJson — action_scope (§4.3a)", () => {
  // The bridge parsed action_scope but never surfaced it: mapper's entry builder
  // copied `kind` and skipped the envelope that gives `kind: skill` its meaning.
  // A consumer reading the manifest over MCP saw a skill with no declared scope —
  // indistinguishable from one that declares none, which per §4.3a authorizes
  // nothing. Built through parseDict so this covers parse -> map, not just map.
  function skillManifest(actionScope: unknown) {
    return parseDict({
      project: "example",
      version: "1.0.0",
      kcp_version: "0.28",
      units: [
        {
          id: "rotate-key",
          path: "skills/rotate-key.md",
          intent: "How do I rotate the signing key?",
          scope: "project",
          audience: ["agent"],
          kind: "skill",
          ...(actionScope === undefined ? {} : { action_scope: actionScope }),
        },
      ],
      relationships: [],
    });
  }

  const FULL = {
    tools: ["kcp-sign", "git"],
    paths: ["schema/**"],
    capabilities: ["key-management"],
    spend: { max_spend: 2, currency: "USD", allowed_vendors: ["registry.example.com"] },
  };

  it("surfaces action_scope on the unit entry", () => {
    const out = JSON.parse(manifestToJson(skillManifest(FULL), "example"));
    expect(out.units[0].action_scope).toBeDefined();
    expect(out.units[0].action_scope.tools).toEqual(["kcp-sign", "git"]);
    expect(out.units[0].action_scope.paths).toEqual(["schema/**"]);
  });

  it("passes action_scope through opaquely, keeping sub-fields it does not model", () => {
    // §4.3a: action_scope is an opaque passthrough object. Rebuilding it field by
    // field — as `delegation` and `compliance` are handled — would silently drop
    // `spend`, and every future sub-field, at the bridge boundary.
    const out = JSON.parse(manifestToJson(skillManifest(FULL), "example"));
    expect(out.units[0].action_scope.spend).toEqual({
      max_spend: 2,
      currency: "USD",
      allowed_vendors: ["registry.example.com"],
    });
  });

  it("omits action_scope entirely when the unit declares none", () => {
    const out = JSON.parse(manifestToJson(skillManifest(undefined), "example"));
    expect("action_scope" in out.units[0]).toBe(false);
  });
});
