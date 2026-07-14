# Serving Endpoint Binding + Unit Aliases (v0.26)

A regulatory knowledge base that demonstrates both v0.26 features together:

- **`serving`** (§3.12, [RFC-0024](../../RFC-0024-Serving-Endpoint-Binding.md)) — a signed,
  in-manifest declaration of the URLs at which this manifest is *authoritatively served* and the
  MCP endpoints authorized to represent it. It answers a question a signature alone cannot: not
  *who signed this*, but *where is it legitimately served*.
- **`aliases`** (§4.2a, [RFC-0023](../../RFC-0023-Unit-Aliases.md)) — additional identifiers that
  resolve to the same unit as its canonical `id`. A regulation is authored one article per file,
  but agents cite sub-clauses (Art. 21(2)(a), (b), (c)); aliases let a lookup by any sub-clause id
  resolve to the article that covers it — no file-per-sub-clause sprawl, no undeclared
  suffix-stripping.

## The manifest

`knowledge.yaml` declares two articles. `reg-art-021` carries three sub-clause aliases and
`reg-art-023` two. The `serving` block names a primary wiki, a declared mirror, and one MCP
endpoint. `relationships[].depends_on` targets the **canonical id** — aliases are references, never
valid topology targets.

## Try it

### Aliases resolve to the canonical unit

```bash
# Fetch by canonical id — plain content.
kcp query "incident reporting" --file knowledge.yaml

# Through a bridge, get_unit("reg-art-21-2b") resolves to reg-art-021 and the
# response leads with { "matched_alias": "reg-art-21-2b", "canonical_id": "reg-art-021" }.
# search_knowledge for a sub-clause id reports "matched_alias" on the hit.
```

### Serving binding demotes a rogue retrieval (C22)

```bash
# Retrieved from a declared serving URL → stays trusted (given signing + allowlist):
kcp render knowledge.yaml \
  --keys ~/.kcp/trusted-keys.yaml --origin github.com/Cantara/cyber-reg \
  --retrieved-from https://mirror.example.org/cantara/cyber-reg/knowledge.yaml

# Retrieved from a URL NOT in serving.manifest → trusted demoted to known + a warning
# naming both the retrieval URL and the declared list (T11 rogue-representative defense):
kcp render knowledge.yaml \
  --keys ~/.kcp/trusted-keys.yaml --origin github.com/Cantara/cyber-reg \
  --retrieved-from https://rogue.example.net/knowledge.yaml
```

The renderer surfaces `serving` as data and, with `--retrieved-from`, records a `serving_check`
(`match` / `mismatch` / `not_declared`) in the `trust` block. URL matching follows §3.12: scheme
and host are lowercased, a default `:443` is dropped, query and fragment are stripped, and the path
is compared exactly — no wildcard or prefix matching.

## Why it matters

A signature proves integrity and authorship. It does not prove that the endpoint serving the bytes
was authorized to do so. `serving` makes that authorization claim **expressible and signed**;
enforcement scales with how independently the consumer can verify — the same trust topology as
certificate pinning. Aliases keep citation-level addressability without fragmenting the content
store. Both are OPTIONAL and additive: a manifest without them behaves exactly as before, and a
verifier predating v0.26 ignores them (unknown-field rule) — they can only *add* protection.
