import { describe, it, expect } from "vitest";
import { join } from "node:path";
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
