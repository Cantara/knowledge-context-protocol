# RFC-0024: Serving Endpoint Binding

**Status:** Draft
**Author:** Thor Henning Hetland (eXOReaction AS)
**Created:** 2026-07-14
**Discussion:** [GitHub Discussions](https://github.com/Cantara/knowledge-context-protocol/discussions)
**Depends on:** [RFC-0018 Trusted Render Pipeline](./RFC-0018-Trusted-Render-Pipeline.md), [RFC-0019 Unit Content Integrity and Origin Evidence](./RFC-0019-Unit-Content-Integrity-and-Origin-Evidence.md)
**Amends:** RFC-0018 (§2 threat model adds T11)
**Related:** [RFC-0003 Federation](./RFC-0003-Federation.md) · [RFC-0012 Capability Discovery Provenance](./RFC-0012-Capability-Discovery-Provenance.md) · [RFC-0022 Composition Integrity](./RFC-0022-Composition-Integrity.md)
**Spec:** [SPEC.md](./SPEC.md) (current: v0.25)

---

## Summary

A KCP signature proves **who** signed the manifest and that its bytes are
intact (RFC-0018), and — since RFC-0019 — that the unit content behind it is
what the signer hashed. It proves nothing about **where** the knowledge web is
legitimately served. Nothing in the signed bytes states which HTTPS URL
authoritatively serves the manifest, or which MCP servers are authorized to
represent it to agents.

This RFC adds an OPTIONAL `serving` block to the manifest — a signed
declaration of the manifest's authoritative retrieval URLs and its authorized
MCP endpoints — and defines verifier behavior when content arrives from an
undeclared endpoint. It names the attack this closes (**T11, the
rogue-representative attack**) and continues the RFC-0018/0019/0022 threat
model.

```yaml
serving:
  manifest:
    - https://wiki.cantara.no/knowledge.yaml
  mcp:
    - https://mcp.cantara.no/mcp
```

Backward compatible: the block is optional; a manifest without it behaves
exactly as today.

---

## The Problem

### The signature stops one hop short

The trusted render pipeline authenticates a chain:

```
signing key ──signs──▶ manifest bytes ──hashes──▶ unit content
```

But agents increasingly meet knowledge webs through a **serving layer** the
chain never mentions:

1. **Direct HTTPS retrieval** — an agent fetches `knowledge.yaml` from a URL
   it obtained out-of-band (a registry listing, a README, a link).
2. **MCP-mediated access** — an agent connects to an MCP server (e.g. one
   listed in the official MCP registry) that plans and loads *on the agent's
   behalf*, injecting a default manifest the agent never chose.

In both cases the signature verifies — over bytes that never claimed to live
at that URL or be represented by that server.

### T11: the rogue-representative attack

An attacker takes a **genuinely signed, unmodified** manifest and stands up
their own serving layer for it:

- **Rogue MCP proxy.** The attacker registers `example.corp/knowledge-mirror`
  in a public MCP registry, pointing at their own MCP server. That server
  fetches the victim's signed manifest, so `kcp_plan` truthfully reports
  `signature: verified` — then the attacker's *tool layer* tampers with what
  it returns: reordered plans, dropped units, injected "additional context",
  stale pinned snapshots. Every cryptographic check the agent can express
  today passes.
- **Stale or selective mirror.** The attacker (or an innocent cache) serves a
  superseded manifest version from a plausible URL. The signature on the old
  bytes remains valid forever; nothing marks the location as unauthoritative.

The common shape: **a valid signature is presented by a representative the
signer never authorized.** T9 (RFC-0019) was this pattern for local
directories; T10 (RFC-0022) was this pattern for composition includes; T11 is
the same pattern for the network serving layer.

### Observed in the wild — in the WHO layer

The failure mode is not hypothetical. AMCP v0.1 (Authority-MCP, the
W3C-VC/did:web profile deployed by `ch.jassverband/jasswiki`) attests the
*inverse* direction — an organisation cryptographically endorses its MCP
server — and its reference deployment exhibits exactly this gap today: the
signed credential's `credentialSubject.id` names a decommissioned Cloud
Functions URL, while the live endpoint (`jasswiki.ch/mcp/http`, per its MCP
registry entry since 2026-07-02) is only named in an **unsigned**
`.well-known/mcp.json`. The verifiable chain endorses a server nobody
connects to; the server everybody connects to is endorsed by nothing.
(Reported upstream: [remoprinz/jasswiki-mcp#1](https://github.com/remoprinz/jasswiki-mcp/issues/1).)

KCP has the mirror-image hole: we bind content, not endpoints. Any scheme
that binds only one end of the serving relationship leaves the other end as
the attack surface.

---

## What This RFC Proposes

### 1. The `serving` block

A new OPTIONAL top-level manifest block:

| Field | Requirement | Type | Description |
|---|---|---|---|
| `serving` | OPTIONAL | object | Signed declaration of authorized serving endpoints. |
| `serving.manifest` | OPTIONAL | list of strings | HTTPS URLs at which this manifest is authoritatively served. Exact-match URLs (scheme, host, path; see matching rules). |
| `serving.mcp` | OPTIONAL | list of strings | HTTPS URLs of MCP endpoints authorized to represent this knowledge web to agents. |

Because the block lives inside the manifest, it is covered by the existing
signature — declaring it requires no new cryptography, no additional
documents, and no new `.well-known` artifacts. Moving an endpoint is an edit
plus a re-sign: the same supersession mechanics every other manifest change
already uses.

Each declared list is **exhaustive for its class**. Declaring
`serving.manifest` asserts "these are the only authoritative manifest URLs";
declaring `serving.mcp` asserts "these are the only authorized MCP
representatives". Omitting a list makes no assertion about that class.

### 2. Verifier behavior

**Direct retrieval (`serving.manifest` declared):**

- A verifier that retrieved the manifest over HTTP(S) MUST compare the final
  post-redirect retrieval URL against `serving.manifest`.
- On mismatch, a render or plan that would tier `trusted` MUST be demoted to
  `known`, and the verifier MUST surface a warning naming both the retrieval
  URL and the declared list. This mirrors the demotion discipline of §16
  corroboration and RFC-0019 content-hash mismatches: the content is intact
  and the signer is known, but an authorization claim failed — so the
  manifest is treated as recognized, not trusted.
- Local retrieval (file paths, git checkouts) is out of scope here; it is
  governed by RFC-0019 origin evidence.

**MCP-mediated access (`serving.mcp` declared):**

- An MCP client (or governance proxy such as kcp-harness) that knows which
  endpoint URL it dialed SHOULD, after receiving a plan/load response that
  reports a verified manifest, independently fetch and verify that manifest
  and compare its dialed endpoint against `serving.mcp`.
- On mismatch, the client SHOULD treat the *server's mediation* — not the
  manifest — as unverified: surface the discrepancy to the agent/user, and
  where the client enforces trust tiers, cap server-mediated content at
  `known`.
- MCP *servers* implementing KCP SHOULD self-check at startup: a server
  configured with a default manifest whose `serving.mcp` list exists but does
  not include the server's own public URL SHOULD log a prominent warning.
  (Serving remains permitted — the server cannot always know its public URL —
  but silent impersonation should cost at least a log line.)

**Neither list declared:** no behavior change whatsoever.

### 3. URL matching rules

- Comparison is on the **final post-redirect** URL for retrieval; on the
  **dialed** URL for MCP endpoints.
- Exact match on scheme, host, and path after: lowercasing scheme and host,
  removing default ports (`:443`), and stripping any query string and
  fragment. No wildcard or prefix matching in this RFC (see Open Questions).
- Entries MUST be HTTPS. An `http://` entry is a manifest validation error.

### 4. Threat model amendment (RFC-0018 §2)

| ID | Threat | Closed by |
|---|---|---|
| T11 | **Rogue-representative attack**: a genuinely signed manifest is served, mirrored, or MCP-fronted by an endpoint the signer never authorized, lending the endpoint's own behavior (tampered mediation, stale pinning, selective serving) the credibility of a valid signature. | `serving` block + demotion/warning semantics above. |

Honest limitation: T11 is closed **only for consumers that check**. A client
that never independently fetches the manifest cannot detect a rogue MCP
proxy, because the proxy controls everything that client sees. The `serving`
block makes the authorization claim *expressible and signed*; enforcement
strength scales with how independently the consumer can verify. This is the
same trust topology as certificate pinning: it protects clients that pin.

---

## Legitimate mirrors and federation

Mirroring is not an attack — undeclared representation is. A knowledge web
that wants mirrors lists them:

```yaml
serving:
  manifest:
    - https://wiki.cantara.no/knowledge.yaml
    - https://mirror.example.org/cantara/knowledge.yaml
  mcp:
    - https://mcp.cantara.no/mcp
    - https://mcp-cantara-no.fly.dev/mcp   # platform URL behind the custom domain
```

Federation (RFC-0003) and composition (RFC-0014/0022) interact naturally:
each included manifest carries its own `serving` declaration and is judged
against its own retrieval URL, under the existing rule that a composed
manifest's tier is bounded by its weakest authenticated include.

---

## Backward Compatibility

- `serving` is OPTIONAL. Absent block = no assertion = today's behavior.
- Verifiers predating this RFC ignore unknown manifest fields (existing
  conformance rule), so old verifiers see no change. The block only *adds*
  protection for consumers that understand it — it can never make a manifest
  less trusted for consumers that don't.
- No changes to the signature envelope, hash encoding, or bridge query
  surface.

---

## Conformance (proposed)

| ID | Requirement |
|---|---|
| C18 | A verifier that retrieved a manifest over HTTP(S), where `serving.manifest` is declared and the final retrieval URL is not listed, MUST NOT tier the render/plan above `known` and MUST emit a warning naming both URLs. |
| C19 | A KCP-aware MCP server configured with a default manifest declaring `serving.mcp` SHOULD warn at startup if its own public URL is absent from the list. |

---

## Relationship to AMCP (WHO vs WHAT)

AMCP v0.1 and KCP attack complementary halves of the same trust chain:

| Layer | Question | Answered by |
|---|---|---|
| Organisation → server | "Is this MCP server officially endorsed by the organisation?" | AMCP (W3C VC, did:web) |
| Manifest → content | "Is this content what the signer published?" | KCP (RFC-0018/0019) |
| Manifest → serving layer | "Is this endpoint authorized to serve/represent this content?" | **this RFC** |

A composed deployment — organisational VC attesting the MCP server, whose
default manifest signs its own `serving` list naming that same server —
closes the full chain end-to-end with a single primitive (Ed25519)
throughout. A future RFC MAY add an optional `attestations` pointer from the
manifest to external credentials (AMCP interop); it is deliberately out of
scope here.

---

## Open Questions

1. **Prefix/wildcard matching.** Multi-region or per-tenant deployments may
   want `https://mcp.example.com/*`. Deferred: exact match is auditable and
   sufficient for every deployment we operate; wildcards reintroduce
   subdomain-takeover surface.
2. **Should C18 demotion be `known` or `failed`?** `known` (chosen) matches
   the RFC-0019 mismatch discipline: signer authentic, claim failed.
   Arguments exist for a per-manifest opt-in to fail-closed
   (`serving.policy: strict` → `failed`).
3. **MCP endpoint self-knowledge.** C19 is SHOULD because a server behind
   proxies/CDNs cannot reliably know its public URL. A
   `--public-url` flag on serving implementations would make it checkable.
4. **Registry cross-checks.** MCP registries could validate at publish time
   that a submitted remote URL appears in the signed manifest's `serving.mcp`
   list — turning the registry itself into a T11 enforcement point. Out of
   scope for the KCP spec, but the highest-leverage deployment of this block.

---

## Reference Implementation Plan

1. `kcp-agent`: parse `serving`; implement C18 demotion + warning in plan/render;
   startup self-check (C19) in `kcp-agent serve` behind `--public-url`.
2. `kcp-harness`: endpoint comparison for MCP-mediated access (client-side T11 check).
3. Deployed manifests (`wiki.totto.org`, `wiki.cantara.no`,
   `totto.github.io/javabin-archieve`): declare `serving` blocks and re-sign —
   dogfood before promotion to SPEC.
