import { describe, it, expect } from "vitest";
import { join } from "node:path";
import { mkdtempSync, symlinkSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { createKcpServer } from "../src/server.js";
import { loadCommandManifests } from "../src/commands.js";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";

const FULL_DIR = join(import.meta.dirname, "fixtures/full");
const MINIMAL_DIR = join(import.meta.dirname, "fixtures/minimal");
const COMMANDS_DIR = join(import.meta.dirname, "fixtures/commands");
const FEDERATION_DIR = join(import.meta.dirname, "fixtures/federation");

async function connectClient(
  manifestPath: string,
  options: {
    agentOnly?: boolean;
    commandManifests?: Map<string, import("../src/commands.js").CommandManifest>;
  } = {}
) {
  const { server } = createKcpServer(manifestPath, {
    agentOnly: options.agentOnly,
    warnOnValidation: false,
    commandManifests: options.commandManifests,
  });

  const [clientTransport, serverTransport] =
    InMemoryTransport.createLinkedPair();
  await server.connect(serverTransport);

  const client = new Client(
    { name: "test-client", version: "0.1.0" },
    { capabilities: {} }
  );
  await client.connect(clientTransport);

  return client;
}

describe("tools/list", () => {
  it("lists all four tools", async () => {
    const client = await connectClient(join(FULL_DIR, "knowledge.yaml"));
    const { tools } = await client.listTools();

    expect(tools).toHaveLength(4);
    const names = tools.map((t) => t.name);
    expect(names).toContain("search_knowledge");
    expect(names).toContain("get_unit");
    expect(names).toContain("get_command_syntax");
    expect(names).toContain("list_manifests");
    await client.close();
  });

  it("each tool has an inputSchema with required fields", async () => {
    const client = await connectClient(join(FULL_DIR, "knowledge.yaml"));
    const { tools } = await client.listTools();

    for (const tool of tools) {
      expect(tool.inputSchema).toBeDefined();
      expect(tool.inputSchema.type).toBe("object");
      expect(tool.inputSchema.required).toBeDefined();
    }
    await client.close();
  });
});

describe("search_knowledge tool", () => {
  it("returns matching units scored by triggers", async () => {
    const client = await connectClient(join(FULL_DIR, "knowledge.yaml"));
    const result = await client.callTool({
      name: "search_knowledge",
      arguments: { query: "spec rules" },
    });

    const text = (result.content as Array<{ type: string; text: string }>)[0]
      .text;
    const results = JSON.parse(text);
    expect(results).toBeInstanceOf(Array);
    expect(results.length).toBeGreaterThan(0);

    // "spec" unit should score highest (has "spec" and "rules" in triggers)
    expect(results[0].id).toBe("spec");
    expect(results[0].score).toBeGreaterThan(0);
    expect(results[0].uri).toMatch(/^knowledge:\/\//);
    await client.close();
  });

  it("returns helpful message when no units match", async () => {
    const client = await connectClient(join(FULL_DIR, "knowledge.yaml"));
    const result = await client.callTool({
      name: "search_knowledge",
      arguments: { query: "zzz-nonexistent-zzz" },
    });

    const text = (result.content as Array<{ type: string; text: string }>)[0]
      .text;
    expect(text).toContain("No units matched");
    expect(text).toContain("Available units:");
    await client.close();
  });

  it("filters by audience", async () => {
    const client = await connectClient(join(FULL_DIR, "knowledge.yaml"));
    // "guide" has audience [human, developer] — NOT agent
    // search for "guide" but filter to agent only
    const result = await client.callTool({
      name: "search_knowledge",
      arguments: { query: "guide integration", audience: "agent" },
    });

    const text = (result.content as Array<{ type: string; text: string }>)[0]
      .text;
    // Either no results or "guide" should not appear
    if (text.startsWith("[")) {
      const results = JSON.parse(text);
      const ids = results.map((r: { id: string }) => r.id);
      expect(ids).not.toContain("guide");
    }
    await client.close();
  });

  it("filters by scope", async () => {
    const client = await connectClient(join(FULL_DIR, "knowledge.yaml"));
    // "api-schema" is scope=module, "spec" is scope=global
    const result = await client.callTool({
      name: "search_knowledge",
      arguments: { query: "schema", scope: "module" },
    });

    const text = (result.content as Array<{ type: string; text: string }>)[0]
      .text;
    const results = JSON.parse(text);
    // All results should be module scope
    for (const r of results) {
      // The result doesn't include scope in the output, but
      // only module-scope units should match through the filter
      expect(r.id).not.toBe("spec"); // spec is global
    }
    await client.close();
  });

  it("returns top-5 results max", async () => {
    const client = await connectClient(join(FULL_DIR, "knowledge.yaml"));
    // A broad query that matches many things
    const result = await client.callTool({
      name: "search_knowledge",
      arguments: { query: "the" },
    });

    const text = (result.content as Array<{ type: string; text: string }>)[0]
      .text;
    if (text.startsWith("[")) {
      const results = JSON.parse(text);
      expect(results.length).toBeLessThanOrEqual(5);
    }
    await client.close();
  });

  // ── RFC-0007 query baseline ─────────────────────────────────────────────

  const RFC007_DIR = join(import.meta.dirname, "fixtures/rfc007");

  it("returns match_reason in results", async () => {
    const client = await connectClient(join(RFC007_DIR, "knowledge.yaml"));
    const result = await client.callTool({
      name: "search_knowledge",
      arguments: { query: "authentication" },
    });
    const text = (result.content as Array<{ type: string; text: string }>)[0].text;
    const results = JSON.parse(text);
    expect(results[0].match_reason).toBeInstanceOf(Array);
    expect(results[0].match_reason).toContain("trigger");
    await client.close();
  });

  it("returns token_estimate and summary_unit from hints", async () => {
    const client = await connectClient(join(RFC007_DIR, "knowledge.yaml"));
    const result = await client.callTool({
      name: "search_knowledge",
      arguments: { query: "authentication" },
    });
    const text = (result.content as Array<{ type: string; text: string }>)[0].text;
    const results = JSON.parse(text);
    const authGuide = results.find((r: { id: string }) => r.id === "auth-guide");
    expect(authGuide).toBeDefined();
    expect(authGuide.token_estimate).toBe(4200);
    expect(authGuide.summary_unit).toBe("auth-tldr");
    await client.close();
  });

  it("excludes deprecated units by default", async () => {
    const client = await connectClient(join(RFC007_DIR, "knowledge.yaml"));
    const result = await client.callTool({
      name: "search_knowledge",
      arguments: { query: "api endpoints legacy" },
    });
    const text = (result.content as Array<{ type: string; text: string }>)[0].text;
    if (text.startsWith("[")) {
      const results = JSON.parse(text);
      const ids = results.map((r: { id: string }) => r.id);
      expect(ids).not.toContain("old-api");
    }
    await client.close();
  });

  it("includes deprecated units when exclude_deprecated is false", async () => {
    const client = await connectClient(join(RFC007_DIR, "knowledge.yaml"));
    const result = await client.callTool({
      name: "search_knowledge",
      arguments: { query: "api endpoints legacy", exclude_deprecated: false },
    });
    const text = (result.content as Array<{ type: string; text: string }>)[0].text;
    const results = JSON.parse(text);
    const ids = results.map((r: { id: string }) => r.id);
    expect(ids).toContain("old-api");
    await client.close();
  });

  it("filters by sensitivity_max", async () => {
    const client = await connectClient(join(RFC007_DIR, "knowledge.yaml"));
    // sensitivity_max: internal — should exclude confidential unit
    const result = await client.callTool({
      name: "search_knowledge",
      arguments: { query: "config secrets", sensitivity_max: "internal" },
    });
    const text = (result.content as Array<{ type: string; text: string }>)[0].text;
    if (text.startsWith("[")) {
      const results = JSON.parse(text);
      const ids = results.map((r: { id: string }) => r.id);
      expect(ids).not.toContain("secret-config");
    }
    await client.close();
  });

  it("includes confidential units when sensitivity_max is confidential", async () => {
    const client = await connectClient(join(RFC007_DIR, "knowledge.yaml"));
    const result = await client.callTool({
      name: "search_knowledge",
      arguments: { query: "config secrets credentials", sensitivity_max: "confidential" },
    });
    const text = (result.content as Array<{ type: string; text: string }>)[0].text;
    const results = JSON.parse(text);
    const ids = results.map((r: { id: string }) => r.id);
    expect(ids).toContain("secret-config");
    await client.close();
  });

  // ── §15.11 not_for filtering ─────────────────────────────────────────────

  it("soft-demotes units whose not_for phrase matches a query term (§15.11)", async () => {
    const client = await connectClient(join(RFC007_DIR, "knowledge.yaml"));
    // "configure" hits admin-console trigger → score 5; "end" matches "end user" → halved + caution
    const result = await client.callTool({
      name: "search_knowledge",
      arguments: { query: "configure end" },
    });
    const text = (result.content as Array<{ type: string; text: string }>)[0].text;
    const results = JSON.parse(text);
    const adminConsole = results.find((r: { id: string }) => r.id === "admin-console");
    expect(adminConsole).toBeDefined();
    expect(adminConsole.caution).toMatch(/not_for match/);
    await client.close();
  });

  it("strictly excludes units with not_for_strict when query term matches (§15.11)", async () => {
    const client = await connectClient(join(RFC007_DIR, "knowledge.yaml"));
    // "operations" hits internal-ops trigger → 5 pts; "external" matches not_for strict → excluded
    const result = await client.callTool({
      name: "search_knowledge",
      arguments: { query: "operations external" },
    });
    const text = (result.content as Array<{ type: string; text: string }>)[0].text;
    if (text.startsWith("[")) {
      const results = JSON.parse(text);
      const ids = results.map((r: { id: string }) => r.id);
      expect(ids).not.toContain("internal-ops");
    }
    await client.close();
  });

  // ── §15.13 temporal query filtering ──────────────────────────────────────

  it("excludes future and expired units by default temporal filter (§15.13)", async () => {
    const client = await connectClient(join(RFC007_DIR, "knowledge.yaml"));
    // "future" matches future-feature trigger (valid_from: 2099) → excluded by default temporal
    // "legacy" matches legacy-guide trigger (valid_until: 2020) → excluded by default temporal
    const result = await client.callTool({
      name: "search_knowledge",
      arguments: { query: "future legacy integration" },
    });
    const text = (result.content as Array<{ type: string; text: string }>)[0].text;
    if (text.startsWith("[")) {
      const results = JSON.parse(text);
      const ids = results.map((r: { id: string }) => r.id);
      expect(ids).not.toContain("future-feature");
      expect(ids).not.toContain("legacy-guide");
    }
    await client.close();
  });

  it("includes temporally inactive units when include_all_temporal is true (§15.13)", async () => {
    const client = await connectClient(join(RFC007_DIR, "knowledge.yaml"));
    const result = await client.callTool({
      name: "search_knowledge",
      arguments: { query: "future feature upcoming", include_all_temporal: true },
    });
    const text = (result.content as Array<{ type: string; text: string }>)[0].text;
    const results = JSON.parse(text);
    const ids = results.map((r: { id: string }) => r.id);
    expect(ids).toContain("future-feature");
    await client.close();
  });
});

describe("get_unit tool", () => {
  it("returns text content for a valid unit", async () => {
    const client = await connectClient(join(MINIMAL_DIR, "knowledge.yaml"));
    const result = await client.callTool({
      name: "get_unit",
      arguments: { unit_id: "overview" },
    });

    const text = (result.content as Array<{ type: string; text: string }>)[0]
      .text;
    expect(text).toContain("My Project");
    expect(result.isError).not.toBe(true);
    await client.close();
  });

  it("returns error for unknown unit id", async () => {
    const client = await connectClient(join(MINIMAL_DIR, "knowledge.yaml"));
    const result = await client.callTool({
      name: "get_unit",
      arguments: { unit_id: "nonexistent" },
    });

    const text = (result.content as Array<{ type: string; text: string }>)[0]
      .text;
    expect(text).toContain("Unit not found");
    expect(text).toContain("Available units:");
    expect(result.isError).toBe(true);
    await client.close();
  });
});

describe("get_command_syntax tool", () => {
  it("returns formatted syntax block for known command", async () => {
    const commandManifests = loadCommandManifests(COMMANDS_DIR);
    const client = await connectClient(join(MINIMAL_DIR, "knowledge.yaml"), {
      commandManifests,
    });

    const result = await client.callTool({
      name: "get_command_syntax",
      arguments: { command: "git commit" },
    });

    const text = (result.content as Array<{ type: string; text: string }>)[0]
      .text;
    expect(text).toContain("[kcp] git commit:");
    expect(text).toContain("Usage:");
    expect(text).toContain("Key flags:");
    expect(result.isError).not.toBe(true);
    await client.close();
  });

  it("returns error when no command manifests loaded", async () => {
    const client = await connectClient(join(MINIMAL_DIR, "knowledge.yaml"));

    const result = await client.callTool({
      name: "get_command_syntax",
      arguments: { command: "git" },
    });

    const text = (result.content as Array<{ type: string; text: string }>)[0]
      .text;
    expect(text).toContain("No command manifests loaded");
    expect(text).toContain("--commands-dir");
    expect(result.isError).toBe(true);
    await client.close();
  });

  it("returns error for unknown command with list of available", async () => {
    const commandManifests = loadCommandManifests(COMMANDS_DIR);
    const client = await connectClient(join(MINIMAL_DIR, "knowledge.yaml"), {
      commandManifests,
    });

    const result = await client.callTool({
      name: "get_command_syntax",
      arguments: { command: "kubectl" },
    });

    const text = (result.content as Array<{ type: string; text: string }>)[0]
      .text;
    expect(text).toContain("Unknown command");
    expect(text).toContain("Available commands:");
    expect(result.isError).toBe(true);
    await client.close();
  });
});

describe("list_manifests tool", () => {
  it("returns empty array when manifest has no federation block", async () => {
    const client = await connectClient(join(MINIMAL_DIR, "knowledge.yaml"));
    const result = await client.callTool({
      name: "list_manifests",
      arguments: {},
    });

    const text = (result.content as Array<{ type: string; text: string }>)[0]
      .text;
    const entries = JSON.parse(text);
    expect(entries).toEqual([]);
    await client.close();
  });

  it("returns manifest entries from federation block", async () => {
    const client = await connectClient(
      join(FEDERATION_DIR, "knowledge.yaml")
    );
    const result = await client.callTool({
      name: "list_manifests",
      arguments: {},
    });

    const text = (result.content as Array<{ type: string; text: string }>)[0]
      .text;
    const entries = JSON.parse(text);
    expect(entries).toHaveLength(2);

    expect(entries[0].id).toBe("platform");
    expect(entries[0].url).toBe(
      "https://example.com/platform/knowledge.yaml"
    );
    expect(entries[0].label).toBe("Platform Team");
    expect(entries[0].relationship).toBe("foundation");
    expect(entries[0].has_local_mirror).toBe(false);
    expect(entries[0].update_frequency).toBe("weekly");

    expect(entries[1].id).toBe("security");
    expect(entries[1].url).toBe(
      "https://example.com/security/knowledge.yaml"
    );
    expect(entries[1].label).toBe("Security Team");
    expect(entries[1].relationship).toBe("governs");
    expect(entries[1].has_local_mirror).toBe(false);
    expect(entries[1].update_frequency).toBeNull();
    await client.close();
  });
});

// RFC-0021 / C18: manifest-level (federation source) temporal filtering. The hub federates
// two GDPR corpora via local_mirror with disjoint source windows — gdpr-2018
// (valid_until 2023-09-01) and gdpr-2023 (valid_from 2023-09-01). Neither corpus declares
// unit-level temporal, so the *only* thing that can include or exclude their units is the
// manifests[].temporal source window. Both consent units match the query "consent gdpr".
describe("federation temporal (C18)", () => {
  const FED_TEMPORAL_DIR = join(import.meta.dirname, "fixtures/fed-temporal");
  const HUB = join(FED_TEMPORAL_DIR, "knowledge.yaml");
  const MIRRORS = [
    join(FED_TEMPORAL_DIR, "mirror-old/knowledge.yaml"),
    join(FED_TEMPORAL_DIR, "mirror-new/knowledge.yaml"),
  ];

  async function connectFed() {
    const { server } = createKcpServer(HUB, {
      warnOnValidation: false,
      subManifests: MIRRORS,
    });
    const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
    await server.connect(serverTransport);
    const client = new Client({ name: "test-client", version: "0.1.0" }, { capabilities: {} });
    await client.connect(clientTransport);
    return client;
  }

  async function searchIds(args: Record<string, unknown>): Promise<string[]> {
    const client = await connectFed();
    const result = await client.callTool({ name: "search_knowledge", arguments: args });
    await client.close();
    const text = (result.content as Array<{ type: string; text: string }>)[0].text;
    const parsed = JSON.parse(text);
    return Array.isArray(parsed) ? parsed.map((r: { id: string }) => r.id) : [];
  }

  it("as_of inside only the active window excludes the expired source's units", async () => {
    // 2026 is after gdpr-2018's valid_until and inside gdpr-2023's window.
    const ids = await searchIds({ query: "consent gdpr", as_of: "2026-06-13" });
    expect(ids).toContain("gdpr-2023-consent");
    expect(ids).not.toContain("gdpr-2018-consent");
  });

  it("as_of inside the expired window includes it and excludes the not-yet-valid source", async () => {
    // 2020 is inside gdpr-2018's window and before gdpr-2023's valid_from.
    const ids = await searchIds({ query: "consent gdpr", as_of: "2020-01-01" });
    expect(ids).toContain("gdpr-2018-consent");
    expect(ids).not.toContain("gdpr-2023-consent");
  });

  it("include_all_temporal bypasses manifest-level filtering and returns both sources", async () => {
    const ids = await searchIds({ query: "consent gdpr", include_all_temporal: true });
    expect(ids).toContain("gdpr-2018-consent");
    expect(ids).toContain("gdpr-2023-consent");
  });

  it("as_of and include_all_temporal together are a conflict error", async () => {
    const client = await connectFed();
    const result = await client.callTool({
      name: "search_knowledge",
      arguments: { query: "consent gdpr", as_of: "2020-01-01", include_all_temporal: true },
    });
    await client.close();
    const text = (result.content as Array<{ type: string; text: string }>)[0].text;
    expect(JSON.parse(text).error).toBe("temporal_query_conflict");
  });

  it("list_manifests exposes the temporal block and computed activity", async () => {
    const client = await connectFed();
    const result = await client.callTool({ name: "list_manifests", arguments: {} });
    await client.close();
    const text = (result.content as Array<{ type: string; text: string }>)[0].text;
    const entries = JSON.parse(text) as Array<{
      id: string;
      temporal: { valid_until?: string } | null;
      temporally_active: boolean;
    }>;
    const old = entries.find((e) => e.id === "gdpr-2018")!;
    const cur = entries.find((e) => e.id === "gdpr-2023")!;
    expect(old.temporal?.valid_until).toBe("2023-09-01");
    expect(old.temporally_active).toBe(false); // expired as of today
    expect(cur.temporally_active).toBe(true);
  });
});

// Issue #98 — C18 hardening: the temporal filter must hold on every retrieval path (not just
// search_knowledge), bind robustly, validate input, enforce supersession, and be observable
// when bypassed. Same fed-temporal hub: gdpr-2018 (valid_until 2023-09-01, superseded_by
// gdpr-2023) and gdpr-2023 (valid_from 2023-09-01). "Today" in tests is well after 2023.
describe("C18 hardening (issue #98)", () => {
  const FED = join(import.meta.dirname, "fixtures/fed-temporal");
  const HUB = join(FED, "knowledge.yaml");
  const MIRRORS = [join(FED, "mirror-old/knowledge.yaml"), join(FED, "mirror-new/knowledge.yaml")];

  async function connect(subManifests = MIRRORS) {
    const { server } = createKcpServer(HUB, { warnOnValidation: false, subManifests });
    const [ct, st] = InMemoryTransport.createLinkedPair();
    await server.connect(st);
    const client = new Client({ name: "t", version: "0.1.0" }, { capabilities: {} });
    await client.connect(ct);
    return client;
  }
  const txt = (r: unknown) => ((r as { content: Array<{ text: string }> }).content[0].text);

  // ── F1: the filter holds on get_unit / read_resource / list_resources, not just search ──
  it("F1: get_unit refuses a unit from an expired source (today)", async () => {
    const c = await connect();
    const r = await c.callTool({ name: "get_unit", arguments: { unit_id: "gdpr-2018-consent" } });
    await c.close();
    expect(r.isError).toBe(true);
    expect(JSON.parse(txt(r)).error).toBe("temporally_unavailable");
  });

  it("F1: list_resources hides expired-source units, keeps active ones", async () => {
    const c = await connect();
    const { resources } = await c.listResources();
    await c.close();
    const names = resources.map((x) => x.name);
    expect(names).toContain("gdpr-2023-consent");
    expect(names).not.toContain("gdpr-2018-consent");
  });

  it("F1: read_resource throws for an expired-source unit", async () => {
    const c = await connect();
    await expect(
      c.readResource({ uri: "knowledge://fed-temporal-hub/gdpr-2018-consent" })
    ).rejects.toThrow(/temporal validity window/);
    await c.close();
  });

  // ── F2: association binds through a symlinked sub-manifest path (no lexical fail-open) ──
  it("F2: source temporal binds through a symlinked sub-manifest path", async () => {
    const tmp = mkdtempSync(join(tmpdir(), "kcp-fed-"));
    try {
      symlinkSync(join(FED, "mirror-old"), join(tmp, "mirror-old-link"), "dir");
      // Pass the expired mirror via a symlink whose lexical path differs from the hub's
      // ./mirror-old — only realpath canonicalisation makes the window bind.
      const c = await connect([join(tmp, "mirror-old-link/knowledge.yaml"), MIRRORS[1]]);
      const r = await c.callTool({ name: "get_unit", arguments: { unit_id: "gdpr-2018-consent" } });
      await c.close();
      expect(JSON.parse(txt(r)).error).toBe("temporally_unavailable");
    } finally {
      rmSync(tmp, { recursive: true, force: true });
    }
  });

  // ── F3: as_of is validated, not fed raw into string comparison ──
  it("F3: an unparseable as_of is rejected", async () => {
    const c = await connect();
    const r = await c.callTool({ name: "search_knowledge", arguments: { query: "consent gdpr", as_of: "not-a-date" } });
    await c.close();
    expect(r.isError).toBe(true);
    expect(JSON.parse(txt(r)).error).toBe("invalid_as_of");
  });

  // ── F4/F8: supersession hard-excludes on the boundary day once the successor is active ──
  it("F4: on the supersession boundary, the superseded source is dropped", async () => {
    const c = await connect();
    const r = await c.callTool({ name: "search_knowledge", arguments: { query: "consent gdpr", as_of: "2023-09-01" } });
    await c.close();
    const ids = (JSON.parse(txt(r)) as Array<{ id: string }>).map((x) => x.id);
    expect(ids).toContain("gdpr-2023-consent");
    expect(ids).not.toContain("gdpr-2018-consent"); // superseded by an active successor
  });

  // ── F5: include_all_temporal bypass is marked on results ──
  it("F5: include_all_temporal stamps a caution on results", async () => {
    const c = await connect();
    const r = await c.callTool({ name: "search_knowledge", arguments: { query: "consent gdpr", include_all_temporal: true } });
    await c.close();
    const rows = JSON.parse(txt(r)) as Array<{ caution: string | null }>;
    expect(rows.length).toBeGreaterThan(0);
    expect(rows.every((x) => (x.caution ?? "").includes("temporal filtering bypassed"))).toBe(true);
  });

  // ── F9: list_manifests honours as_of so it can't contradict a historical search ──
  it("F9: list_manifests temporally_active reflects as_of", async () => {
    const c = await connect();
    const r = await c.callTool({ name: "list_manifests", arguments: { as_of: "2020-01-01" } });
    await c.close();
    const entries = JSON.parse(txt(r)) as Array<{ id: string; temporally_active: boolean }>;
    expect(entries.find((e) => e.id === "gdpr-2018")!.temporally_active).toBe(true);
    expect(entries.find((e) => e.id === "gdpr-2023")!.temporally_active).toBe(false);
  });
});

describe("prompts/list", () => {
  it("lists both prompts", async () => {
    const client = await connectClient(join(MINIMAL_DIR, "knowledge.yaml"));
    const { prompts } = await client.listPrompts();

    expect(prompts).toHaveLength(2);
    const names = prompts.map((p) => p.name);
    expect(names).toContain("sdd-review");
    expect(names).toContain("kcp-explore");
    await client.close();
  });

  it("sdd-review prompt has optional focus argument", async () => {
    const client = await connectClient(join(MINIMAL_DIR, "knowledge.yaml"));
    const { prompts } = await client.listPrompts();
    const sddReview = prompts.find((p) => p.name === "sdd-review")!;
    expect(sddReview.arguments).toBeDefined();
    const focusArg = sddReview.arguments!.find(
      (a) => a.name === "focus"
    );
    expect(focusArg).toBeDefined();
    expect(focusArg!.required).toBe(false);
    await client.close();
  });

  it("kcp-explore prompt has required topic argument", async () => {
    const client = await connectClient(join(MINIMAL_DIR, "knowledge.yaml"));
    const { prompts } = await client.listPrompts();
    const kcpExplore = prompts.find((p) => p.name === "kcp-explore")!;
    expect(kcpExplore.arguments).toBeDefined();
    const topicArg = kcpExplore.arguments!.find(
      (a) => a.name === "topic"
    );
    expect(topicArg).toBeDefined();
    expect(topicArg!.required).toBe(true);
    await client.close();
  });
});

describe("prompts/get", () => {
  it("sdd-review returns structured prompt with review criteria", async () => {
    const client = await connectClient(join(MINIMAL_DIR, "knowledge.yaml"));
    const result = await client.getPrompt({
      name: "sdd-review",
      arguments: { focus: "security" },
    });

    expect(result.messages).toHaveLength(1);
    expect(result.messages[0].role).toBe("user");
    const text = (result.messages[0].content as { type: string; text: string })
      .text;
    expect(text).toContain("SDD Review: security");
    expect(text).toContain("Input Validation");
    expect(text).toContain("Secret Management");
    await client.close();
  });

  it("sdd-review defaults to architecture focus", async () => {
    const client = await connectClient(join(MINIMAL_DIR, "knowledge.yaml"));
    const result = await client.getPrompt({
      name: "sdd-review",
      arguments: {},
    });

    const text = (result.messages[0].content as { type: string; text: string })
      .text;
    expect(text).toContain("SDD Review: architecture");
    expect(text).toContain("Intent Clarity");
    await client.close();
  });

  it("kcp-explore returns prompt referencing the topic", async () => {
    const client = await connectClient(join(MINIMAL_DIR, "knowledge.yaml"));
    const result = await client.getPrompt({
      name: "kcp-explore",
      arguments: { topic: "authentication" },
    });

    expect(result.messages).toHaveLength(1);
    const text = (result.messages[0].content as { type: string; text: string })
      .text;
    expect(text).toContain("authentication");
    expect(text).toContain("search_knowledge");
    await client.close();
  });
});
