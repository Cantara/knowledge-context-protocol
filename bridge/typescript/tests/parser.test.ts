import { describe, it, expect } from "vitest";
import { join } from "node:path";
import { parseDict, parseFile, validateUnitPath } from "../src/parser.js";

const MINIMAL_DIR = join(import.meta.dirname, "fixtures/minimal");
const FULL_DIR = join(import.meta.dirname, "fixtures/full");

describe("validateUnitPath", () => {
  it("accepts normal relative paths", () => {
    expect(validateUnitPath("README.md")).toBe("README.md");
    expect(validateUnitPath("docs/spec.md")).toBe("docs/spec.md");
    expect(validateUnitPath("a/b/c.yaml")).toBe("a/b/c.yaml");
  });

  it("rejects absolute paths", () => {
    expect(() => validateUnitPath("/etc/passwd")).toThrow("relative");
    expect(() => validateUnitPath("\\windows\\system32")).toThrow("relative");
  });

  it("rejects path traversal", () => {
    expect(() => validateUnitPath("../secret.txt")).toThrow("escapes");
    expect(() => validateUnitPath("docs/../../etc/passwd")).toThrow("escapes");
  });

  it("accepts paths with internal dots that resolve safely", () => {
    // a/./b.md normalizes to a/b.md — safe
    expect(validateUnitPath("a/./b.md")).toBe("a/./b.md");
  });
});

describe("parseDict", () => {
  it("parses a minimal manifest", () => {
    const manifest = parseDict({
      project: "test",
      version: "1.0.0",
      units: [
        {
          id: "overview",
          path: "README.md",
          intent: "What is this?",
          scope: "global",
          audience: ["human", "agent"],
        },
      ],
    });

    expect(manifest.project).toBe("test");
    expect(manifest.version).toBe("1.0.0");
    expect(manifest.units).toHaveLength(1);
    expect(manifest.units[0].id).toBe("overview");
    expect(manifest.relationships).toEqual([]);
  });

  it("defaults arrays to empty", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [
        {
          id: "u",
          path: "f.md",
          intent: "i",
          scope: "global",
          audience: ["human"],
        },
      ],
    });
    expect(manifest.units[0].depends_on).toEqual([]);
    expect(manifest.units[0].triggers).toEqual([]);
    expect(manifest.relationships).toEqual([]);
  });

  it("normalizes Date objects to ISO strings", () => {
    const d = new Date("2026-02-28T12:00:00Z");
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      updated: d,
      units: [
        {
          id: "u",
          path: "f.md",
          intent: "i",
          scope: "global",
          audience: ["agent"],
          validated: d,
        },
      ],
    });
    expect(manifest.updated).toBe("2026-02-28");
    expect(manifest.units[0].validated).toBe("2026-02-28");
  });

  it("handles relationships with from/to/type", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [],
      relationships: [{ from: "a", to: "b", type: "context" }],
    });
    expect(manifest.relationships[0]).toEqual({
      from_id: "a",
      to_id: "b",
      type: "context",
    });
  });
});

describe("parseDelegation", () => {
  it("parses root-level delegation block", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      delegation: {
        max_depth: 2,
        require_capability_attenuation: true,
        audit_chain: false,
        human_in_the_loop: { required: true, approval_mechanism: "oauth_consent" },
      },
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.delegation).toBeDefined();
    expect(manifest.delegation?.max_depth).toBe(2);
    expect(manifest.delegation?.require_capability_attenuation).toBe(true);
    expect(manifest.delegation?.audit_chain).toBe(false);
    expect(manifest.delegation?.human_in_the_loop?.approval_mechanism).toBe("oauth_consent");
  });

  it("parses per-unit delegation override", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{
        id: "u",
        path: "f.md",
        intent: "i",
        scope: "global",
        audience: ["agent"],
        delegation: { max_depth: 0, human_in_the_loop: { required: false, approval_mechanism: "uma" } },
      }],
    });
    const u = manifest.units[0];
    expect(u.delegation).toBeDefined();
    expect(u.delegation?.max_depth).toBe(0);
    expect(u.delegation?.human_in_the_loop?.approval_mechanism).toBe("uma");
    expect(u.delegation?.require_capability_attenuation).toBeUndefined();
  });

  it("absent delegation is undefined", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.delegation).toBeUndefined();
    expect(manifest.units[0].delegation).toBeUndefined();
  });
});

describe("parseCompliance", () => {
  it("parses root-level compliance block", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      compliance: {
        data_residency: ["EU", "NO"],
        sensitivity: "confidential",
        regulations: ["GDPR", "NIS2"],
        restrictions: ["no_ai_training", "no_cross_border"],
      },
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.compliance).toBeDefined();
    expect(manifest.compliance?.data_residency).toEqual(["EU", "NO"]);
    expect(manifest.compliance?.sensitivity).toBe("confidential");
    expect(manifest.compliance?.regulations).toEqual(["GDPR", "NIS2"]);
    expect(manifest.compliance?.restrictions).toEqual(["no_ai_training", "no_cross_border"]);
  });

  it("parses per-unit compliance override", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{
        id: "u",
        path: "f.md",
        intent: "i",
        scope: "global",
        audience: ["agent"],
        compliance: { sensitivity: "restricted", regulations: ["AML5D"] },
      }],
    });
    const u = manifest.units[0];
    expect(u.compliance).toBeDefined();
    expect(u.compliance?.sensitivity).toBe("restricted");
    expect(u.compliance?.regulations).toEqual(["AML5D"]);
    expect(u.compliance?.data_residency).toBeUndefined();
  });

  it("absent compliance is undefined", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.compliance).toBeUndefined();
    expect(manifest.units[0].compliance).toBeUndefined();
  });

  it("compliance with only sensitivity leaves other fields undefined", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      compliance: { sensitivity: "internal" },
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.compliance?.sensitivity).toBe("internal");
    expect(manifest.compliance?.data_residency).toBeUndefined();
    expect(manifest.compliance?.regulations).toBeUndefined();
    expect(manifest.compliance?.restrictions).toBeUndefined();
  });
});

describe("parseTrust", () => {
  it("parses root-level trust block", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      trust: {
        provenance: {
          publisher: "Acme Corp",
          publisher_url: "https://acme.com",
          contact: "docs@acme.com",
        },
        audit: {
          agent_must_log: true,
          require_trace_context: false,
        },
      },
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.trust).toBeDefined();
    expect(manifest.trust?.provenance?.publisher).toBe("Acme Corp");
    expect(manifest.trust?.provenance?.publisher_url).toBe("https://acme.com");
    expect(manifest.trust?.provenance?.contact).toBe("docs@acme.com");
    expect(manifest.trust?.audit?.agent_must_log).toBe(true);
    expect(manifest.trust?.audit?.require_trace_context).toBe(false);
  });

  it("absent trust is undefined", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.trust).toBeUndefined();
  });
});

describe("parseAuth", () => {
  it("parses root-level auth block with multiple methods", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      auth: {
        methods: [
          { type: "oauth2", issuer: "https://auth.example.com", scopes: ["read:docs"] },
          { type: "api_key", header: "X-API-Key", registration_url: "https://example.com/register" },
          { type: "none" },
        ],
      },
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.auth).toBeDefined();
    expect(manifest.auth?.methods).toHaveLength(3);
    expect(manifest.auth?.methods[0].type).toBe("oauth2");
    expect(manifest.auth?.methods[0].issuer).toBe("https://auth.example.com");
    expect(manifest.auth?.methods[0].scopes).toEqual(["read:docs"]);
    expect(manifest.auth?.methods[1].type).toBe("api_key");
    expect(manifest.auth?.methods[1].header).toBe("X-API-Key");
    expect(manifest.auth?.methods[2].type).toBe("none");
  });

  it("absent auth is undefined", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.auth).toBeUndefined();
  });
});

describe("parseHints", () => {
  it("parses unit-level hints block", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{
        id: "u",
        path: "f.md",
        intent: "i",
        scope: "global",
        audience: ["agent"],
        hints: {
          token_estimate: 5000,
          load_strategy: "lazy",
          summary_available: true,
          summary_unit: "overview-tldr",
        },
      }],
    });
    expect(manifest.units[0].hints).toBeDefined();
    expect(manifest.units[0].hints?.token_estimate).toBe(5000);
    expect(manifest.units[0].hints?.load_strategy).toBe("lazy");
    expect(manifest.units[0].hints?.summary_available).toBe(true);
  });

  it("parses root-level hints block", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      hints: { total_token_estimate: 50000, unit_count: 5 },
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.hints).toBeDefined();
    expect(manifest.hints?.total_token_estimate).toBe(50000);
  });
});

describe("parsePayment", () => {
  it("parses root-level payment block", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      payment: { default_tier: "free" },
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.payment).toBeDefined();
  });

  it("parses unit-level payment block", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{
        id: "u",
        path: "f.md",
        intent: "i",
        scope: "global",
        audience: ["agent"],
        payment: { default_tier: "metered" },
      }],
    });
    expect(manifest.units[0].payment).toBeDefined();
  });

  it("absent payment is undefined", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.payment).toBeUndefined();
  });
});

describe("path traversal (#12)", () => {
  it("rejects ../secret", () => {
    expect(() => validateUnitPath("../secret")).toThrow("escapes");
  });

  it("rejects ../../etc/passwd", () => {
    expect(() => validateUnitPath("../../etc/passwd")).toThrow("escapes");
  });

  it("rejects absolute /etc/passwd", () => {
    expect(() => validateUnitPath("/etc/passwd")).toThrow("relative");
  });

  it("rejects docs/../../etc/shadow", () => {
    expect(() => validateUnitPath("docs/../../etc/shadow")).toThrow("escapes");
  });

  it("accepts safe nested path", () => {
    expect(validateUnitPath("docs/guide/intro.md")).toBe("docs/guide/intro.md");
  });
});

describe("parseAuthority", () => {
  it("parses a unit authority block with known actions", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{
        id: "u",
        path: "f.md",
        intent: "i",
        scope: "global",
        audience: ["agent"],
        authority: {
          read: "initiative",
          summarize: "initiative",
          modify: "requires_approval",
          share_externally: "denied",
          execute: "denied",
        },
      }],
    });
    const u = manifest.units[0];
    expect(u.authority).toBeDefined();
    expect(u.authority?.read).toBe("initiative");
    expect(u.authority?.summarize).toBe("initiative");
    expect(u.authority?.modify).toBe("requires_approval");
    expect(u.authority?.share_externally).toBe("denied");
    expect(u.authority?.execute).toBe("denied");
  });

  it("parses a unit authority block with a custom action", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{
        id: "u",
        path: "f.md",
        intent: "i",
        scope: "global",
        audience: ["agent"],
        authority: { read: "initiative", export_pdf: "requires_approval" },
      }],
    });
    expect(manifest.units[0].authority?.export_pdf).toBe("requires_approval");
  });

  it("parses root-level authority block", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      authority: { read: "initiative", modify: "denied" },
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.authority).toBeDefined();
    expect(manifest.authority?.read).toBe("initiative");
    expect(manifest.authority?.modify).toBe("denied");
  });

  it("absent authority is undefined", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.authority).toBeUndefined();
    expect(manifest.units[0].authority).toBeUndefined();
  });
});

describe("parseDiscovery", () => {
  it("parses a unit discovery block with observed status and confidence", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{
        id: "u",
        path: "f.md",
        intent: "i",
        scope: "global",
        audience: ["agent"],
        discovery: {
          verification_status: "observed",
          source: "web_traversal",
          observed_at: "2026-03-01T10:00:00Z",
          confidence: 0.72,
        },
      }],
    });
    const disc = manifest.units[0].discovery;
    expect(disc).toBeDefined();
    expect(disc?.verification_status).toBe("observed");
    expect(disc?.source).toBe("web_traversal");
    expect(disc?.observed_at).toBe("2026-03-01T10:00:00Z");
    expect(disc?.confidence).toBe(0.72);
    expect(disc?.verified_at).toBeUndefined();
    expect(disc?.contradicted_by).toBeUndefined();
  });

  it("parses a fully populated discovery block", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{
        id: "u",
        path: "f.md",
        intent: "i",
        scope: "global",
        audience: ["agent"],
        discovery: {
          verification_status: "verified",
          source: "openapi",
          observed_at: "2026-01-15T08:00:00Z",
          verified_at: "2026-02-01T12:00:00Z",
          confidence: 1.0,
          contradicted_by: "other-unit",
        },
      }],
    });
    const disc = manifest.units[0].discovery;
    expect(disc?.verification_status).toBe("verified");
    expect(disc?.verified_at).toBe("2026-02-01T12:00:00Z");
    expect(disc?.confidence).toBe(1.0);
    expect(disc?.contradicted_by).toBe("other-unit");
  });

  it("parses root-level discovery block", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      discovery: { verification_status: "manual", source: "manual", confidence: 0.9 },
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.discovery).toBeDefined();
    expect(manifest.discovery?.source).toBe("manual");
  });

  it("absent discovery is undefined", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.discovery).toBeUndefined();
    expect(manifest.units[0].discovery).toBeUndefined();
  });
});

describe("parseVisibility", () => {
  it("parses a unit visibility block with conditions", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{
        id: "u",
        path: "f.md",
        intent: "i",
        scope: "global",
        audience: ["agent"],
        visibility: {
          default: "internal",
          conditions: [
            {
              when: { environment: "production", agent_role: "auditor" },
              then: { sensitivity: "confidential", requires_auth: true },
            },
          ],
        },
      }],
    });
    const vis = manifest.units[0].visibility;
    expect(vis).toBeDefined();
    expect(vis?.default).toBe("internal");
    expect(vis?.conditions).toHaveLength(1);
    expect(vis?.conditions?.[0].when.environment).toBe("production");
    expect(vis?.conditions?.[0].when.agent_role).toBe("auditor");
    expect(vis?.conditions?.[0].then.sensitivity).toBe("confidential");
    expect(vis?.conditions?.[0].then.requires_auth).toBe(true);
  });

  it("parses visibility with array environment and agent_role", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{
        id: "u",
        path: "f.md",
        intent: "i",
        scope: "global",
        audience: ["agent"],
        visibility: {
          conditions: [
            {
              when: { environment: ["staging", "production"], agent_role: ["auditor", "admin"] },
              then: { requires_auth: true },
            },
          ],
        },
      }],
    });
    const cond = manifest.units[0].visibility?.conditions?.[0];
    expect(Array.isArray(cond?.when.environment)).toBe(true);
    expect(cond?.when.environment).toEqual(["staging", "production"]);
    expect(cond?.when.agent_role).toEqual(["auditor", "admin"]);
  });

  it("parses visibility condition with nested authority in then", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{
        id: "u",
        path: "f.md",
        intent: "i",
        scope: "global",
        audience: ["agent"],
        visibility: {
          conditions: [
            {
              when: { environment: "production" },
              then: { authority: { read: "initiative", modify: "denied" } },
            },
          ],
        },
      }],
    });
    const then = manifest.units[0].visibility?.conditions?.[0].then;
    expect(then?.authority?.read).toBe("initiative");
    expect(then?.authority?.modify).toBe("denied");
  });

  it("parses root-level visibility block", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      visibility: { default: "public" },
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.visibility).toBeDefined();
    expect(manifest.visibility?.default).toBe("public");
  });

  it("absent visibility is undefined", () => {
    const manifest = parseDict({
      project: "p",
      version: "1.0.0",
      units: [{ id: "u", path: "f.md", intent: "i", scope: "global", audience: ["agent"] }],
    });
    expect(manifest.visibility).toBeUndefined();
    expect(manifest.units[0].visibility).toBeUndefined();
  });
});

describe("parseFile", () => {
  it("parses the minimal fixture", () => {
    const manifest = parseFile(join(MINIMAL_DIR, "knowledge.yaml"));
    expect(manifest.project).toBe("my-project");
    expect(manifest.version).toBe("1.0.0");
    expect(manifest.kcp_version).toBe("0.6");
    expect(manifest.units).toHaveLength(1);
    expect(manifest.units[0].id).toBe("overview");
    expect(manifest.units[0].audience).toContain("agent");
  });

  it("parses the full fixture", () => {
    const manifest = parseFile(join(FULL_DIR, "knowledge.yaml"));
    expect(manifest.project).toBe("full-example");
    expect(manifest.units).toHaveLength(3);
    expect(manifest.relationships).toHaveLength(2);

    const spec = manifest.units.find((u) => u.id === "spec");
    expect(spec?.validated).toBe("2026-02-27");
    expect(spec?.triggers).toEqual(["spec", "rules", "normative"]);

    const api = manifest.units.find((u) => u.id === "api-schema");
    expect(api?.content_type).toBe("application/schema+json");
    expect(api?.depends_on).toEqual(["spec"]);
  });
});

// v0.22 Trust & Attestation: trust.agent_requirements + extended auth.methods (RFC-0004/0002)
import { validate } from "../src/validator.js";

const ATTEST_YAML = `
kcp_version: "0.21"
project: attest-demo
version: 1.0.0
trust:
  agent_requirements:
    require_attestation: true
    trusted_providers: [internal-agents.acme.com]
    attestation_url: https://acme.com/v1/attest
    attestation_jwks: https://acme.com/.well-known/jwks.json
    propagate_to_governed: true
auth:
  methods:
    - type: spiffe
      trust_domain: acme.internal
    - type: did
      supported_methods: [did:web, did:key]
    - type: http_signature
      key_id: k1
      algorithm: ed25519
    - type: bearer_token
      registration_url: https://acme.com/token
relationships:
  - from: overview
    to: overview
    type: governs
units:
  - id: overview
    path: README.md
    intent: "restricted overview"
    scope: project
    audience: [agent]
    access: restricted
`;

describe("trust.agent_requirements + extended auth (v0.22)", () => {
  it("parses agent_requirements fields", () => {
    const m = parseDict((require("js-yaml") as typeof import("js-yaml")).load(ATTEST_YAML) as Record<string, unknown>);
    const ar = m.trust!.agent_requirements!;
    expect(ar.require_attestation).toBe(true);
    expect(ar.trusted_providers).toEqual(["internal-agents.acme.com"]);
    expect(ar.attestation_url).toBe("https://acme.com/v1/attest");
    expect(ar.propagate_to_governed).toBe(true);
  });

  it("parses the extended auth method sub-fields", () => {
    const m = parseDict((require("js-yaml") as typeof import("js-yaml")).load(ATTEST_YAML) as Record<string, unknown>);
    const byType = Object.fromEntries(m.auth!.methods.map((x) => [x.type, x]));
    expect(byType["spiffe"].trust_domain).toBe("acme.internal");
    expect(byType["did"].supported_methods).toEqual(["did:web", "did:key"]);
    expect(byType["http_signature"].key_id).toBe("k1");
    expect(byType["http_signature"].algorithm).toBe("ed25519");
  });

  it("warns on non-HTTPS attestation_url and unsatisfiable require_attestation", () => {
    const m = parseDict((require("js-yaml") as typeof import("js-yaml")).load(`
kcp_version: "0.21"
project: bad
version: 1.0.0
trust:
  agent_requirements:
    require_attestation: true
    attestation_url: http://insecure.example/attest
units:
  - {id: u, path: u.md, intent: x, scope: project, audience: [agent]}
`) as Record<string, unknown>);
    const r = validate(m, ".");
    expect(r.warnings.some((w) => w.includes("attestation_url SHOULD use HTTPS"))).toBe(true);
  });

  it("warns when propagate_to_governed is set but no governs relationship exists", () => {
    const m = parseDict((require("js-yaml") as typeof import("js-yaml")).load(`
kcp_version: "0.21"
project: nogov
version: 1.0.0
trust:
  agent_requirements:
    propagate_to_governed: true
units:
  - {id: u, path: u.md, intent: x, scope: project, audience: [agent]}
`) as Record<string, unknown>);
    const r = validate(m, ".");
    expect(r.warnings.some((w) => w.includes("propagate_to_governed"))).toBe(true);
  });
});

// v0.23 Trust & Auth Completion: publisher_did, access receipts, require_delegation_proof,
// per-unit auth override (RFC-0004/0002).
describe("v0.23 trust/auth completion fields", () => {
  const M = (require("js-yaml") as typeof import("js-yaml")).load(`
kcp_version: "0.22"
project: v23
version: 1.0.0
trust:
  provenance: {publisher: Acme, publisher_did: "did:web:acme.com"}
  audit: {provides_access_receipts: true, receipt_format: jws}
delegation: {max_depth: 2, require_delegation_proof: true}
units:
  - id: partner
    path: p.md
    intent: "partner data"
    scope: project
    audience: [agent]
    access: restricted
    auth:
      methods:
        - {type: oauth2, issuer: "https://partner.example.com", scopes: [read:shared]}
`) as Record<string, unknown>;

  it("parses publisher_did, access receipts, require_delegation_proof, per-unit auth", () => {
    const m = parseDict(M);
    expect(m.trust!.provenance!.publisher_did).toBe("did:web:acme.com");
    expect(m.trust!.audit!.provides_access_receipts).toBe(true);
    expect(m.trust!.audit!.receipt_format).toBe("jws");
    expect(m.delegation!.require_delegation_proof).toBe(true);
    expect(m.units[0].auth!.methods[0].type).toBe("oauth2");
    expect(m.units[0].auth!.methods[0].issuer).toBe("https://partner.example.com");
  });

  it("warns on a non-DID publisher_did and on receipts without a format", () => {
    const bad = parseDict((require("js-yaml") as typeof import("js-yaml")).load(`
kcp_version: "0.22"
project: bad
version: 1.0.0
trust:
  provenance: {publisher_did: "acme.com"}
  audit: {provides_access_receipts: true}
units:
  - {id: u, path: u.md, intent: x, scope: project, audience: [agent]}
`) as Record<string, unknown>);
    const w = validate(bad, ".").warnings;
    expect(w.some((x) => x.includes("publisher_did SHOULD be a DID"))).toBe(true);
    expect(w.some((x) => x.includes("provides_access_receipts is true but no receipt_format"))).toBe(true);
  });
});

// v0.24 Org-Federation: manifests[].context + manifests[].agent_identity (RFC-0011).
describe("v0.24 org-federation manifest fields", () => {
  const yaml = (require("js-yaml") as typeof import("js-yaml"));
  const HUB = `
kcp_version: "0.24"
project: hub
version: 1.0.0
units:
  - {id: front-door, path: README.md, intent: x, scope: global, audience: [agent]}
manifests:
  - id: platform
    url: "https://git.example.com/platform/knowledge.yaml"
    relationship: foundation
    context: ["prod"]
    agent_identity:
      required: true
      credential_hint: github_pat
      docs_url: "https://kcp.example.com/auth.md"
  - id: data
    url: "https://git.example.com/data/knowledge.yaml"
    relationship: peer
    agent_identity:
      required: true
      credential_hint: oauth2
      issuer_hint: "https://auth.example.com"
`;

  it("parses context and agent_identity on manifests[] entries", () => {
    const m = parseDict(yaml.load(HUB) as Record<string, unknown>);
    const platform = m.manifests.find((x) => x.id === "platform")!;
    expect(platform.context).toEqual(["prod"]);
    expect(platform.agent_identity!.required).toBe(true);
    expect(platform.agent_identity!.credential_hint).toBe("github_pat");
    expect(platform.agent_identity!.docs_url).toBe("https://kcp.example.com/auth.md");
    const data = m.manifests.find((x) => x.id === "data")!;
    expect(data.agent_identity!.issuer_hint).toBe("https://auth.example.com");
    expect(data.context).toBeUndefined(); // absent = all environments
  });

  it("warns on empty context, required-without-hint, and issuer_hint misuse", () => {
    const bad = parseDict(yaml.load(`
kcp_version: "0.24"
project: bad
version: 1.0.0
units:
  - {id: u, path: u.md, intent: x, scope: project, audience: [agent]}
manifests:
  - id: a
    url: "https://git.example.com/a/knowledge.yaml"
    context: []
    agent_identity: {required: true}
  - id: b
    url: "https://git.example.com/b/knowledge.yaml"
    agent_identity: {credential_hint: github_pat, issuer_hint: "https://x.example.com"}
`) as Record<string, unknown>);
    const w = validate(bad, ".").warnings;
    expect(w.some((x) => x.includes("context is present but empty"))).toBe(true);
    expect(w.some((x) => x.includes("required is true but no credential_hint"))).toBe(true);
    expect(w.some((x) => x.includes("issuer_hint is only meaningful for credential_hint 'oauth2'"))).toBe(true);
  });
});
