#!/usr/bin/env node
// KCP Developer CLI
// Usage: kcp <command> [options]

import { parseArgs } from "node:util";

function printUsage(): void {
  process.stderr.write(
    `\nKCP Developer CLI — v0.15.0

Usage: kcp <command> [options]

Commands:
  init [output]       Scaffold a knowledge.yaml for this project (default: knowledge.yaml)
  validate [file]     Validate a knowledge.yaml manifest (default: knowledge.yaml)
  query <question>    Simulate agent search against a manifest
  stats               Show KCP usage statistics (queries, units, tokens saved)

Options:
  --file <path>       Manifest path (for query command, default: knowledge.yaml)
  --days <n>          Reporting window in days for stats (default: 30)
  --json              Machine-readable JSON output (stats command)
  --project <name>    Filter stats by project name
  --help, -h          Show this help

Examples:
  npx kcp init                              # scaffold ./knowledge.yaml
  npx kcp init docs/knowledge.yaml         # scaffold to custom path
  npx kcp validate                          # validate ./knowledge.yaml
  npx kcp validate docs/knowledge.yaml     # validate a specific file
  npx kcp query "how do I deploy?"         # simulate agent search
  npx kcp query "authentication" --file docs/knowledge.yaml
  npx kcp stats                            # usage stats for last 30 days
  npx kcp stats --days 7                   # last 7 days
  npx kcp stats --json                     # machine-readable output

`
  );
}

async function main(): Promise<void> {
  const { values, positionals } = parseArgs({
    args: process.argv.slice(2),
    options: {
      file:    { type: "string" },
      help:    { type: "boolean", default: false, short: "h" },
      yes:     { type: "boolean", default: false, short: "y" },
      json:    { type: "boolean", default: false },
      days:    { type: "string",  default: "30" },
      project: { type: "string" },
    },
    allowPositionals: true,
    strict: false,
  });

  if (values.help || positionals.length === 0) {
    printUsage();
    process.exit(values.help ? 0 : 1);
  }

  const command = positionals[0];

  if (command === "init") {
    const { runInit } = await import("./init.js");
    const outputPath = positionals[1] ?? "knowledge.yaml";
    await runInit(outputPath, values.yes as boolean);
    return;
  }

  if (command === "validate") {
    const { runValidate } = await import("./validate.js");
    const manifestPath = positionals[1] ?? (values.file as string | undefined) ?? "knowledge.yaml";
    runValidate(manifestPath);
    return;
  }

  if (command === "query") {
    const { runQuery } = await import("./query.js");
    const { parseFile } = await import("./parser.js");

    const query = positionals.slice(1).join(" ");
    if (!query) {
      process.stderr.write("Error: query requires a question, e.g.: kcp query \"how do I deploy?\"\n");
      process.exit(1);
    }

    const manifestPath = (values.file as string | undefined) ?? "knowledge.yaml";
    let manifest;
    try {
      manifest = parseFile(manifestPath);
    } catch (err) {
      process.stderr.write(`Error: ${err instanceof Error ? err.message : String(err)}\n`);
      process.exit(1);
    }

    runQuery(manifest, query);
    return;
  }

  if (command === "stats") {
    const { runStats } = await import("./stats.js");
    runStats({
      json:    values.json as boolean,
      days:    parseInt(values.days as string, 10) || 30,
      project: values.project as string | undefined,
    });
    return;
  }

  process.stderr.write(`Unknown command: "${command}"\n`);
  printUsage();
  process.exit(1);
}

main().catch((err) => {
  process.stderr.write(`Fatal: ${err instanceof Error ? err.message : String(err)}\n`);
  process.exit(1);
});
