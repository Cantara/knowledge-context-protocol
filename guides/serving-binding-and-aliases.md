# Tutorial: Serving endpoint binding & unit aliases

**Level:** intermediate · **Spec:** SPEC.md §3.12 / §4.2a (v0.26) · **RFCs:**
[RFC-0024](../RFC-0024-Serving-Endpoint-Binding.md), [RFC-0023](../RFC-0023-Unit-Aliases.md)
· **Example:** [examples/serving-and-aliases/](../examples/serving-and-aliases/)

A KCP signature proves two things: **who** signed a manifest, and that its bytes and referenced
content are **intact**. It proves nothing about **where** the manifest is legitimately served, and
it says nothing about how *fine-grained* a citation an agent may resolve. v0.26 adds one signed,
in-manifest declaration for each gap. Both are OPTIONAL and additive — a manifest without them
behaves exactly as before, and a verifier predating v0.26 ignores them (unknown-field rule).

## 1. `serving` — where the knowledge web is legitimately served

Open [`examples/serving-and-aliases/knowledge.yaml`](../examples/serving-and-aliases/knowledge.yaml):

```yaml
serving:
  manifest:
    - https://wiki.cantara.no/cyber-reg/knowledge.yaml
    - https://mirror.example.org/cantara/cyber-reg/knowledge.yaml   # a declared mirror is legitimate
  mcp:
    - https://mcp.cantara.no/cyber-reg
```

Each list is **exhaustive for its class**: declaring `serving.manifest` asserts "these are the only
authoritative manifest URLs"; declaring `serving.mcp` asserts "these are the only authorized MCP
representatives". Omitting a list makes no assertion about that class. Every entry MUST be HTTPS —
an `http://` entry is a §7 validation error. Because the block lives inside the signed bytes,
moving an endpoint is an edit plus a re-sign — no `.well-known` artifacts, no new cryptography.

### The threat it closes (T11)

A genuinely-signed manifest is served, mirrored, or MCP-fronted by an endpoint the signer never
authorized — lending that endpoint's own behaviour (tampered mediation, stale pinning, selective
serving) the credibility of a valid signature. T9 (§4.21) was this pattern for local directories,
T10 (§3.11) for composition includes; **T11 is the same pattern for the network serving layer.**

### C22 — demotion on a retrieval-URL mismatch

When a verifier retrieved the manifest over HTTP(S), tell the renderer where from:

```bash
# Retrieved from a declared serving URL → stays trusted (given signing + allowlist):
kcp render examples/serving-and-aliases/knowledge.yaml \
  --keys ~/.kcp/trusted-keys.yaml --origin github.com/Cantara/cyber-reg \
  --retrieved-from https://mirror.example.org/cantara/cyber-reg/knowledge.yaml

# Retrieved from a URL NOT in serving.manifest → trusted demoted to known + a warning:
kcp render examples/serving-and-aliases/knowledge.yaml \
  --keys ~/.kcp/trusted-keys.yaml --origin github.com/Cantara/cyber-reg \
  --retrieved-from https://rogue.example.net/knowledge.yaml
```

The second command prints:

```
⚠ retrieval URL 'https://rogue.example.net/knowledge.yaml' is not in the manifest's
  serving.manifest list [...]; trusted tier demoted to known (§3.12 / C22)
```

The content is intact and the signer is known — but an authorization-to-serve claim failed, so the
manifest is treated as *recognized*, not *trusted*. This mirrors the demotion discipline of §16
corroboration and §4.21 content-hash mismatch. The `trust` block records a `serving_check`
(`match` / `mismatch` / `not_declared`) for audit. URL matching (§3.12) lowercases scheme and host,
drops a default `:443`, strips any query string and fragment, and compares the path exactly — **no
wildcard or prefix matching.** Local retrieval (file paths, git checkouts) is out of scope here: it
is governed by RFC-0019 origin evidence.

It is closed **only for consumers that check** — a client that never independently fetches the
manifest cannot detect a rogue proxy. `serving` makes the authorization claim expressible and
signed; enforcement scales with how independently the consumer can verify, the same trust topology
as certificate pinning.

## 2. `aliases` — citation-level addressability without file sprawl

A regulation is authored one article per file, but agents and humans cite sub-clauses — Article
21(2)(a), (b), (c). Without aliases you either split every sub-clause into its own unit (sprawl) or
hope the agent guesses an undeclared suffix-stripping rule. `aliases` declares the mapping:

```yaml
units:
  - id: reg-art-021
    path: articles/art-021.txt
    intent: "What cybersecurity risk-management measures must entities implement?"
    aliases:
      - reg-art-21-2a   # (a) policies on risk analysis and information system security
      - reg-art-21-2b   # (b) incident handling
      - reg-art-21-2c   # (c) business continuity and crisis management
```

**Rules that keep the model unambiguous:**

- An alias follows the same character rules as `id` and MUST be unique across **all** ids **and**
  aliases (a collision is a §7 warning).
- A lookup by an alias resolves to the **same unit** as its `id`. The alias creates no new unit.
- `id` stays **canonical**: aliases are NOT valid targets for `depends_on`, `supersedes`,
  `relationships`, `overrides`, or `excludes` — the topology and composition layers only ever name
  canonical ids. In the example, `reg-art-023 depends_on reg-art-021` (never an alias).
- Aliases share their unit's temporal window (§4.22) and `content_hash` (§4.21) — identifier-level
  indirection only, never a distinct payload.

### How a bridge resolves an alias

`get_unit("reg-art-21-2b")` resolves to `reg-art-021` and leads the response with a metadata block,
then the content:

```json
{ "matched_alias": "reg-art-21-2b", "canonical_id": "reg-art-021" }
```

A direct id lookup (`get_unit("reg-art-021")`) is unchanged — a single content item, no metadata
block. `search_knowledge` matches alias terms too: an alias hit adds `alias` to `match_reason` and
reports the `matched_alias` on the result. All three bridges (TypeScript, Python, Java) behave
identically.

## 3. Both together

The example manifest exercises both: a regulatory corpus served from a primary wiki and a declared
mirror, with each article carrying its sub-clause aliases. Validate it and watch the renderer
surface both blocks:

```bash
kcp validate examples/serving-and-aliases/knowledge.yaml   # clean
kcp render   examples/serving-and-aliases/knowledge.yaml   # serving + aliases surfaced as data
```

Neither feature changes trust for a manifest that doesn't use it. `serving` can only *add*
protection (a demotion never fires without a declared list and a supplied retrieval URL); `aliases`
can only *add* resolvable identifiers. That additivity is the point: adopt either, both, or neither,
one manifest at a time.
