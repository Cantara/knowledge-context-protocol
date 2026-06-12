// §15.11 negative-space filtering — soft demotion vs strict exclusion.
// §15.13 temporal query filtering.

import { describe, expect, it } from "vitest";
import { applyNotFor, isTemporallyActive, matchNotFor } from "./query.js";
import type { KnowledgeUnit } from "./model.js";

function unit(over: Partial<KnowledgeUnit>): KnowledgeUnit {
  return {
    id: "u",
    path: "docs/u.md",
    intent: "intent",
    scope: "project",
    audience: ["agent"],
    triggers: [],
    depends_on: [],
    ...over,
  } as KnowledgeUnit;
}

const scored = (id: string, score: number) => ({
  id, intent: "i", path: "p", audience: [], score, match_reason: ["intent"], caution: null,
});

describe("§15.11 negative-space filtering", () => {
  it("matches not_for entries case-insensitively by substring", () => {
    const u = unit({ not_for: ["End-user login and session management"] });
    expect(matchNotFor(u, ["login"])).toBe("End-user login and session management");
    expect(matchNotFor(u, ["gerber"])).toBeNull();
    expect(matchNotFor(unit({}), ["login"])).toBeNull();
  });

  it("soft-demotes and annotates when not_for matches without strict", () => {
    const u = unit({ id: "api-security", not_for: ["end-user login"] });
    const out = applyNotFor([u], [scored("api-security", 8)], ["login"]);
    expect(out).toHaveLength(1);
    expect(out[0].score).toBe(4); // demoted, still present
    expect(out[0].caution).toBe("not_for match: 'end-user login'");
  });

  it("excludes entirely when not_for matches and not_for_strict is true", () => {
    const u = unit({ id: "v1-migration", not_for: ["initial adoption"], not_for_strict: true });
    const out = applyNotFor([u], [scored("v1-migration", 8)], ["adoption"]);
    expect(out).toHaveLength(0);
  });

  it("leaves non-matching units untouched", () => {
    const u = unit({ id: "gerber", not_for: ["frontend authentication"] });
    const out = applyNotFor([u], [scored("gerber", 5)], ["gerber", "drc"]);
    expect(out[0].score).toBe(5);
    expect(out[0].caution).toBeNull();
  });
});

describe("§15.13 temporal query filtering", () => {
  it("returns true for units without a temporal block", () => {
    const u = unit({});
    expect(isTemporallyActive(u, "2026-06-12")).toBe(true);
  });

  it("returns false when valid_from is in the future", () => {
    const u = unit({ temporal: { valid_from: "2099-01-01" } });
    expect(isTemporallyActive(u, "2026-06-12")).toBe(false);
  });

  it("returns false when valid_until is in the past", () => {
    const u = unit({ temporal: { valid_until: "2020-12-31" } });
    expect(isTemporallyActive(u, "2026-06-12")).toBe(false);
  });

  it("returns true when asOf is within the validity window", () => {
    const u = unit({ temporal: { valid_from: "2025-01-01", valid_until: "2027-12-31" } });
    expect(isTemporallyActive(u, "2026-06-12")).toBe(true);
  });

  it("returns true when temporal block is present but both bounds are null", () => {
    const u = unit({ temporal: {} });
    expect(isTemporallyActive(u, "2026-06-12")).toBe(true);
  });
});
