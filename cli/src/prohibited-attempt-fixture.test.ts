import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * §17 `prohibited_attempt_events` (v0.32.1, RFC-0030) — pins the canonical wire-format
 * fixture that emitters (e.g. a governance adjudicator) and ingesters (e.g. a dashboard
 * /trace sink) test against. An emitter and an ingester are conformant when this fixture
 * round-trips between them; this test keeps the fixture from drifting away from §17.
 */

const FIXTURE = join(
  dirname(fileURLToPath(import.meta.url)),
  "../../conformance/fixtures/observability/prohibited-attempt.json"
);

describe("§17 prohibited_attempt wire-format fixture", () => {
  const ev = JSON.parse(readFileSync(FIXTURE, "utf8"));

  it("declares the kind discriminator", () => {
    expect(ev.kind).toBe("prohibited_attempt");
  });

  it("carries every §17 column, with the nullable ones present-but-null when unset", () => {
    for (const key of [
      "timestamp", "unit_id", "playbook_id", "step_id", "dimension",
      "token", "matched_pattern", "binding_source", "acknowledged_by", "correlation_id",
    ]) {
      expect(Object.hasOwn(ev, key), `missing §17 field '${key}'`).toBe(true);
    }
  });

  it("constrains the enumerated fields", () => {
    expect(["tools", "paths", "capabilities"]).toContain(ev.dimension);
    expect(["skill", "playbook", "both"]).toContain(ev.binding_source);
  });

  it("shows the glob case the table exists to record: token ≠ matched_pattern", () => {
    expect(ev.dimension).toBe("paths");
    expect(ev.token).not.toBe(ev.matched_pattern);
  });

  it("is notify-only in its example: nothing acknowledged, nothing enacted", () => {
    expect(ev.acknowledged_by).toBeNull();
  });
});
