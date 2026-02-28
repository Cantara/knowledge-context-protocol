import { describe, it, expect } from "vitest";
import { join } from "node:path";
import { createKcpServer } from "../src/server.js";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";

const MINIMAL_DIR = join(import.meta.dirname, "fixtures/minimal");
const FULL_DIR = join(import.meta.dirname, "fixtures/full");

async function connectClient(manifestPath: string, agentOnly = false) {
  const { server } = createKcpServer(manifestPath, {
    agentOnly,
    warnOnValidation: false,
  });

  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await server.connect(serverTransport);

  const client = new Client({ name: "test-client", version: "0.1.0" }, { capabilities: {} });
  await client.connect(clientTransport);

  return client;
}

describe("createKcpServer", () => {
  it("throws on missing manifest", () => {
    expect(() => createKcpServer("/nonexistent/knowledge.yaml")).toThrow();
  });

  it("loads the minimal manifest without throwing", () => {
    expect(() =>
      createKcpServer(join(MINIMAL_DIR, "knowledge.yaml"), {
        warnOnValidation: false,
      })
    ).not.toThrow();
  });
});

describe("resources/list", () => {
  it("returns manifest + units for minimal fixture", async () => {
    const client = await connectClient(join(MINIMAL_DIR, "knowledge.yaml"));
    const { resources } = await client.listResources();

    // manifest resource + 1 unit
    expect(resources).toHaveLength(2);
    const names = resources.map((r) => r.name);
    expect(names).toContain("manifest");
    expect(names).toContain("overview");
    await client.close();
  });

  it("returns manifest + all units for full fixture", async () => {
    const client = await connectClient(join(FULL_DIR, "knowledge.yaml"));
    const { resources } = await client.listResources();

    // manifest + 3 units
    expect(resources).toHaveLength(4);
    await client.close();
  });

  it("filters human-only units when agentOnly=true", async () => {
    // full fixture: spec (human+agent+developer), api-schema (developer+agent), guide (human+developer)
    // agentOnly=true should expose manifest + spec + api-schema (2 with agent), not guide
    const client = await connectClient(
      join(FULL_DIR, "knowledge.yaml"),
      true
    );
    const { resources } = await client.listResources();
    const names = resources.map((r) => r.name);
    expect(names).toContain("manifest"); // always present
    expect(names).toContain("spec");
    expect(names).toContain("api-schema");
    expect(names).not.toContain("guide");
    await client.close();
  });

  it("manifest resource has priority 1.0", async () => {
    const client = await connectClient(join(MINIMAL_DIR, "knowledge.yaml"));
    const { resources } = await client.listResources();
    const manifest = resources.find((r) => r.name === "manifest");
    expect(manifest?.annotations?.priority).toBe(1.0);
    await client.close();
  });

  it("unit URIs use knowledge:// scheme", async () => {
    const client = await connectClient(join(MINIMAL_DIR, "knowledge.yaml"));
    const { resources } = await client.listResources();
    const unit = resources.find((r) => r.name === "overview");
    expect(unit?.uri).toMatch(/^knowledge:\/\//);
    await client.close();
  });
});

describe("resources/read", () => {
  it("reads the manifest meta-resource as JSON", async () => {
    const client = await connectClient(join(MINIMAL_DIR, "knowledge.yaml"));
    const { resources } = await client.listResources();
    const manifestResource = resources.find((r) => r.name === "manifest")!;

    const result = await client.readResource({ uri: manifestResource.uri });
    expect(result.contents).toHaveLength(1);

    const content = result.contents[0] as { uri: string; mimeType?: string; text?: string };
    expect(content.mimeType).toBe("application/json");

    const parsed = JSON.parse(content.text!);
    expect(parsed.project).toBe("my-project");
    expect(parsed.units).toHaveLength(1);
    await client.close();
  });

  it("reads a text unit's file content", async () => {
    const client = await connectClient(join(MINIMAL_DIR, "knowledge.yaml"));
    const { resources } = await client.listResources();
    const unit = resources.find((r) => r.name === "overview")!;

    const result = await client.readResource({ uri: unit.uri });
    expect(result.contents).toHaveLength(1);

    const content = result.contents[0] as { text?: string };
    expect(content.text).toContain("My Project");
    await client.close();
  });

  it("reads a JSON unit with correct mimeType", async () => {
    const client = await connectClient(join(FULL_DIR, "knowledge.yaml"));
    const { resources } = await client.listResources();
    const apiUnit = resources.find((r) => r.name === "api-schema")!;

    const result = await client.readResource({ uri: apiUnit.uri });
    const content = result.contents[0] as { mimeType?: string; text?: string };
    expect(content.mimeType).toBe("application/schema+json");
    const parsed = JSON.parse(content.text!);
    expect(parsed.$schema).toBeDefined();
    await client.close();
  });

  it("throws on unknown URI", async () => {
    const client = await connectClient(join(MINIMAL_DIR, "knowledge.yaml"));
    await expect(
      client.readResource({ uri: "knowledge://my-project/nonexistent" })
    ).rejects.toThrow();
    await client.close();
  });
});
