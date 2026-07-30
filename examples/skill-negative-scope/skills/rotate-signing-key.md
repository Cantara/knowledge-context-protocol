# Rotate the manifest signing key

A governed `kind: skill` procedure. Its enaction is bounded by the `action_scope`
declared for it in [`../knowledge.yaml`](../knowledge.yaml):

- **Allowed** tools `kcp-sign`, `git`; paths `schema/**` and `.well-known/kcp-signing-key`;
  capability `key-management`.
- **Forbidden** (overrides the allowlist, fail-closed): the `schema/secrets/**` subtree,
  the `shell` tool, and the `network` capability — denied even though `schema/**` and a
  broader tool grant would otherwise reach them.

Its own `authority_level: prepare` is the ceiling it was written to hold; the manifest's
`grant_ceiling` folds that ceiling into the multi-source minimum via `unit_ref`, so the
effective authority for enacting this skill can never exceed `prepare`.
