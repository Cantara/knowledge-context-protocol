# Tutorial: Enterprise discovery with org-federation

**Level:** intermediate · **Spec:** SPEC.md §3.6 (v0.24) · **RFC:** [RFC-0011](../RFC-0011-Org-Federation.md)
· **Example:** [examples/org-federation/](../examples/org-federation/)

An agent arrives at a large company knowing only the domain name. How does it find its first
manifest, get through the door, and progressively learn what else exists? That is the
**enterprise bootstrap** problem, and RFC-0011 answers it with a hub manifest plus two small
declarations on each federation edge:

- **`context`** — which runtime environment(s) a sub-manifest reference is valid for.
- **`agent_identity`** — what credential the agent needs *before* it tries to fetch that
  sub-manifest.

Neither is enforcement. They are declarations that let an agent *plan* instead of probing blindly
and backtracking on failed fetches. This tutorial walks the full path against the reference hub.

## 1. The hub manifest — a front door

Open [`examples/org-federation/knowledge.yaml`](../examples/org-federation/knowledge.yaml). Three
things make it a hub:

```yaml
network:
  role: hub
  entry_point: "https://kcp.companyx.example/knowledge.yaml"

units:
  - id: front-door           # public, load-eager — the cold-start surface
    compliance: { sensitivity: public }
    hints: { load_strategy: eager }

manifests:
  - id: platform-engineering
    url: "https://git.companyx.example/platform/knowledge.yaml"
    context: ["prod"]
    agent_identity:
      required: true
      credential_hint: github_pat
      docs_url: "https://kcp.companyx.example/guides/agent-authentication.md"
```

An unauthenticated agent can load the `front-door` unit immediately — it is `sensitivity: public`.
From there it reads the auth guide, then decides which federation edges to follow.

Validate it:

```bash
kcp validate examples/org-federation/knowledge.yaml
# ✓ Valid — no errors or warnings
```

## 2. Environment selection with `context`

The hub lists three platform entries — one `prod`, one `prod`+`staging`, one `dev`+`test`:

```yaml
manifests:
  - id: platform-engineering        # context: ["prod"]
  - id: data-warehouse              # context: ["prod", "staging"]
  - id: platform-engineering-dev    # context: ["dev", "test"]
```

An agent running in `prod` selects `platform-engineering` and `data-warehouse` and **ignores**
the dev mirror. An agent in `dev` selects only `platform-engineering-dev`. No fetching all three
and reconciling — the hub author published one federation list that spans environments, and each
agent takes its slice. An entry with no `context` is valid everywhere.

`context` is an advisory selection hint. KCP does not enforce it; an agent with no environment
notion may traverse every entry.

## 3. Credential planning with `agent_identity`

Before fetching a sub-manifest, the agent reads its `agent_identity` hint:

- `platform-engineering` wants a `github_pat` (`required: true`) — acquire it first.
- `data-warehouse` wants `oauth2` and even names the issuer (`issuer_hint`) — run the
  authorization-code flow against that issuer before fetching.
- `platform-engineering-dev` sets `required: false` — fetch it directly; its own `auth` block
  still governs anything sensitive inside.

The agent surfaces `docs_url` to its developer for anything it cannot obtain on its own, pauses for
the credential, then traverses. The sub-manifest's own `auth` block (§3.3) is the enforcement
point — `agent_identity` only tells the agent what to bring.

## 4. Render it — the hints are surfaced, never acted on

```bash
kcp render examples/org-federation/knowledge.yaml
```

The federation block comes through as pure data:

```yaml
federation:
  - id: platform-engineering
    url: https://git.companyx.example/platform/knowledge.yaml
    relationship: foundation
    context: [prod]
    agent_identity:
      required: true
      credential_hint: github_pat
      docs_url: https://kcp.companyx.example/guides/agent-authentication.md
    target_tier: unrendered          # trust is never inherited across the edge (C5)
```

The renderer copies `context` and `agent_identity` verbatim and **never dereferences** `docs_url`
or `issuer_hint` — deterministic and network-free, exactly like `attestation_url` in v0.22. A
manifest may influence what an agent knows, never what it does.

## 5. Progressive disclosure — the sensitivity ladder

The hub's units climb three tiers with no new spec fields, layering on `compliance.sensitivity`:

| Tier | `sensitivity` | Unit | Who sees it |
|------|---------------|------|-------------|
| T0 | `public` | `front-door`, `auth-guide` | anyone, unauthenticated |
| T1 | `internal` | `service-catalogue` | authenticated developer |
| T2 | `confidential` | `data-contracts` | role-specific approval |

An agent loads T0 cold, authenticates (guided by `agent_identity` and the auth guide), reads the
T1 catalogue, and only reaches the T2 data contracts after full authorisation. Every step was
declared in advance, so the agent plans the climb rather than discovering each gate by hitting it.

## Where this sits

RFC-0011 is the **topology and discovery** layer: how an agent finds and selects manifests across
an organisation. RFC-0009 `visibility.conditions[]` is the **access control** layer for individual
units. Together they answer the full enterprise-bootstrap scenario. Both `context` and
`agent_identity` are declaration-level and advisory — KCP surfaces them; the agent (or its runtime
enforcement system) acts.

## See also

- [SPEC.md §3.6](../SPEC.md) — `manifests[].context` and `manifests[].agent_identity` field reference
- [RFC-0011](../RFC-0011-Org-Federation.md) — the full design rationale and the Org Hub / progressive-disclosure patterns
- [examples/org-federation/](../examples/org-federation/) — the runnable hub used above
- [guides/gating-restricted-knowledge-with-attestation.md](./gating-restricted-knowledge-with-attestation.md) — the consumer-identity companion (v0.22)
