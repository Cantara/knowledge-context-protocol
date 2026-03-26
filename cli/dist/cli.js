#!/usr/bin/env node
// KCP Developer CLI
// Usage: kcp <command> [options]
import { parseArgs } from "node:util";
function printUsage() {
    process.stderr.write(`\nKCP Developer CLI — v0.14.0

Usage: kcp <command> [options]

Commands:
  init [output]       Scaffold a knowledge.yaml for this project (default: knowledge.yaml)
  validate [file]     Validate a knowledge.yaml manifest (default: knowledge.yaml)
  query <question>    Simulate agent search against a manifest

Options:
  --file <path>       Manifest path (for query command, default: knowledge.yaml)
  --help, -h          Show this help

Examples:
  npx kcp init                              # scaffold ./knowledge.yaml
  npx kcp init docs/knowledge.yaml         # scaffold to custom path
  npx kcp validate                          # validate ./knowledge.yaml
  npx kcp validate docs/knowledge.yaml     # validate a specific file
  npx kcp query "how do I deploy?"         # simulate agent search
  npx kcp query "authentication" --file docs/knowledge.yaml

`);
}
async function main() {
    const { values, positionals } = parseArgs({
        args: process.argv.slice(2),
        options: {
            file: { type: "string" },
            help: { type: "boolean", default: false, short: "h" },
            yes: { type: "boolean", default: false, short: "y" },
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
        await runInit(outputPath, values.yes);
        return;
    }
    if (command === "validate") {
        const { runValidate } = await import("./validate.js");
        const manifestPath = positionals[1] ?? values.file ?? "knowledge.yaml";
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
        const manifestPath = values.file ?? "knowledge.yaml";
        let manifest;
        try {
            manifest = parseFile(manifestPath);
        }
        catch (err) {
            process.stderr.write(`Error: ${err instanceof Error ? err.message : String(err)}\n`);
            process.exit(1);
        }
        runQuery(manifest, query);
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
//# sourceMappingURL=cli.js.map