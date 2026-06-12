# RFC-0018/0019 Render Pipeline — Experimental Validation

Executable experiments validating that [RFC-0018 (Trusted Render
Pipeline)](../../RFC-0018-Trusted-Render-Pipeline.md) and
[RFC-0019 (Unit Content Integrity and Origin
Evidence)](../../RFC-0019-Unit-Content-Integrity-and-Origin-Evidence.md)
actually hold for both the legitimate use cases and the threat model
(T1–T9), using the shipping renderer and real Ed25519 signatures.

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
| RFC-0019 | Per-unit content hashes verify intact (A10) and across two independent §3.2 digest implementations (A11); the T9 relocation attack — genuine signed manifest, fabricated `.git` remote, attacker content — is stopped by the evidence cap (B17) and, independently, by content hashes when the cap is waived (B17b); post-sign drift demotes per-unit (B18). Corroboration (§4.3) is deferred (B19). |

Machine-checked global invariants on every emitted artifact: the stats
identity (`fields_in = rendered + dropped + quarantined`), schema-only
output (C3), no `load_eligible: true` on executable/service units (C4),
no timestamp in default output (C1).

## Layout

```
prototype/verify-render.js §3.4 consumer-side artifact check (C10) —
                           the one piece with no CLI equivalent
fixtures/                  legit-* and hostile-* manifests
run.js                     experiment matrix + runner, writes RESULTS.md
```

The renderer under test is the **shipping** `kcp render` (`../../cli`),
built on demand by `run.js` — there is no separate prototype renderer, so
these experiments certify the code that actually ships rather than a copy
that can drift from it. (Earlier drafts carried a prototype renderer; it
was retired once the CLI implementation existed.)

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
- Most cases inject origin via `--origin` (asserted evidence,
  RFC-0019 §4.1). Real git-remote derivation — and its classification as
  `derived` evidence — is exercised by B17/B17b, which build an actual
  fabricated `.git/config` in the case directory.
- B19 (origin corroboration, RFC-0019 §4.3) is deferred: the CLI does not
  implement `--corroborate` yet, and testing it honestly needs a network
  stub plus forge-specific manifest-URL mapping.
- No experiment covers RFC-0017 event-store writes (§8) — observability
  is asserted by the RFC but not yet exercised.
