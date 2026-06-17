// kcp import-okf — Convert an OKF bundle into a KCP manifest
// Usage: kcp import-okf <directory> [--out knowledge.yaml]
//
// Reads all Markdown files with YAML frontmatter containing a `type` field
// (skipping index.md and log.md), then generates a knowledge.yaml with:
//   - units derived from each OKF document
//   - content_hash computed for each file (sha256)
//   - depends_on derived from Markdown links between OKF files
//   - TODO annotations on auto-generated intents and missing temporal fields
//
// What cannot be inferred from OKF:
//   - intent quality (auto-generated from title/description — needs review)
//   - temporal.valid_from (OKF timestamp = last-modified, not enforcement date)
//   - trust/signatures (no signing performed — run kcp sign when ready)
//   - supersession chains (OKF has no supersession model)

import { readFileSync, writeFileSync, existsSync, readdirSync, statSync } from "fs";
import { join, relative, basename, resolve } from "path";
import { createHash } from "crypto";
import * as yaml from "js-yaml";

// ── ANSI colours (matches init.ts) ──────────────────────────────────────────
const green  = (s: string) => `\x1b[32m${s}\x1b[0m`;
const cyan   = (s: string) => `\x1b[36m${s}\x1b[0m`;
const bold   = (s: string) => `\x1b[1m${s}\x1b[0m`;
const dim    = (s: string) => `\x1b[2m${s}\x1b[0m`;
const yellow = (s: string) => `\x1b[33m${s}\x1b[0m`;
const red    = (s: string) => `\x1b[31m${s}\x1b[0m`;

// ── Types ────────────────────────────────────────────────────────────────────

interface OkfFrontmatter {
  type?: string;
  title?: string;
  description?: string;
  tags?: string[];
  timestamp?: string;
  resource?: string;
  [key: string]: unknown;
}

interface OkfDocument {
  filename: string;   // basename, e.g. "events.md"
  relPath:  string;   // relative to the OKF directory
  frontmatter: OkfFrontmatter;
  bodyLinks:   string[];  // filenames linked from the Markdown body
}

interface ImportedUnit {
  id:             string;
  path:           string;
  intent:         string;
  intentIsWeak:   boolean;   // true → emit TODO comment
  kind:           string;
  triggers:       string[];
  validated?:     string;    // ISO date from OKF timestamp, if present
  dependsOn:      string[];  // ids of other units linked from the body
  contentHash:    string;    // sha256 hex of the file
}

// ── Public entry point ───────────────────────────────────────────────────────

export async function runImportOkf(
  okfDir: string,
  outputPath: string | undefined,
): Promise<void> {
  const absDir = resolve(process.cwd(), okfDir);

  if (!existsSync(absDir) || !statSync(absDir).isDirectory()) {
    process.stderr.write(red(`Error: Not a directory: ${absDir}\n`));
    process.exit(1);
  }

  const absOutput = outputPath
    ? resolve(process.cwd(), outputPath)
    : join(absDir, "knowledge.yaml");

  if (existsSync(absOutput)) {
    process.stderr.write(
      red(`Error: ${absOutput} already exists.\n`) +
      dim(`Use --out <path> to specify a different output path.\n`),
    );
    process.exit(1);
  }

  process.stdout.write(bold(`\nKCP ← OKF Import\n`));
  process.stdout.write(dim(`Source: ${absDir}\n`));
  process.stdout.write(dim(`Output: ${absOutput}\n\n`));

  // 1. Discover OKF documents
  const docs = discoverOkfDocs(absDir);

  if (docs.length === 0) {
    process.stderr.write(
      red(`No OKF documents found in ${absDir}\n`) +
      dim(`Expected .md files with YAML frontmatter containing a 'type' field.\n`) +
      dim(`(index.md and log.md are reserved by OKF and are skipped.)\n`),
    );
    process.exit(1);
  }

  process.stdout.write(`Found ${bold(String(docs.length))} OKF document(s).\n\n`);

  // 2. Convert each OKF doc → KCP unit
  const okfFilenames = new Set(docs.map(d => d.filename));
  const units: ImportedUnit[] = docs.map(doc => convertToUnit(doc, absDir, okfFilenames));

  // 3. Build and write manifest
  const projectName = basename(absDir);
  const { manifest, weakIntentCount, noTemporalCount } = buildManifest(
    projectName,
    units,
    absDir,
  );

  writeFileSync(absOutput, manifest, "utf-8");

  // 4. Print summary
  printSummary(absOutput, units.length, weakIntentCount, noTemporalCount);
}

// ── OKF Discovery ────────────────────────────────────────────────────────────

function discoverOkfDocs(dir: string): OkfDocument[] {
  const docs: OkfDocument[] = [];

  for (const name of readdirSync(dir).sort()) {
    // OKF reserved files — structural, not knowledge units
    if (name === "index.md" || name === "log.md") continue;
    if (!name.endsWith(".md")) continue;

    const fullPath = join(dir, name);
    if (!statSync(fullPath).isFile()) continue;

    const raw = readFileSync(fullPath, "utf-8");
    const { frontmatter, body } = parseFrontmatter(raw);

    // OKF requires `type` — skip files that don't have it
    if (!frontmatter.type) continue;

    docs.push({
      filename: name,
      relPath:  relative(dir, fullPath),
      frontmatter,
      bodyLinks: extractMarkdownLinks(body),
    });
  }

  return docs;
}

function parseFrontmatter(raw: string): { frontmatter: OkfFrontmatter; body: string } {
  // Standard YAML frontmatter: starts with ---, ends with ---
  const match = raw.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n([\s\S]*)$/);
  if (!match) return { frontmatter: {}, body: raw };

  let fm: unknown;
  try {
    fm = yaml.load(match[1]);
  } catch {
    return { frontmatter: {}, body: match[2] };
  }

  if (!fm || typeof fm !== "object" || Array.isArray(fm)) {
    return { frontmatter: {}, body: match[2] };
  }

  return { frontmatter: fm as OkfFrontmatter, body: match[2] };
}

// Extract links to other .md files within the same OKF bundle
function extractMarkdownLinks(body: string): string[] {
  const seen  = new Set<string>();
  const linkRe = /\[([^\]]+)\]\(([^)#\s]+\.md)[^)]*\)/g;
  let m: RegExpExecArray | null;

  while ((m = linkRe.exec(body)) !== null) {
    const href = m[2].replace(/^\.\//, "");
    if (!href.includes("/")) seen.add(href); // only same-directory links
  }

  return [...seen];
}

// ── OKF → KCP Conversion ─────────────────────────────────────────────────────

function suggestId(title: string | undefined, filename: string): string {
  const base = (title || basename(filename, ".md")).trim();
  return base
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "")
    .slice(0, 64) || basename(filename, ".md");
}

function mapOkfTypeToKind(okfType: string): string {
  const t = okfType.toLowerCase();
  if (t.includes("table")    || t.includes("schema")   ||
      t.includes("database") || t.includes("dataset")  ||
      t.includes("model")    || t.includes("entity"))   return "schema";
  if (t.includes("api")      || t.includes("service")  ||
      t.includes("endpoint") || t.includes("webhook"))  return "service";
  if (t.includes("policy")   || t.includes("rule")     ||
      t.includes("compliance") || t.includes("regulation") ||
      t.includes("runbook")  || t.includes("playbook")) return "policy";
  return "knowledge";
}

function buildIntent(fm: OkfFrontmatter): { intent: string; isWeak: boolean } {
  const subject = fm.title || fm.type || "this resource";
  const kind    = mapOkfTypeToKind(fm.type || "");

  // If description is already phrased as a question, use it directly
  if (fm.description) {
    const d = fm.description.trim();
    if (d.endsWith("?")) return { intent: d, isWeak: false };

    // Convert statement to question form based on kind
    switch (kind) {
      case "schema":
        return { intent: `What is the schema, structure, and content of ${subject}?`, isWeak: true };
      case "service":
        return { intent: `What does the ${subject} provide and how is it used?`, isWeak: true };
      case "policy":
        return { intent: `What does the ${subject} require or govern?`, isWeak: true };
      default:
        return { intent: `What does ${subject} contain and when should I use it?`, isWeak: true };
    }
  }

  return { intent: `What is ${subject}?`, isWeak: true };
}

function computeContentHash(filePath: string): string {
  const content = readFileSync(filePath);
  return createHash("sha256").update(content).digest("hex");
}

function convertToUnit(
  doc: OkfDocument,
  absDir: string,
  okfFilenames: Set<string>,
): ImportedUnit {
  const { frontmatter: fm } = doc;

  const id       = suggestId(fm.title, doc.filename);
  const kind     = mapOkfTypeToKind(fm.type || "");
  const { intent, isWeak } = buildIntent(fm);

  // Triggers: OKF tags + the OKF type itself
  const triggerSet = new Set<string>();
  if (fm.type) triggerSet.add(fm.type);
  for (const t of fm.tags ?? []) {
    if (typeof t === "string") triggerSet.add(t);
  }
  const triggers = [...triggerSet];

  // validated: OKF timestamp is last-modified date — best available approximation
  const validated = fm.timestamp
    ? fm.timestamp.substring(0, 10)  // truncate to YYYY-MM-DD
    : undefined;

  // depends_on: body links that resolve to other OKF docs in this bundle
  const dependsOn = doc.bodyLinks
    .filter(link => okfFilenames.has(link))
    .map(link => suggestId(undefined, link));

  const contentHash = computeContentHash(join(absDir, doc.filename));

  return {
    id,
    path: doc.relPath,
    intent,
    intentIsWeak: isWeak,
    kind,
    triggers,
    validated,
    dependsOn,
    contentHash,
  };
}

// ── Manifest Generation ──────────────────────────────────────────────────────

function buildManifest(
  projectName: string,
  units: ImportedUnit[],
  absDir: string,
): { manifest: string; weakIntentCount: number; noTemporalCount: number } {
  const today = new Date().toISOString().substring(0, 10);
  let weakIntentCount  = 0;
  let noTemporalCount  = 0;

  const lines: string[] = [
    `# Generated by: kcp import-okf`,
    `# Source: OKF bundle — ${absDir}`,
    `# Date: ${today}`,
    `#`,
    `# Review checklist:`,
    `#   1. Search for "# TODO" and address each annotation`,
    `#   2. Add temporal.valid_from where content has an enforcement or effective date`,
    `#   3. Refine auto-generated intents into precise task-oriented questions`,
    `#   4. Run: kcp validate`,
    `#   5. Run: kcp sign --key <ed25519.pem> --key-id <id>`,
    ``,
    `kcp_version: "0.21"`,
    `project: ${sanitizeProjectName(projectName)}`,
    `version: "1.0.0"`,
    `updated: ${today}`,
    `language: en`,
    ``,
    `units:`,
  ];

  for (const unit of units) {
    if (unit.intentIsWeak) weakIntentCount++;

    lines.push(`  - id: ${unit.id}`);
    lines.push(`    path: ${unit.path}`);

    if (unit.intentIsWeak) {
      lines.push(`    # TODO: Refine intent — auto-generated from OKF type/title, not task-verified`);
    }
    lines.push(`    intent: "${escapeYamlString(unit.intent)}"`);
    lines.push(`    kind: ${unit.kind}`);
    lines.push(`    scope: global`);
    lines.push(`    audience: [human, agent]`);

    if (unit.triggers.length > 0) {
      const ts = unit.triggers.map(t => `"${escapeYamlString(t)}"`).join(", ");
      lines.push(`    triggers: [${ts}]`);
    }

    if (unit.validated) {
      lines.push(`    validated: ${unit.validated}  # OKF timestamp (last-modified, not enforcement date)`);
    }

    if (unit.dependsOn.length > 0) {
      lines.push(`    depends_on: [${unit.dependsOn.join(", ")}]`);
    }

    lines.push(`    content_hash:`);
    lines.push(`      algorithm: sha256`);
    lines.push(`      value: ${unit.contentHash}`);

    // Temporal stub — always emit as commented-out so reviewers know it exists
    noTemporalCount++;
    lines.push(`    # temporal:`);
    lines.push(`    #   valid_from: null  # TODO: Set if content has an enforcement/effective date`);

    lines.push(``);
  }

  return { manifest: lines.join("\n") + "\n", weakIntentCount, noTemporalCount };
}

function sanitizeProjectName(name: string): string {
  // Quote if it contains special YAML characters
  if (/[:#\[\]{},&*?|<>=!%@`]/.test(name) || name.includes(" ")) {
    return `"${name.replace(/"/g, '\\"')}"`;
  }
  return name;
}

function escapeYamlString(s: string): string {
  return s.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
}

// ── Summary Output ───────────────────────────────────────────────────────────

function printSummary(
  absOutput: string,
  unitCount: number,
  weakIntentCount: number,
  noTemporalCount: number,
): void {
  const relOut = relative(process.cwd(), absOutput);

  process.stdout.write(green(`\n✓ Import complete\n`));
  process.stdout.write(`  ${bold(String(unitCount))} unit(s) → ${cyan(relOut)}\n`);
  process.stdout.write(`\n`);

  if (weakIntentCount > 0) {
    process.stdout.write(
      yellow(`  ⚠  ${weakIntentCount} intent(s) auto-generated`) +
      dim(` — search for "# TODO: Refine intent" to review\n`),
    );
  }
  if (noTemporalCount > 0) {
    process.stdout.write(
      dim(`  ℹ  ${noTemporalCount} unit(s) have no temporal data`) +
      dim(` — add valid_from if content has enforcement dates\n`),
    );
  }

  process.stdout.write(`\n`);
  process.stdout.write(dim(`Next steps:\n`));
  process.stdout.write(dim(`  kcp validate ${relOut}\n`));
  process.stdout.write(dim(`  kcp sign ${relOut} --key <ed25519.pem> --key-id <id>\n`));
  process.stdout.write(`\n`);
}
