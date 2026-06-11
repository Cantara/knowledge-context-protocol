# RFC-0018 Render Pipeline — Experimental Validation

Executable experiments validating that [RFC-0018 (Trusted Render
Pipeline, draft-02)](../../RFC-0018-Trusted-Render-Pipeline.md) actually
holds for both the legitimate use cases and the threat model (T1–T8),
using a prototype renderer and real Ed25519 signatures.

```bash
npm install
npm run run        # writes RESULTS.md, exit 0 iff no failures
```

## What is being tested

| Group | Question |
|-------|----------|
| A-cases | Do **legitimate** manifests render cleanly? Plain OSS repos (unsigned), org-signed manifests, manifests that *describe* build commands, declared `kind: executable` tooling, federation edges, byte-identical re-renders (C1). |
| B-cases | Do the **threats** fail the way the RFC says? Imperative injection (T1), capability laundering at trusted tier (T2), schema smuggling (T5), unknown-`kind` evasion, signature theater (T4), signature stripping with and without pinning (T7), tampered signatures, scope typosquatting, forged rendered artifacts (T8). |
| B12 | A deliberately **unfixed** case: descriptive-mood injection that passes the lint, kept as a permanent reminder that §6.2 is defense-in-depth and §6.4/C8 data-framing is the load-bearing control. It reports `KNOWN-GAP`, not `PASS`. |

Machine-checked global invariants on every emitted artifact: the stats
identity (`fields_in = rendered + dropped + quarantined`), schema-only
output (C3), no `load_eligible: true` on executable/service units (C4),
no timestamp in default output (C1).

## Layout

```
prototype/render.js        prototype renderer (tiering, pinning, lint,
                           schema whitelist, fail-closed) — experiment
                           code, NOT the reference implementation
prototype/verify-render.js §3.4 consumer-side artifact check (C10)
prototype/lint.js          imperative-lint-0.2 rule set
fixtures/                  legit-* and hostile-* manifests
run.js                     experiment matrix + runner, writes RESULTS.md
```

Signatures: the runner generates fresh Ed25519 keypairs per run (an
allowlisted "org" key scoped to `github.com/Cantara` + `cantara.no`, and
an unscoped "attacker" key) and signs fixtures with a detached-signature
file modelling the §4.2 detached-JWS profile without a JOSE dependency.

## Harness self-test

The expectation checks were mutation-tested: disabling the unknown-kind
rule or the lint pass in the renderer makes the corresponding experiments
fail loudly (including payload-leak detection on the rendered output).
If you change the renderer, re-run with a deliberate break first to
confirm the harness still bites.

## Known limitations

- The lint corpus is small (3 imperative variants, 3 descriptive
  controls, 1 bypass). Real precision/recall numbers need the
  `conformance/` adversarial corpus ported over.
- Origin is injected via `--origin`; deriving origin from a real git
  checkout is unspecified in the RFC (open issue) and untested here.
- No experiment covers RFC-0017 event-store writes (§8) — observability
  is asserted by the RFC but not yet exercised.
