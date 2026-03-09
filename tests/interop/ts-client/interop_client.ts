/**
 * Interop test client -- TypeScript
 *
 * Connects to a KCP MCP bridge via stdio, lists resources and reads each one,
 * then prints structured results as JSON for parity comparison.
 *
 * Usage: npx tsx interop_client.ts <bridge-command> <bridge-args...> -- <output-path>
 *   or:  npx tsx interop_client.ts <manifest-path> [output-path]
 */

import { resolve, dirname } from "node:path";
import { writeFileSync } from "node:fs";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

interface ResourceEntry {
  uri: string;
  name: string;
  description?: string;
  mimeType?: string;
  content_preview?: string;
}

interface InteropResult {
  resource_count: number;
  resources: ResourceEntry[];
}

async function main(): Promise<void> {
  const manifestPath = process.argv[2];
  const outputPath = process.argv[3] || "ts-results.json";

  if (!manifestPath) {
    console.error("Usage: npx tsx interop_client.ts <manifest-path> [output-path]");
    process.exit(1);
  }

  const absManifest = resolve(manifestPath);

  // Start the TS bridge as a subprocess with stdio transport
  const bridgePath = resolve(
    dirname(new URL(import.meta.url).pathname),
    "../../../bridge/typescript/dist/cli.js"
  );

  const transport = new StdioClientTransport({
    command: "node",
    args: [bridgePath, absManifest],
  });

  const client = new Client({
    name: "interop-ts-client",
    version: "1.0.0",
  });

  await client.connect(transport);

  // List all resources
  const listResult = await client.listResources();
  const resources: ResourceEntry[] = [];

  for (const res of listResult.resources) {
    const entry: ResourceEntry = {
      uri: res.uri,
      name: res.name,
      description: res.description,
      mimeType: res.mimeType,
    };

    // Read each resource to get content
    try {
      const readResult = await client.readResource({ uri: res.uri });
      const contents = readResult.contents;
      if (contents && contents.length > 0) {
        const firstContent = contents[0];
        if ("text" in firstContent && typeof firstContent.text === "string") {
          // Store first 200 chars as preview for comparison
          entry.content_preview = firstContent.text.substring(0, 200);
        }
      }
    } catch (err: any) {
      console.error(`Failed to read ${res.uri}: ${err.message}`);
    }

    resources.push(entry);
  }

  // Sort by URI for deterministic comparison
  resources.sort((a, b) => a.uri.localeCompare(b.uri));

  const result: InteropResult = {
    resource_count: resources.length,
    resources,
  };

  writeFileSync(outputPath, JSON.stringify(result, null, 2));
  console.log(`Wrote ${resources.length} resources to ${outputPath}`);

  await client.close();
  process.exit(0);
}

main().catch((err) => {
  console.error(`Fatal: ${err.message}`);
  process.exit(1);
});
