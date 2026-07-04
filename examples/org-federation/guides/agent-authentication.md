# Agent authentication at CompanyX

Our sub-manifests declare, up front, what credential an agent needs before fetching them. This
guide explains how a developer running an agent obtains each one. You never need to guess: read
the `agent_identity.credential_hint` on a federation entry, then follow the matching section here.

## `github_pat` — Platform Engineering (prod)

The production Platform Engineering hub is behind our internal GitHub. Create a fine-grained
Personal Access Token scoped to read the `platform/knowledge` repository, then hand it to your
agent as its Git credential. The federation entry sets `required: true`, so acquire it **before**
attempting the fetch.

## `oauth2` — Data Warehouse (prod, staging)

The Data Warehouse uses OAuth 2.1. The federation entry carries an `issuer_hint`
(`https://auth.companyx.example`) — begin the authorization-code flow against that issuer, request
the `read:catalogue` scope, and present the resulting bearer token. This is the same issuer the
hub's own `auth.methods` names, so a token obtained here also unlocks the hub's internal tier.

## No credential — Dev mirror

The dev mirror of Platform Engineering (`context: ["dev", "test"]`) sets
`agent_identity.required: false`. An agent running in a dev or test environment can fetch it
directly; its own `auth` block still governs anything sensitive inside.

## The rule of thumb

`agent_identity` tells you what to bring. The sub-manifest's `auth` block checks it at the door.
KCP never performs either step for you — it only makes the requirement legible ahead of time so
your agent can plan instead of failing a fetch and backtracking.
