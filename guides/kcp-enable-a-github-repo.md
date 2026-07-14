# Tutorial: KCP-enable a GitHub repository, end to end

A hands-on walkthrough that takes a plain GitHub repo and turns it into one an AI
agent can navigate by intent — then signs both the manifest **and** the content
it points at, so an execution-capable agent can trust it. Every step is a command
you run; expected output is shown so you can check your work.

By the end you will have:

- a validated `knowledge.yaml` at the repo root,
- an Ed25519 signature in the RFC-0018 §4.2 envelope,
- per-unit `content_hash` binding your docs to that signature (RFC-0019),
- a `kcp render` that reports **`tier: trusted`** with **`content_verified: true`**,
- a GitHub Actions workflow that re-signs on every change.

> This is exactly how the KCP spec repo dogfoods itself. The finished result is
> live at [`knowledge.yaml`](../knowledge.yaml) + [`knowledge.yaml.sig`](../knowledge.yaml.sig).

**Time:** ~20 minutes. **Prerequisites:** Node 20+, `git`, and `openssl` (for the key).

---

## 0. Install the CLI

```bash
npm install -g @cantara.no/kcp  # provides the `kcp` developer CLI
kcp --help
```

No global install? Every command below also works as `npx --package @cantara.no/kcp kcp <command>`.

---

## 1. Scaffold the manifest

From the root of your repository:

```bash
kcp init
```

```
✓ wrote knowledge.yaml (1 unit) — edit it, then run `kcp validate`
```

`kcp init` surveys the repo and writes a starter `knowledge.yaml`. Open it: it has
the five required fields on one unit. That is a valid Level 1 manifest already.

---

## 2. Author your knowledge units

A *unit* is a file or directory that answers one recurring question. Aim for
5–20 units, not one per file. Edit `knowledge.yaml`:

```yaml
kcp_version: "0.21"
project: my-service
version: 1.0.0
units:
  - id: overview
    path: README.md
    intent: "What is this service and how do I run it locally?"
    scope: global
    audience: [human, agent]
    triggers: [overview, getting started, setup]

  - id: deploy
    path: ops/deploy.md
    intent: "How do I deploy a release to production?"
    scope: project
    audience: [operator, agent]
    triggers: [deploy, release, production, rollback]
    depends_on: [overview]
    not_for: ["local development", "CI configuration"]
```

The two fields that make routing work:

- **`intent`** — the *question* a user would ask, in natural language (not a title).
- **`triggers`** — the keywords that question contains. Add synonyms.

`not_for` (RFC-0015) stops an agent loading a unit for the wrong task. `depends_on`
sets reading order.

---

## 3. Validate

```bash
kcp validate
```

```
knowledge.yaml (2 units, kcp_version: 0.21)

✓ Valid — no errors or warnings
```

Fix every **error** (warnings are advisory). Common ones: a `path` that does not
exist, a duplicate `id`, a `depends_on` pointing at a unit that is not declared.

---

## 4. Prove the routing

Simulate an agent query before you ship:

```bash
kcp query "how do I roll back a bad release?"
```

```
→ deploy   (score 0.82)  triggers: release, production, rollback
  overview (score 0.11)
```

If the obvious questions do not route to the unit you intended, tighten the
`intent` and `triggers` and re-query. This loop is the whole point — five minutes
here is where the 53–80% tool-call savings come from.

---

## 5. Grow as needed (optional)

KCP is a strict superset at every level; add only what earns its place.

- **Relationships** — typed edges (`enables`, `supersedes`, `contradicts`, …)
  for richer navigation.
- **Temporal validity** (RFC-0010) — `temporal.valid_from` / `valid_until` /
  `superseded_by` for policies and runbooks that expire or are future-dated. See
  the [`temporal-validity` example](../examples/temporal-validity/).
- **Federation** (`manifests[]`) — link other teams' manifests as a knowledge DAG.

Stop wherever the value is. Most repos never go past Level 2.

---

## 6. Sign it — authenticate the map

So far an agent can *navigate* your repo. To let an execution-capable agent
*trust* it, sign the manifest. KCP uses Ed25519 (RFC-0018 §4.2).

Generate a signing key (keep the private key secret; you will put it in a GitHub
secret in step 8):

```bash
openssl genpkey -algorithm ed25519 -out kcp-signing.pem
```

Sign the manifest:

```bash
kcp sign knowledge.yaml --key kcp-signing.pem --key-id my-org-2026
```

```
✓ signed → knowledge.yaml.sig (key_id: my-org-2026)
  allowlist public_key: MCowBQYDK2VwAyEA...
```

`knowledge.yaml.sig` is the detached envelope `{ key_id, algorithm: "EdDSA",
public_key, signature }`. Copy that `public_key` — consumers add it to their
allowlist to trust you.

---

## 7. Bind the content — authenticate the territory

A signature over the manifest authenticates the *map*, not the *content of the
files it points at* (the *territory*). That gap is the manifest-relocation class
of attack (RFC-0019, threat T9). Close it with per-unit `content_hash`.

Add a `content_hash` block to the units whose files you want bound (single-file
paths work cleanly):

```yaml
  - id: overview
    path: README.md
    intent: "What is this service and how do I run it locally?"
    scope: global
    audience: [human, agent]
    triggers: [overview, getting started, setup]
    content_hash:
      algorithm: sha256
      value: "0"          # placeholder — filled by --update-hashes
```

Then sign with `--update-hashes`, which computes each digest over the current file
bytes before signing:

```bash
kcp sign knowledge.yaml --key kcp-signing.pem --key-id my-org-2026 --update-hashes
```

```
↻ content_hash refreshed: overview
✓ signed → knowledge.yaml.sig (key_id: my-org-2026)
```

Now the signature covers your README's bytes too. `kcp validate` re-checks the
hash against disk and **errors** if a file changed without a re-sign — so drift
can't slip through.

---

## 8. Verify it as a consumer would

Build a trusted-keys allowlist with the `public_key` from step 6, scoped to your
GitHub org so the renderer pins it:

```bash
cat > trusted-keys.yaml <<'EOF'
version: 1
keys:
  - key_id: my-org-2026
    method: jws
    algorithm: EdDSA
    public_key: "MCowBQYDK2VwAyEA..."     # paste yours
    scope:
      domains: ["github.com/my-org"]
EOF
```

Render through the trusted pipeline:

```bash
kcp render knowledge.yaml --keys trusted-keys.yaml --origin github.com/my-org/my-service
```

```
✓ rendered (tier: trusted)
```

The rendered artifact reports `content_verified: true` on every hash-bound unit.
That is the goal: an agent ingesting the **rendered** output — never the raw
manifest — gets sanitized, trust-tiered, content-verified knowledge.

> **Why render at all?** Reading a raw `knowledge.yaml` places third-party prose
> directly into an execution-capable agent's context — an injection channel. The
> render pipeline (SPEC §16) makes the trust decision first, deterministically and
> LLM-free. A manifest may influence what an agent knows, never what it does.

---

## 9. Automate signing in GitHub Actions

Put the private key in a repository secret named `KCP_SIGNING_KEY` (paste the PEM
from step 6), then add `.github/workflows/sign-manifests.yml`:

```yaml
name: Sign KCP manifest
on:
  push:
    branches: [main]
    paths: ['knowledge.yaml', 'README.md', 'ops/**']   # docs you bind hashes over
  workflow_dispatch:
jobs:
  sign:
    runs-on: ubuntu-latest
    if: "!contains(github.event.head_commit.message, '[skip-sign]')"
    permissions: { contents: write }
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-node@v4
        with: { node-version: '20' }
      - run: npm install -g @cantara.no/kcp
      - name: Restore signing key
        env: { KCP_SIGNING_KEY: "${{ secrets.KCP_SIGNING_KEY }}" }
        run: printf '%s' "$KCP_SIGNING_KEY" > /tmp/k.pem && chmod 600 /tmp/k.pem
      - name: Sign (refresh hashes first)
        run: kcp sign knowledge.yaml --key /tmp/k.pem --key-id my-org-2026 --update-hashes
      - name: Commit
        run: |
          git config user.name kcp-bot
          git config user.email bot@example.com
          git add knowledge.yaml knowledge.yaml.sig
          git diff --staged --quiet || (git commit -m "chore: re-sign [skip-sign]" && git push)
```

Now every change to your manifest **or** the documents it binds re-hashes and
re-signs automatically. The `--update-hashes` + the doc paths in the trigger are
what keep the content signature from going stale.

---

## 10. Publish for discovery

Make agents find you without being told:

- Serve the public key at `/.well-known/kcp-signing-key.pub`.
- Add a `signing:` block to the manifest pointing at the key and `.sig` URLs.
- Add `> knowledge: /knowledge.yaml` to your `llms.txt` (RFC-0008 cold discovery).

---

## You're done

Your repo now ships authenticated, content-verified, agent-navigable knowledge.
A consumer with your key on their allowlist renders it to `tier: trusted` with the
content cryptographically bound — the same posture the KCP spec repo holds over
its own [`knowledge.yaml`](../knowledge.yaml).

**Next:**

- The [`kcp-adopt` / `kcp-author` / `kcp-render` Agent Skills](../skills/) let an
  agent drive all of the above for you.
- [`SPEC.md`](../SPEC.md) for the full field set; [`guides/adopting-kcp-in-existing-projects.md`](./adopting-kcp-in-existing-projects.md)
  for migrating a large existing docs set.
