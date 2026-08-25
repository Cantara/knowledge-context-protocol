# knowledge-context-protocol

KCP is the open specification for `knowledge.yaml` — a manifest format that makes a project's
knowledge navigable by AI agents (intent, scope, audience, freshness, dependencies), the way MCP
made tools navigable. This repo *is* the spec: `SPEC.md`, the RFCs, reference parsers/bridges
(TypeScript, Java, Python), the `kcp` CLI, and the repo's own dogfooded `knowledge.yaml`.

## Start here: knowledge.yaml

This repo's root `knowledge.yaml` is the canonical, agent-navigable map of its own content —
read it before exploring by hand. Query it with the `kcp` CLI (`kcp query "<question>"`,
installed via [kcp-commands](https://github.com/Cantara/kcp-commands)), or read the file
directly if the CLI isn't available.

## Skills

- **Shared governed-skill authoring conventions** — how to write a `kind: skill` unit with
  `action_scope` as a firewall rule (tools/paths/capabilities): see
  [kcp-skill](https://github.com/Cantara/kcp-skill)'s `PROFILE.md`. That repo owns the
  convention; don't copy its content here.
- **`skills/`** (this repo) — the four portable Agent Skills KCP itself ships (`kcp-adopt`,
  `kcp-author`, `kcp-navigate`, `kcp-render`), for *using* KCP in any project. They're
  registered as governed `kind: skill` units in this repo's own `knowledge.yaml`. See
  `skills/README.md`.

## Gotchas

- **No v0.15 spec version.** Skipped deliberately to re-sync the spec version number with the
  `kcp` CLI release train — not a missing file.
- **Version numbers drift across files** (README's "Current version" line and its `kcp_version`
  code snippet, `knowledge.yaml`'s legacy `kcp_version` header vs its `spec_version` field).
  Trust `CHANGELOG.md`'s newest entry for "what's current," not any single field.
- **`docs/` (GitHub Pages) only deploys from `main`.** Changes made on a feature branch won't
  appear on the live site until merged — the most commonly missed step in a spec release
  (see `CONTRIBUTING.md`'s release checklist).
- **Spec version bumps require parser+bridge parity.** TypeScript, Java, and Python parsers
  and MCP bridges all update together, or cross-language conformance tests fail
  (`conformance/`).
