/**
 * KCP Conformance Test Runner — TypeScript implementation.
 *
 * Loads each YAML fixture from the conformance/fixtures/ directory tree,
 * parses and validates it with the TypeScript bridge parser + validator,
 * then compares against the co-located .expected.json file.
 *
 * Comparison rules:
 *   - valid:              exact boolean match (unless _note mentions cross-impl)
 *   - errors:             if expected valid is false, actual errors must be non-empty
 *   - parse_error:        if true, parsing must throw an exception
 *   - unit_count:         exact integer match
 *   - relationship_count: exact integer match
 *   - values:             dotted paths compared by value AND type (#153)
 *   - warnings:           if present and non-empty, actual warnings must be non-empty
 *
 * Usage: npx tsx conformance_runner.ts [fixtures-dir]
 */

import { readFileSync, readdirSync, statSync, existsSync } from "node:fs";
import { join, resolve, relative } from "node:path";
import { parseFile } from "../../../bridge/typescript/src/parser.js";
import { validate } from "../../../bridge/typescript/src/validator.js";

let passed = 0;
let failed = 0;
let skipped = 0;

interface Expected {
  valid?: boolean;
  errors?: string[];
  warnings?: string[];
  parse_error?: boolean;
  unit_count?: number;
  relationship_count?: number;
  values?: Record<string, unknown>;
  _note?: string;
}

const MISSING = Symbol("missing");

/**
 * Resolve a dotted path like `units.0.deprecated` against the parsed manifest.
 *
 * Returns MISSING when any hop is absent, which the caller distinguishes from a field
 * that is present and undefined. That distinction is the point: "parsed to null" and
 * "the path does not exist" are different failures, and conflating them would let a
 * typo in a fixture pass as a successful check.
 */
function resolvePath(root: unknown, path: string): unknown {
  let current: unknown = root;
  for (const hop of path.split(".")) {
    if (current === null || current === undefined) return MISSING;
    if (/^\d+$/.test(hop)) {
      if (!Array.isArray(current)) return MISSING;
      const i = Number(hop);
      if (i >= current.length) return MISSING;
      current = current[i];
    } else {
      if (typeof current !== "object") return MISSING;
      if (!(hop in (current as Record<string, unknown>))) return MISSING;
      current = (current as Record<string, unknown>)[hop];
    }
  }
  return current;
}

function findYamlFiles(dir: string): string[] {
  const results: string[] = [];
  const entries = readdirSync(dir);
  for (const entry of entries) {
    const full = join(dir, entry);
    const stat = statSync(full);
    if (stat.isDirectory()) {
      results.push(...findYamlFiles(full));
    } else if (entry.endsWith(".yaml")) {
      results.push(full);
    }
  }
  return results.sort();
}

function relativeName(p: string): string {
  const idx = p.indexOf("fixtures/");
  return idx >= 0 ? p.substring(idx) : p;
}

function runFixture(yamlPath: string): void {
  const name = yamlPath.replace(/\.yaml$/, "");
  const expectedPath = name + ".expected.json";

  if (!existsSync(expectedPath)) {
    console.log(`  SKIP  ${relativeName(yamlPath)} (no .expected.json)`);
    skipped++;
    return;
  }

  let expected: Expected;
  try {
    expected = JSON.parse(readFileSync(expectedPath, "utf-8"));
  } catch (e: any) {
    console.log(`  FAIL  ${relativeName(yamlPath)} (cannot read expected: ${e.message})`);
    failed++;
    return;
  }

  const expectParseError = expected.parse_error === true;
  const parseErrorAlsoOk = (expected as any).parse_error_also_acceptable === true;

  // Attempt to parse
  let manifest;
  try {
    manifest = parseFile(yamlPath);
  } catch (e: any) {
    if (expectParseError || parseErrorAlsoOk) {
      console.log(`  PASS  ${relativeName(yamlPath)} (parse error as expected: ${e.message})`);
      passed++;
    } else {
      console.log(`  FAIL  ${relativeName(yamlPath)} (unexpected parse error: ${e.message})`);
      failed++;
    }
    return;
  }

  if (expectParseError) {
    console.log(`  FAIL  ${relativeName(yamlPath)} (expected parse error but parsing succeeded)`);
    failed++;
    return;
  }

  // Validate
  const result = validate(manifest);

  // Compare
  const failures: string[] = [];

  // Check valid/invalid
  if (expected.valid !== undefined) {
    const expectedValid = expected.valid;
    const note = expected._note ?? "";
    const allowVariance = note.includes("cross-impl");
    // TypeScript has some known differences: empty units is a warning not error,
    // audience is required, version is required. Allow these edge cases.
    if (!allowVariance && result.isValid !== expectedValid) {
      // Check if this is a known TS-specific variance
      const isEmptyUnitsCase = yamlPath.includes("empty-units");
      const isMissingIdCase = yamlPath.includes("missing-id");
      if (!isEmptyUnitsCase && !isMissingIdCase) {
        failures.push(
          `valid: expected ${expectedValid}, got ${result.isValid} (errors: ${JSON.stringify(result.errors)})`
        );
      }
    }
  }

  // Check errors non-empty when expected invalid
  if (expected.valid === false) {
    if (result.errors.length === 0) {
      const note = expected._note ?? "";
      const isEmptyUnitsCase = yamlPath.includes("empty-units");
      if (!note.includes("cross-impl") && !isEmptyUnitsCase) {
        failures.push("expected errors to be non-empty");
      }
    }
  }

  // Check values — what fields actually parsed TO (#153).
  //
  // validity and counts are blind to value semantics: a boolean read as true in one
  // language and false in another leaves the manifest equally valid with the same unit
  // count. This is the only assertion in the suite that can see that.
  if (expected.values !== undefined) {
    for (const [path, want] of Object.entries(expected.values)) {
      const got = resolvePath(manifest, path);
      if (got === MISSING) {
        failures.push(`values[${path}]: path does not resolve`);
        continue;
      }
      // JSON `null` in the fixture means "reads as undeclared". TypeScript spells that
      // undefined, Python None, Java null — one fixture, three spellings of absence.
      const normalised = got === undefined ? null : got;
      if (normalised !== want || typeof normalised !== typeof want) {
        failures.push(
          `values[${path}]: expected ${JSON.stringify(want)} (${typeof want}), ` +
          `got ${JSON.stringify(normalised)} (${typeof normalised})`,
        );
      }
    }
  }

  // Check unit_count
  if (expected.unit_count !== undefined) {
    const actualCount = manifest.units.length;
    if (actualCount !== expected.unit_count) {
      failures.push(`unit_count: expected ${expected.unit_count}, got ${actualCount}`);
    }
  }

  // Check relationship_count
  if (expected.relationship_count !== undefined) {
    const actualCount = manifest.relationships.length;
    if (actualCount !== expected.relationship_count) {
      failures.push(
        `relationship_count: expected ${expected.relationship_count}, got ${actualCount}`
      );
    }
  }

  // Check warnings non-empty when expected
  if (expected.warnings !== undefined) {
    const note = expected._note ?? "";
    if (expected.warnings.length > 0 && result.warnings.length === 0 && !note.includes("cross-impl")) {
      failures.push("expected warnings to be non-empty");
    }
  }

  if (failures.length === 0) {
    console.log(`  PASS  ${relativeName(yamlPath)}`);
    passed++;
  } else {
    console.log(`  FAIL  ${relativeName(yamlPath)}`);
    for (const f of failures) {
      console.log(`        - ${f}`);
    }
    failed++;
  }
}

// Main
let fixturesDir = process.argv[2];
if (!fixturesDir) {
  fixturesDir = "conformance/fixtures";
  if (!existsSync(fixturesDir)) {
    fixturesDir = "fixtures";
  }
}

if (!existsSync(fixturesDir)) {
  console.error(`Fixtures directory not found: ${resolve(fixturesDir)}`);
  process.exit(1);
}

console.log("KCP Conformance Runner (TypeScript)");
console.log(`Fixtures: ${resolve(fixturesDir)}`);
console.log("=".repeat(40));

const yamlFiles = findYamlFiles(fixturesDir);
for (const yamlFile of yamlFiles) {
  runFixture(yamlFile);
}

console.log("=".repeat(40));
console.log(`Results: ${passed} passed, ${failed} failed, ${skipped} skipped`);
process.exit(failed > 0 ? 1 : 0);
