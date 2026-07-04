# Production data contracts (confidential)

> `sensitivity: confidential`, `access: restricted`, `auth_scope: read:data-contracts` — served
> only after role-specific authorisation. This is the tightest tier of the progressive-disclosure
> ladder.

Data contracts declare, per regulated dataset, where the data may be processed and what may not
touch it. They are the "do not let an external LLM near this" layer of the enterprise.

## Example contract — customer PII

- **Residency:** EU only (`eu-west-1`, `eu-central-1`). No replication outside the EEA.
- **Regulations:** GDPR, ePrivacy.
- **Restrictions:** `no_ai_training`, `no_external_processing`.
- **Access:** requires the `read:data-contracts` scope AND a human-approved role grant.

An agent that reaches this unit has passed every tier: it loaded the public front door, authenticated
for the internal catalogue, and obtained role approval for the confidential contracts. Each step was
declared in advance — by `compliance.sensitivity` on the units and `agent_identity` on the
federation edges — so the agent never had to probe blindly.
