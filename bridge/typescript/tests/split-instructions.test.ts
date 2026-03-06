import { describe, it, expect, afterEach } from "vitest";
import { join } from "node:path";
import { existsSync, readFileSync, rmSync, mkdirSync } from "node:fs";
import { generateSplitInstructions } from "../src/split-instructions.js";

const FULL_FIXTURE = join(import.meta.dirname, "fixtures/full/knowledge.yaml");
const MINIMAL_FIXTURE = join(import.meta.dirname, "fixtures/minimal/knowledge.yaml");

// Use a temp directory inside the test fixtures area
const TMP_DIR = join(import.meta.dirname, "fixtures/.tmp-split-test");

afterEach(() => {
  if (existsSync(TMP_DIR)) {
    rmSync(TMP_DIR, { recursive: true, force: true });
  }
});

describe("generateSplitInstructions", () => {
  it("creates output directory if it does not exist", () => {
    const outputDir = join(TMP_DIR, "new-dir");

    generateSplitInstructions(MINIMAL_FIXTURE, outputDir, {
      splitBy: "none",
      audience: undefined,
    });

    expect(existsSync(outputDir)).toBe(true);
  });

  it("writes a single file when splitBy is 'none'", () => {
    generateSplitInstructions(FULL_FIXTURE, TMP_DIR, {
      splitBy: "none",
    });

    expect(existsSync(join(TMP_DIR, "all.instructions.md"))).toBe(true);
    const content = readFileSync(
      join(TMP_DIR, "all.instructions.md"),
      "utf-8"
    );
    expect(content).toContain("applyTo:");
    expect(content).toContain("| spec |");
    expect(content).toContain("| api-schema |");
    expect(content).toContain("| guide |");
  });

  it("produces files with valid applyTo frontmatter", () => {
    generateSplitInstructions(FULL_FIXTURE, TMP_DIR, {
      splitBy: "scope",
    });

    // Full fixture has global, project, and module scopes
    // Check at least one file was created with frontmatter
    const files = readdirSync(TMP_DIR).filter((f) =>
      f.endsWith(".instructions.md")
    );
    expect(files.length).toBeGreaterThan(0);

    for (const file of files) {
      const content = readFileSync(join(TMP_DIR, file), "utf-8");
      expect(content.startsWith("---\n")).toBe(true);
      expect(content).toContain("applyTo:");
      // Ensure frontmatter closes
      const secondDash = content.indexOf("---", 4);
      expect(secondDash).toBeGreaterThan(4);
    }
  });

  it("splits by scope into separate files", () => {
    generateSplitInstructions(FULL_FIXTURE, TMP_DIR, {
      splitBy: "scope",
    });

    // Should have files for global, project, module scopes
    expect(existsSync(join(TMP_DIR, "global.instructions.md"))).toBe(true);
    expect(existsSync(join(TMP_DIR, "project.instructions.md"))).toBe(true);
    expect(existsSync(join(TMP_DIR, "module.instructions.md"))).toBe(true);

    const globalContent = readFileSync(
      join(TMP_DIR, "global.instructions.md"),
      "utf-8"
    );
    expect(globalContent).toContain("| spec |");
    expect(globalContent).not.toContain("| api-schema |");

    const moduleContent = readFileSync(
      join(TMP_DIR, "module.instructions.md"),
      "utf-8"
    );
    expect(moduleContent).toContain("| api-schema |");
  });

  it("splits by unit into one file per unit", () => {
    generateSplitInstructions(FULL_FIXTURE, TMP_DIR, {
      splitBy: "unit",
    });

    expect(existsSync(join(TMP_DIR, "spec.instructions.md"))).toBe(true);
    expect(existsSync(join(TMP_DIR, "api-schema.instructions.md"))).toBe(true);
    expect(existsSync(join(TMP_DIR, "guide.instructions.md"))).toBe(true);
  });

  it("includes relevant relationships only", () => {
    generateSplitInstructions(FULL_FIXTURE, TMP_DIR, {
      splitBy: "unit",
    });

    const specContent = readFileSync(
      join(TMP_DIR, "spec.instructions.md"),
      "utf-8"
    );
    // spec is referenced by api-schema and guide relationships
    expect(specContent).toContain("Relationships");
    expect(specContent).toContain("spec");

    const guideContent = readFileSync(
      join(TMP_DIR, "guide.instructions.md"),
      "utf-8"
    );
    expect(guideContent).toContain("guide");
  });

  it("filters by audience", () => {
    generateSplitInstructions(FULL_FIXTURE, TMP_DIR, {
      splitBy: "none",
      audience: "agent",
    });

    const content = readFileSync(
      join(TMP_DIR, "all.instructions.md"),
      "utf-8"
    );
    expect(content).toContain("| spec |");
    expect(content).toContain("| api-schema |");
    expect(content).not.toContain("| guide |"); // guide has no "agent" audience
  });

  it("includes project name in the generated content", () => {
    generateSplitInstructions(FULL_FIXTURE, TMP_DIR, {
      splitBy: "none",
    });

    const content = readFileSync(
      join(TMP_DIR, "all.instructions.md"),
      "utf-8"
    );
    expect(content).toContain("full-example");
  });
});

// Import readdirSync for use in tests
import { readdirSync } from "node:fs";
