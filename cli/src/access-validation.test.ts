// Tests for the §4.11 access/auth-axis warnings — 'access' declares the
// authentication gate only. An auth block whose only method is 'none' can never
// satisfy a protected unit (the payment-as-access anti-pattern surfaced by
// kcp-agent interop testing, issue #115).

import { describe, expect, it } from "vitest";
import { parseDict } from "./parser.js";
import { validate } from "./validator.js";

function validateManifest(extra: Record<string, unknown>) {
  return parseAndValidate({ kcp_version: "0.25", project: "test", version: "1.0.0", ...extra });
}

function parseAndValidate(raw: Record<string, unknown>) {
  return validate(parseDict(raw));
}

const unit = (id: string, extra: Record<string, unknown> = {}) => ({
  id,
  path: `docs/${id}.md`,
  intent: `Unit ${id}`,
  scope: "project",
  audience: ["agent"],
  ...extra,
});

const NONE_ONLY_WARNING = "declares only method 'none'";
const NO_AUTH_WARNING = "no 'auth' block is declared";

describe("access vs auth coherence (§4.11)", () => {
  it("warns when protected units exist and the auth block declares only method 'none'", () => {
    const r = validateManifest({
      auth: { methods: [{ type: "none" }] },
      units: [unit("paid", { access: "restricted", payment: { default_tier: "metered" } })],
    });
    expect(r.warnings.some((w) => w.includes(NONE_ONLY_WARNING))).toBe(true);
    expect(r.isValid).toBe(true); // warning, not error
  });

  it("does not warn when the auth block declares a real method", () => {
    const r = validateManifest({
      auth: { methods: [{ type: "none" }, { type: "api_key" }] },
      units: [unit("internal", { access: "restricted" })],
    });
    expect(r.warnings.some((w) => w.includes(NONE_ONLY_WARNING))).toBe(false);
  });

  it("does not warn when no unit is access-protected (anonymous-paid is access: public)", () => {
    const r = validateManifest({
      auth: { methods: [{ type: "none" }] },
      units: [unit("paid", { payment: { default_tier: "metered" } })],
    });
    expect(r.warnings.some((w) => w.includes(NONE_ONLY_WARNING))).toBe(false);
  });

  it("still warns when protected units exist with no auth block at all (existing §7 rule)", () => {
    const r = validateManifest({
      units: [unit("internal", { access: "authenticated" })],
    });
    expect(r.warnings.some((w) => w.includes(NO_AUTH_WARNING))).toBe(true);
    expect(r.warnings.some((w) => w.includes(NONE_ONLY_WARNING))).toBe(false);
  });
});
