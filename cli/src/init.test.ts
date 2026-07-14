// Tests for kcp init — scaffold output must always be valid YAML and a valid
// manifest. Regression: v0.26.0 embedded filename-derived intents containing
// double quotes without escaping, producing a scaffold that failed `kcp
// validate` (bad indentation of a mapping entry).

import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import yaml from "js-yaml";

import { runInit, SCAFFOLD_KCP_VERSION } from "./init.js";
import { parseFile } from "./parser.js";
import { validate } from "./validator.js";

let dir: string;
let prevCwd: string;

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), "kcp-init-test-"));
  prevCwd = process.cwd();
  process.chdir(dir);
});

afterEach(() => {
  process.chdir(prevCwd);
  rmSync(dir, { recursive: true, force: true });
});

// runInit is non-interactive when stdin is not a TTY (vitest) or yes=true.
async function scaffold(): Promise<string> {
  await runInit("knowledge.yaml", true);
  return readFileSync(join(dir, "knowledge.yaml"), "utf8");
}

describe("kcp init scaffold", () => {
  it("escapes double quotes in filename-derived intents (regression: 0.26.0)", async () => {
    mkdirSync(join(dir, "docs"));
    // "rate-limits.md" → fallback intent: What is covered in "rate limits"?
    writeFileSync(join(dir, "docs", "rate-limits.md"), "# API Rate Limits\n");

    const text = await scaffold();
    const doc = yaml.load(text) as { units: Array<{ intent: string }> };
    expect(doc.units).toHaveLength(1);
    expect(doc.units[0].intent).toBe('What is covered in "rate limits"?');
  });

  it("produces a manifest that passes validation", async () => {
    mkdirSync(join(dir, "docs"));
    writeFileSync(join(dir, "README.md"), "# Demo\n");
    writeFileSync(join(dir, "docs", "rate-limits.md"), "# API Rate Limits\n");

    await scaffold();
    const manifest = parseFile(join(dir, "knowledge.yaml"));
    const result = validate(manifest, dir);
    expect(result.errors).toEqual([]);
  });

  it("declares the current scaffold kcp_version", async () => {
    const text = await scaffold();
    const doc = yaml.load(text) as { kcp_version: string };
    expect(doc.kcp_version).toBe(SCAFFOLD_KCP_VERSION);
  });

  it("escapes double quotes in the project name", async () => {
    // Project name derives from the cwd basename; simulate a quoted name via
    // a directory literally containing a quote character.
    const quoted = join(dir, 'my "quoted" project');
    mkdirSync(quoted);
    process.chdir(quoted);
    await runInit("knowledge.yaml", true);
    const text = readFileSync(join(quoted, "knowledge.yaml"), "utf8");
    const doc = yaml.load(text) as { project: string };
    expect(doc.project).toBe('my "quoted" project');
  });
});
