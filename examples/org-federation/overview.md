# CompanyX Knowledge Network — front door

Welcome. This is the public landing page for CompanyX's knowledge network. An AI agent that
arrives here with nothing but our domain name can read this file, learn what exists, and find out
how to get the credentials it needs to go further.

## What CompanyX runs

- **Platform Engineering** — CI/CD, service scaffolding, deployment runbooks.
- **Data Warehouse** — analytics datasets, data contracts, lineage.
- Plus a dozen product services, each with its own KCP manifest, catalogued once you authenticate.

## How an agent gets started

1. **Load this file.** It is `sensitivity: public` and `load_strategy: eager` — safe to read
   before you authenticate.
2. **Read the [authentication guide](guides/agent-authentication.md).** It explains the two
   credential types our sub-manifests ask for: a GitHub PAT and an OAuth 2.1 token.
3. **Pick your environment.** The federation entries in `knowledge.yaml` are tagged with
   `context` (`dev`, `test`, `staging`, `prod`). Fetch only the ones that match where you are
   running.
4. **Acquire the declared credential.** Each federation entry declares an `agent_identity` hint
   telling you what it needs *before* you fetch it. Get that credential, then traverse.
5. **Ask for more.** After authenticating, request the internal `service-catalogue` unit for the
   full list of services.

Nothing here is enforced by KCP — these are declarations. Enforcement happens when you actually
present your credential to a sub-manifest's `auth` endpoint.
