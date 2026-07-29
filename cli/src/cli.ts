#!/usr/bin/env node
// KCP Developer CLI
// Usage: kcp <command> [options]

import { parseArgs } from "node:util";

function printUsage(): void {
  process.stderr.write(
    `\nKCP Developer CLI — v0.30.3

Usage: kcp <command> [options]

Commands:
  init [output]           Scaffold a knowledge.yaml for this project (default: knowledge.yaml)
  import-okf <directory>  Import a Google OKF bundle and generate a knowledge.yaml
  validate [file]         Validate a knowledge.yaml manifest (default: knowledge.yaml)
  render [file]           Render a manifest into a trust-tiered artifact (RFC-0018, default: knowledge.yaml)
  sign [file]             Sign a manifest (Ed25519 detached, RFC-0018 §4.2; default: knowledge.yaml)
  query <question>        Simulate agent search against a manifest
  stats                   Show KCP usage statistics (queries, units, tokens saved)

Options:
  --file <path>       Manifest path (for query command, default: knowledge.yaml)
  --out <path>        Output path (import-okf: default <directory>/knowledge.yaml; render: stdout)
  --keys <path>       Trusted-keys allowlist for render (default: ~/.kcp/trusted-keys.yaml)
  --origin <string>   Assert the origin for render (RFC-0018 §4.1; asserted evidence, RFC-0019 §4.1)
  --timestamp         Include rendered_at in render output (excluded from determinism)
  --allow-derived-origin   Let a git-derived origin satisfy trusted tier (RFC-0019 §4.3; accepts T9 risk)
  --require-unit-hashes    Deny standing-context eligibility to units without content_hash (RFC-0019 §3.3)
  --corroborate            Verify a derived origin by fetching the manifest from it (RFC-0019 §4.3)
  --corroborate-url <url>  Explicit manifest URL for corroboration (implies --corroborate)
  --retrieved-from <url>   Final URL the manifest was retrieved from; demotes trusted→known on a serving.manifest mismatch (RFC-0024 §3.12 / C22)
  --key <path>        Ed25519 private key (PEM, PKCS#8) for sign
  --key-id <id>       Allowlist key id recorded in the signature (sign)
  --update-hashes     Recompute declared content_hash values before signing (RFC-0019 §3.1)
  --days <n>          Reporting window in days for stats (default: 30)
  --json              Machine-readable JSON output (stats command)
  --project <name>    Filter stats by project name
  --help, -h          Show this help

Examples:
  npx kcp init                              # scaffold ./knowledge.yaml
  npx kcp init docs/knowledge.yaml         # scaffold to custom path
  npx kcp import-okf ./my-okf-bundle       # import OKF → knowledge.yaml in that directory
  npx kcp import-okf ./okf --out kcp.yaml  # import OKF → custom output path
  npx kcp validate                          # validate ./knowledge.yaml
  npx kcp validate docs/knowledge.yaml     # validate a specific file
  npx kcp render                            # render ./knowledge.yaml to stdout
  npx kcp render --out kcp-rendered.yaml   # render to a file
  npx kcp render --keys ~/.kcp/trusted-keys.yaml --origin github.com/Cantara/repo
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
      file:      { type: "string" },
      help:      { type: "boolean", default: false, short: "h" },
      yes:       { type: "boolean", default: false, short: "y" },
      json:      { type: "boolean", default: false },
      days:      { type: "string",  default: "30" },
      project:   { type: "string" },
      keys:      { type: "string" },
      origin:    { type: "string" },
      out:       { type: "string" },
      timestamp: { type: "boolean", default: false },
      "allow-derived-origin": { type: "boolean", default: false },
      "require-unit-hashes":  { type: "boolean", default: false },
      "as-of":                { type: "string" },
      "include-all-temporal": { type: "boolean", default: false },
      corroborate:        { type: "boolean", default: false },
      "corroborate-url":  { type: "string" },
      "retrieved-from":   { type: "string" },
      key:                { type: "string" },
      "key-id":           { type: "string" },
      "update-hashes":    { type: "boolean", default: false },
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

  if (command === "import-okf") {
    const { runImportOkf } = await import("./import-okf.js");
    const okfDir = positionals[1];
    if (!okfDir) {
      process.stderr.write("Error: import-okf requires a directory path, e.g.: kcp import-okf ./my-okf-bundle\n");
      process.exit(1);
    }
    await runImportOkf(okfDir, values.out as string | undefined);
    return;
  }

  if (command === "validate") {
    const { runValidate } = await import("./validate.js");
    const manifestPath = positionals[1] ?? (values.file as string | undefined) ?? "knowledge.yaml";
    runValidate(manifestPath);
    return;
  }

  if (command === "render") {
    const { runRender } = await import("./render.js");
    const manifestPath = positionals[1] ?? (values.file as string | undefined) ?? "knowledge.yaml";
    await runRender({
      manifestPath,
      keys:      values.keys as string | undefined,
      origin:    values.origin as string | undefined,
      out:       values.out as string | undefined,
      timestamp: values.timestamp as boolean,
      allowDerivedOrigin: values["allow-derived-origin"] as boolean,
      requireUnitHashes:  values["require-unit-hashes"] as boolean,
      corroborate:        values.corroborate as boolean,
      corroborateUrl:     values["corroborate-url"] as string | undefined,
      retrievedFrom:      values["retrieved-from"] as string | undefined,
    });
    return;
  }

  if (command === "sign") {
    const { runSign } = await import("./sign.js");
    const manifestPath = positionals[1] ?? (values.file as string | undefined) ?? "knowledge.yaml";
    if (!values.key || !values["key-id"]) {
      process.stderr.write("Error: sign requires --key <ed25519.pem> and --key-id <id>\n");
      process.exit(1);
    }
    runSign({
      manifestPath,
      keyPath: values.key as string,
      keyId:   values["key-id"] as string,
      updateHashes: values["update-hashes"] as boolean,
      out:     values.out as string | undefined,
    });
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

    runQuery(manifest, query, {
      asOf: values["as-of"] as string | undefined,
      includeAllTemporal: values["include-all-temporal"] as boolean,
    });
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
