// kcp validate — lint a knowledge.yaml manifest with colored terminal output

import { dirname, resolve } from "node:path";
import { parseFile } from "./parser.js";
import { validate } from "./validator.js";

const green = (s: string) => `\x1b[32m${s}\x1b[0m`;
const red = (s: string) => `\x1b[31m${s}\x1b[0m`;
const yellow = (s: string) => `\x1b[33m${s}\x1b[0m`;
const bold = (s: string) => `\x1b[1m${s}\x1b[0m`;
const dim = (s: string) => `\x1b[2m${s}\x1b[0m`;

export function runValidate(manifestPath: string): void {
  let manifest;
  try {
    manifest = parseFile(manifestPath);
  } catch (err) {
    process.stderr.write(red(`Error: ${err instanceof Error ? err.message : String(err)}\n`));
    process.exit(1);
  }

  const manifestDir = dirname(resolve(manifestPath));
  const result = validate(manifest, manifestDir);

  const unitCount = manifest.units.length;
  const header = `${bold(manifestPath)} ${dim(`(${unitCount} unit${unitCount === 1 ? "" : "s"}, kcp_version: ${manifest.kcp_version ?? "unset"})`)}`;
  process.stdout.write(`\n${header}\n\n`);

  if (result.isValid && result.warnings.length === 0) {
    process.stdout.write(green("✓ Valid — no errors or warnings\n\n"));
    return;
  }

  if (result.errors.length > 0) {
    process.stdout.write(bold(red(`✗ ${result.errors.length} error${result.errors.length === 1 ? "" : "s"}:\n`)));
    for (const msg of result.errors) {
      process.stdout.write(`  ${red("●")} ${msg}\n`);
    }
    process.stdout.write("\n");
  }

  if (result.warnings.length > 0) {
    process.stdout.write(bold(yellow(`⚠ ${result.warnings.length} warning${result.warnings.length === 1 ? "" : "s"}:\n`)));
    for (const msg of result.warnings) {
      process.stdout.write(`  ${yellow("●")} ${msg}\n`);
    }
    process.stdout.write("\n");
  }

  if (!result.isValid) {
    process.exit(1);
  }
}
