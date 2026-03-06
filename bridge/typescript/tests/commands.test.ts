import { describe, it, expect } from "vitest";
import { join } from "node:path";
import {
  loadCommandManifests,
  formatSyntaxBlock,
  lookupCommand,
  type CommandManifest,
} from "../src/commands.js";

const COMMANDS_DIR = join(import.meta.dirname, "fixtures/commands");

function makeManifest(overrides: Partial<CommandManifest> = {}): CommandManifest {
  return {
    command: "git",
    subcommand: "commit",
    platform: "all",
    description: "Record staged changes to the repository",
    syntax: {
      usage: "git commit [<options>]",
      key_flags: [
        {
          flag: "-m '<message>'",
          description: "Commit message inline",
          use_when: "Simple one-line commits",
        },
      ],
      preferred_invocations: [
        {
          invocation: "git commit -m 'Add feature X'",
          use_when: "Standard single-line commit",
        },
      ],
    },
    ...overrides,
  };
}

describe("loadCommandManifests", () => {
  it("loads all YAML files from the fixtures directory", () => {
    const map = loadCommandManifests(COMMANDS_DIR);
    // git-commit.yaml, git-status.yaml, docker.yaml
    expect(map.size).toBe(3);
    expect(map.has("git commit")).toBe(true);
    expect(map.has("git status")).toBe(true);
    expect(map.has("docker")).toBe(true);
  });

  it("parses command and subcommand correctly", () => {
    const map = loadCommandManifests(COMMANDS_DIR);
    const gitCommit = map.get("git commit")!;
    expect(gitCommit.command).toBe("git");
    expect(gitCommit.subcommand).toBe("commit");
    expect(gitCommit.description).toBe("Record staged changes to the repository");
  });

  it("parses a base command without subcommand", () => {
    const map = loadCommandManifests(COMMANDS_DIR);
    const docker = map.get("docker")!;
    expect(docker.command).toBe("docker");
    expect(docker.subcommand).toBeUndefined();
  });

  it("parses key_flags and preferred_invocations", () => {
    const map = loadCommandManifests(COMMANDS_DIR);
    const gitCommit = map.get("git commit")!;
    expect(gitCommit.syntax.key_flags.length).toBeGreaterThanOrEqual(1);
    expect(gitCommit.syntax.key_flags[0].flag).toBe("-m '<message>'");
    expect(gitCommit.syntax.preferred_invocations.length).toBeGreaterThanOrEqual(1);
  });

  it("returns empty map for nonexistent directory", () => {
    const map = loadCommandManifests("/nonexistent/dir");
    expect(map.size).toBe(0);
  });

  it("returns empty map for directory with no YAML files", () => {
    // The minimal fixture dir has only knowledge.yaml and README.md,
    // but loadCommandManifests skips files without 'command' + 'syntax' keys
    const map = loadCommandManifests(
      join(import.meta.dirname, "fixtures/minimal")
    );
    expect(map.size).toBe(0);
  });
});

describe("formatSyntaxBlock", () => {
  it("formats a manifest with all sections", () => {
    const manifest = makeManifest();
    const block = formatSyntaxBlock(manifest);

    expect(block).toContain("[kcp] git commit: Record staged changes");
    expect(block).toContain("Usage: git commit [<options>]");
    expect(block).toContain("Key flags:");
    expect(block).toContain("-m '<message>': Commit message inline");
    expect(block).toContain("Preferred:");
    expect(block).toContain("git commit -m 'Add feature X'");
  });

  it("formats command name correctly for base command without subcommand", () => {
    const manifest = makeManifest({
      command: "docker",
      subcommand: undefined,
      description: "Container runtime",
      syntax: {
        usage: "docker [OPTIONS] COMMAND",
        key_flags: [],
        preferred_invocations: [],
      },
    });
    const block = formatSyntaxBlock(manifest);

    expect(block).toContain("[kcp] docker: Container runtime");
    expect(block).not.toContain("Key flags:");
    expect(block).not.toContain("Preferred:");
  });

  it("includes arrow separator in key_flags use_when", () => {
    const manifest = makeManifest();
    const block = formatSyntaxBlock(manifest);
    // Unicode arrow →
    expect(block).toContain("\u2192 Simple one-line commits");
  });

  it("includes hash separator in preferred_invocations use_when", () => {
    const manifest = makeManifest();
    const block = formatSyntaxBlock(manifest);
    expect(block).toContain("# Standard single-line commit");
  });
});

describe("lookupCommand", () => {
  it("finds exact match for command + subcommand", () => {
    const map = loadCommandManifests(COMMANDS_DIR);
    const result = lookupCommand(map, "git commit");
    expect(result).not.toBeNull();
    expect(result!.command).toBe("git");
    expect(result!.subcommand).toBe("commit");
  });

  it("finds exact match for base command", () => {
    const map = loadCommandManifests(COMMANDS_DIR);
    const result = lookupCommand(map, "docker");
    expect(result).not.toBeNull();
    expect(result!.command).toBe("docker");
  });

  it("is case-insensitive", () => {
    const map = loadCommandManifests(COMMANDS_DIR);
    const result = lookupCommand(map, "Git Commit");
    expect(result).not.toBeNull();
    expect(result!.subcommand).toBe("commit");
  });

  it("returns null for unknown command", () => {
    const map = loadCommandManifests(COMMANDS_DIR);
    expect(lookupCommand(map, "kubectl")).toBeNull();
  });

  it("falls back to prefix match: 'git' returns a git subcommand", () => {
    const map = loadCommandManifests(COMMANDS_DIR);
    // No base "git" manifest, but there are "git commit" and "git status"
    const result = lookupCommand(map, "git");
    expect(result).not.toBeNull();
    expect(result!.command).toBe("git");
  });

  it("trims whitespace from query", () => {
    const map = loadCommandManifests(COMMANDS_DIR);
    const result = lookupCommand(map, "  docker  ");
    expect(result).not.toBeNull();
    expect(result!.command).toBe("docker");
  });
});
