# CompanyX service catalogue (internal)

> `sensitivity: internal` — visible to authenticated developers. If you are reading this, you have
> already authenticated against the hub's OAuth 2.1 issuer.

Each service publishes its own KCP manifest. The hub's `manifests[]` block federates the ones an
agent commonly needs; this catalogue is the fuller index.

| Service | Manifest | Environments | Credential |
|---------|----------|--------------|------------|
| Platform Engineering | `platform/knowledge.yaml` | prod | `github_pat` |
| Platform Engineering (dev) | `platform/knowledge-dev.yaml` | dev, test | none |
| Data Warehouse | `data/knowledge.yaml` | prod, staging | `oauth2` |
| Payments | `payments/knowledge.yaml` | prod | `oauth2` |
| Identity | `identity/knowledge.yaml` | prod, staging | `oauth2` |

To traverse to any of these, read its `agent_identity` hint on the federation entry, acquire the
credential per the [authentication guide](../guides/agent-authentication.md), and match your
runtime `context` to the entry's `context` list.
