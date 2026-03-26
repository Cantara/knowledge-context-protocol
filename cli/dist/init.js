// kcp init — interactive scaffold for knowledge.yaml
import { createInterface } from "node:readline";
import { existsSync, readdirSync, statSync, writeFileSync, } from "node:fs";
import { basename, join, relative } from "node:path";
const green = (s) => `\x1b[32m${s}\x1b[0m`;
const cyan = (s) => `\x1b[36m${s}\x1b[0m`;
const bold = (s) => `\x1b[1m${s}\x1b[0m`;
const dim = (s) => `\x1b[2m${s}\x1b[0m`;
const INTENT_MAP = {
    "readme.md": "What is this project and how do I get started?",
    "readme": "What is this project?",
    "changelog.md": "What has changed between versions?",
    "changelog": "What has changed between versions?",
    "contributing.md": "How do I contribute to this project?",
    "contributing": "How do I contribute to this project?",
    "security.md": "How do I report security vulnerabilities?",
    "license.md": "What license does this project use?",
    "architecture.md": "What is the high-level architecture of this system?",
    "architecture": "What is the high-level architecture of this system?",
    "adr": "Why was this architectural decision made?",
    "api.md": "What are the available API endpoints?",
    "deployment.md": "How do I deploy this application?",
    "deployment": "How do I deploy this application?",
    "runbook.md": "How do I operate and troubleshoot this service?",
    "runbook": "How do I operate and troubleshoot this service?",
    "spec.md": "What does this specification define?",
    "design.md": "What is the design rationale for this component?",
};
function suggestIntent(relPath, filename) {
    const lower = filename.toLowerCase().replace(/\.(md|txt|rst|adoc)$/, "");
    if (INTENT_MAP[lower])
        return INTENT_MAP[lower];
    if (INTENT_MAP[filename.toLowerCase()])
        return INTENT_MAP[filename.toLowerCase()];
    // Path-based hints
    const parts = relPath.toLowerCase().split("/");
    if (parts.some((p) => p === "adr" || p === "decisions"))
        return "Why was this architectural decision made?";
    if (parts.some((p) => p === "api" || p === "openapi"))
        return "What are the available API endpoints?";
    if (parts.some((p) => p === "guides" || p === "tutorials"))
        return "How do I use this feature?";
    if (parts.some((p) => p === "runbooks" || p === "ops"))
        return "How do I operate this service?";
    if (parts.some((p) => p === "security"))
        return "What are the security requirements or policies?";
    // Fall back to filename-derived
    const readable = lower.replace(/[-_]/g, " ");
    return `What is covered in "${readable}"?`;
}
function suggestId(relPath) {
    return relPath
        .replace(/\.(md|txt|rst|adoc|yaml|yml)$/i, "")
        .replace(/[/\\]/g, "-")
        .replace(/[^a-z0-9-]/gi, "-")
        .replace(/-+/g, "-")
        .replace(/^-|-$/g, "")
        .toLowerCase();
}
const CANDIDATE_FILENAMES = new Set([
    "readme.md", "readme.txt", "readme",
    "changelog.md", "changelog",
    "contributing.md",
    "security.md",
    "license.md",
    "architecture.md",
    "deployment.md",
    "runbook.md",
]);
const CANDIDATE_DIRS = ["docs", "doc", "documentation", "guides", "guide", "adr", "decisions", "architecture", "api", "runbooks"];
const SKIP_DIRS = new Set(["node_modules", ".git", "dist", "build", "target", ".next", ".nuxt", "coverage", "__pycache__", ".venv", "venv"]);
const DOC_EXTENSIONS = new Set([".md", ".txt", ".rst", ".adoc"]);
function scanDirectory(dir, maxDepth = 3, depth = 0) {
    if (depth > maxDepth)
        return [];
    const results = [];
    let entries;
    try {
        entries = readdirSync(dir);
    }
    catch {
        return results;
    }
    for (const name of entries) {
        const fullPath = join(dir, name);
        const relPath = relative(process.cwd(), fullPath);
        const lower = name.toLowerCase();
        let stat;
        try {
            stat = statSync(fullPath);
        }
        catch {
            continue;
        }
        if (stat.isDirectory()) {
            if (SKIP_DIRS.has(lower))
                continue;
            // At depth=0, scan known doc dirs. At depth>0, scan everything.
            if (depth === 0 && !CANDIDATE_DIRS.includes(lower))
                continue;
            results.push(...scanDirectory(fullPath, maxDepth, depth + 1));
        }
        else if (stat.isFile()) {
            const isRoot = depth === 0;
            const ext = name.slice(name.lastIndexOf(".")).toLowerCase();
            const isDoc = DOC_EXTENSIONS.has(ext) || CANDIDATE_FILENAMES.has(lower);
            if (!isDoc)
                continue;
            // At root level, only include well-known files; in doc dirs include all .md etc.
            if (isRoot && !CANDIDATE_FILENAMES.has(lower))
                continue;
            results.push({
                path: relPath,
                suggestedId: suggestId(relPath),
                suggestedIntent: suggestIntent(relPath, name),
                suggestedScope: depth === 0 ? "global" : "module",
            });
        }
    }
    return results;
}
// --- Prompt helpers ---
function prompt(rl, question) {
    return new Promise((resolve) => rl.question(question, resolve));
}
// --- Main ---
export async function runInit(outputPath, yes = false) {
    if (existsSync(outputPath)) {
        process.stderr.write(`\nError: "${outputPath}" already exists. Delete it first or specify a different path.\n\n`);
        process.exit(1);
    }
    // Non-interactive mode: no TTY or --yes flag
    const interactive = !yes && process.stdin.isTTY;
    const defaultName = basename(process.cwd());
    if (!interactive) {
        // Non-interactive: scan and write with defaults
        process.stdout.write(`\n${bold("KCP Init")} — scaffold a ${cyan("knowledge.yaml")} for your project\n\n`);
        process.stdout.write(`  Project: ${defaultName}\n`);
        process.stdout.write(`  Mode: non-interactive (use --yes or run in a terminal for prompts)\n\n`);
        const discovered = scanDirectory(process.cwd());
        writeManifest(outputPath, defaultName, "en", discovered);
        return;
    }
    const rl = createInterface({ input: process.stdin, output: process.stdout });
    process.stdout.write(`\n${bold("KCP Init")} — scaffold a ${cyan("knowledge.yaml")} for your project\n\n`);
    // 1. Project name
    const nameInput = await prompt(rl, `Project name ${dim(`(default: ${defaultName})`)}: `);
    const projectName = nameInput.trim() || defaultName;
    // 2. Language
    const langInput = await prompt(rl, `Language ${dim("(default: en)")}: `);
    const language = langInput.trim() || "en";
    // 3. Scan for files
    process.stdout.write(`\nScanning project...\n`);
    const discovered = scanDirectory(process.cwd());
    let selectedFiles = [];
    if (discovered.length === 0) {
        process.stdout.write(dim("  No documentation files found. Starting with empty unit list.\n\n"));
    }
    else {
        process.stdout.write(`\nFound ${discovered.length} documentation file(s):\n\n`);
        discovered.forEach((f, i) => {
            process.stdout.write(`  ${dim(`${i + 1}.`)} ${cyan(f.path)}\n`);
            process.stdout.write(`     ${dim(f.suggestedIntent)}\n`);
        });
        process.stdout.write("\n");
        const includeInput = await prompt(rl, `Include all ${discovered.length} file(s)? ${dim("(Y/n, or comma-separated numbers to exclude)")}: `);
        const includeVal = includeInput.trim().toLowerCase();
        if (includeVal === "" || includeVal === "y" || includeVal === "yes") {
            selectedFiles = discovered;
        }
        else if (includeVal === "n" || includeVal === "no") {
            selectedFiles = [];
        }
        else {
            const excluded = new Set(includeVal.split(",").map((s) => parseInt(s.trim(), 10) - 1));
            selectedFiles = discovered.filter((_, i) => !excluded.has(i));
        }
    }
    rl.close();
    writeManifest(outputPath, projectName, language, selectedFiles);
}
function writeManifest(outputPath, projectName, language, selectedFiles) {
    const today = new Date().toISOString().slice(0, 10);
    const lines = [
        `kcp_version: "0.14"`,
        `project: "${projectName}"`,
        `version: "1.0.0"`,
        `updated: "${today}"`,
        `language: ${language}`,
        ``,
        `units:`,
    ];
    if (selectedFiles.length === 0) {
        lines.push(`  # Add your knowledge units here.`);
        lines.push(`  # Example:`);
        lines.push(`  # - id: readme`);
        lines.push(`  #   path: README.md`);
        lines.push(`  #   intent: "What is this project and how do I get started?"`);
        lines.push(`  #   scope: global`);
        lines.push(`  #   audience: [human, agent]`);
    }
    else {
        for (const f of selectedFiles) {
            lines.push(`  - id: ${f.suggestedId}`);
            lines.push(`    path: ${f.path}`);
            lines.push(`    intent: "${f.suggestedIntent}"`);
            lines.push(`    scope: ${f.suggestedScope}`);
            lines.push(`    audience: [human, agent]`);
            lines.push(``);
        }
    }
    const yaml = lines.join("\n") + "\n";
    writeFileSync(outputPath, yaml, "utf-8");
    process.stdout.write(`\n${green("✓")} Created ${bold(outputPath)}`);
    process.stdout.write(`  ${dim(`${selectedFiles.length} unit(s) scaffolded`)}\n\n`);
    process.stdout.write(`Next steps:\n`);
    process.stdout.write(`  1. Review and edit ${cyan(outputPath)} — adjust intents and add triggers\n`);
    process.stdout.write(`  2. ${cyan("npx kcp validate")} — check the manifest is valid\n`);
    process.stdout.write(`  3. ${cyan("npx kcp-mcp")} — start the MCP bridge for AI agent access\n\n`);
}
//# sourceMappingURL=init.js.map