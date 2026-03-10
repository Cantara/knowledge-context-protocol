# Knowledge Context Protocol (KCP) Specification

**Version:** 0.9
**Status:** Draft
**Date:** 2026-03-10
**Repository:** github.com/cantara/knowledge-context-protocol

---

## Abstract

The Knowledge Context Protocol (KCP) defines a file format for structured knowledge manifests.
A KCP manifest (`knowledge.yaml`) describes the knowledge units in a project — their intent,
dependencies, freshness, and audience — in a way that AI agents can navigate without loading
everything at once.

KCP is a format specification, not a runtime protocol. It requires no server, no database, and
no running process. A static site or a git repository can be fully KCP-compliant.

---

## Status of This Document

This is a draft specification. The format is intentionally minimal and subject to revision based
on implementation feedback. Implementations SHOULD declare which version of this specification
they conform to using the `kcp_version` field.

---

## Conformance Language

The key words "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT", "SHOULD", "SHOULD NOT",
"RECOMMENDED", "MAY", and "OPTIONAL" in this document are to be interpreted as described in
[RFC 2119](https://www.rfc-editor.org/rfc/rfc2119).

---

## 1. File Discovery

### 1.1 Canonical Location

The canonical KCP manifest file is named `knowledge.yaml` and SHOULD be placed at the root of
the project or documentation site — the same level as `README.md`, `llms.txt`, or equivalent
root files.

### 1.2 Alternative Location via llms.txt

If the manifest is not at the root, its location MAY be declared in `llms.txt` using a
`knowledge:` metadata line:

```
> knowledge: /docs/knowledge.yaml
```

This line MUST appear in the header section of `llms.txt` (before the first `##` section
heading). The value is a path relative to the site or repository root, beginning with `/`.

Parsers encountering a `knowledge:` declaration in `llms.txt` SHOULD use that path instead of
the default root location.

### 1.3 Multiple Manifests

A project MAY contain multiple manifests (e.g. one per subdirectory). Each manifest is
independent and MUST NOT reference units from other manifests by path. Cross-manifest
federation is supported via the `manifests` block — see §3.6.

### 1.4 Discovery via `/.well-known/kcp.json`

An origin server MAY expose a well-known discovery document at `/.well-known/kcp.json`
as defined by [RFC 8615](https://datatracker.ietf.org/doc/html/rfc8615). This enables
agents and crawlers to locate KCP manifests on any HTTP origin without prior knowledge
of the manifest path.

A GET request to `/.well-known/kcp.json` SHOULD return a JSON document with
`Content-Type: application/json`. The document MUST include:

| Field | Type | Description |
|-------|------|-------------|
| `kcp_version` | string | The KCP specification version this manifest conforms to. |
| `manifest` | string | Absolute URL or root-relative path to the `knowledge.yaml` manifest. |

The document MAY include:

| Field | Type | Description |
|-------|------|-------------|
| `title` | string | Human-readable name of the project or knowledge base. |
| `description` | string | Brief summary of the knowledge available. |
| `spec` | string | URL of the KCP specification document. |

Example:

```json
{
  "kcp_version": "0.9",
  "manifest": "/knowledge.yaml",
  "title": "My Project Knowledge Base",
  "description": "Architecture decisions, API reference, and onboarding guides.",
  "spec": "https://github.com/Cantara/knowledge-context-protocol"
}
```

Agents encountering `/.well-known/kcp.json` SHOULD fetch the `manifest` URL to retrieve
the full KCP manifest. Agents that successfully retrieve a manifest via this mechanism
MUST NOT require the manifest to also be present at the repository root.

This discovery path complements §1.1 (root placement) and §1.2 (llms.txt declaration).
An origin MAY support all three; agents SHOULD prefer `/.well-known/kcp.json` when
performing HTTP-based discovery on a live site.

---

## 2. File Format

KCP manifests MUST be valid YAML 1.2. The file MUST be UTF-8 encoded without a BOM.

Parsers MUST silently ignore fields they do not recognise. This ensures forward compatibility:
a manifest valid for a future version of the spec remains parseable by implementations of this
version.

---

## 3. Root Manifest Structure

```yaml
kcp_version: "0.9"          # RECOMMENDED
project: <string>            # REQUIRED
version: <semver string>     # RECOMMENDED
updated: "<ISO date>"        # RECOMMENDED; quote the value (see §4.1.1)
language: <BCP 47 tag>       # OPTIONAL; default language for all units (see §4.4c)
license: <string or object>  # OPTIONAL; default license for all units (see §4.6a)
indexing: <string or object>  # OPTIONAL; default indexing permissions (see §4.6c)
hints: <object>              # OPTIONAL; manifest-level aggregate hints (see §4.10)
trust: <object>              # OPTIONAL; publisher provenance and audit requirements (see §3.2)
auth: <object>               # OPTIONAL; authentication methods for this knowledge source (see §3.3)
delegation: <object>         # OPTIONAL; delegation chain constraints for multi-agent access (see §3.4)
compliance: <object>         # OPTIONAL; compliance classification and processing restrictions (see §3.5)
payment: <object>            # OPTIONAL; default monetisation tier for all units (see §4.14)
manifests: <list>            # OPTIONAL; federation declarations (see §3.6)
external_relationships: <list>  # OPTIONAL; cross-manifest relationship declarations (see §3.6)

units:                       # REQUIRED; list of knowledge units
  - ...

relationships:               # OPTIONAL; list of cross-unit relationship declarations
  - ...
```

### 3.1 Root Fields

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `kcp_version` | RECOMMENDED | string | Version of this specification. MUST be `"0.9"` for conformance with this document. |
| `project` | REQUIRED | string | Human-readable name of the project or documentation site. |
| `version` | RECOMMENDED | string | Semver version of this manifest. Increment when units are added or removed. |
| `updated` | RECOMMENDED | string | ISO 8601 date (`YYYY-MM-DD`) when this manifest was last modified. |
| `language` | OPTIONAL | string | BCP 47 language tag as default for all units. See §4.4c. |
| `license` | OPTIONAL | string or object | Default license for all units. See §4.6a. |
| `indexing` | OPTIONAL | string or object | Default indexing permissions for all units. See §4.6c. |
| `hints` | OPTIONAL | object | Manifest-level aggregate context hints. See §4.10. |
| `trust` | OPTIONAL | object | Publisher provenance and audit requirements for this manifest. See §3.2. |
| `auth` | OPTIONAL | object | Authentication methods for this knowledge source. See §3.3. |
| `delegation` | OPTIONAL | object | Delegation chain constraints for multi-agent access. See §3.4. |
| `compliance` | OPTIONAL | object | Compliance classification, data residency, and processing restrictions. See §3.5. |
| `payment` | OPTIONAL | object | Default monetisation tier for all units. See §4.14. |
| `manifests` | OPTIONAL | list | Federation declarations — sub-manifests this manifest has a relationship with. See §3.6. |
| `external_relationships` | OPTIONAL | list | Cross-manifest relationship declarations. See §3.6. |
| `units` | REQUIRED | list | Ordered list of knowledge unit declarations. MUST contain at least one unit. |
| `relationships` | OPTIONAL | list | Explicit cross-unit relationship declarations. See §5. |

### 3.2 `trust`

The root-level `trust` block declares the provenance of this manifest — who published it and
how to contact them — and what audit behaviour is expected from agents that access it. It is
advisory metadata: it carries no cryptographic weight unless combined with external signing
infrastructure (see §14.1).

```yaml
trust:
  provenance:
    publisher: "Acme Corp"
    publisher_url: "https://acme.com"
    contact: "knowledge-team@acme.com"
  audit:
    agent_must_log: true
    require_trace_context: true
```

#### `trust.provenance` sub-fields

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `publisher` | OPTIONAL | string | Human-readable name of the publishing organisation or individual. |
| `publisher_url` | OPTIONAL | string | URL of the publisher's web presence. MUST use HTTPS if present. |
| `contact` | OPTIONAL | string | Email address or URL for questions about this manifest's content. |

#### `trust.audit` sub-fields

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `agent_must_log` | OPTIONAL | boolean | Advisory: agents SHOULD record access to this manifest's units in their own audit trail. Default: `false`. |
| `require_trace_context` | OPTIONAL | boolean | If `true`: agents MUST include [W3C Trace Context](https://www.w3.org/TR/trace-context/) headers (`traceparent`, `tracestate`) when fetching content via HTTP, so the full access chain can be reconstructed. Compatible with [OpenTelemetry](https://opentelemetry.io/). Default: `false`. |

**`require_trace_context` and local file access:** KCP manifests are frequently consumed from
local file systems or git repositories where no HTTP request occurs. When content is accessed
locally rather than via HTTP, the `traceparent`/`tracestate` header semantics do not apply.
In this case, agents SHOULD generate a `traceparent` value conforming to the W3C Trace Context
specification and record it alongside the access event in their own audit trail. The intent
is the same — a reconstructable trace of which agent accessed which unit and when — regardless
of whether the access occurred over HTTP or via the local file system.

All sub-fields of `trust` are OPTIONAL. An empty `trust` block (no sub-fields) is valid and
SHOULD be silently accepted. Unknown sub-fields MUST be silently ignored.

Cryptographic content integrity, access receipts, and agent attestation requirements are
defined in [RFC-0004](./RFC-0004-Trust-and-Compliance.md) and may be promoted to the core spec
in a future version.

### 3.3 `auth`

The root-level `auth` block describes how to authenticate to the knowledge source. It is
relevant when the manifest is served via a KCP-aware MCP server, an HTTP endpoint, or a
federated discovery registry. For manifests served from public git repositories or static
sites with no access control, the `auth` block MAY be omitted entirely.

```yaml
auth:
  methods:
    - type: oauth2
      issuer: "https://auth.example.com"
      scopes: ["read:knowledge"]

    - type: api_key
      header: "X-API-Key"
      registration_url: "https://example.com/register"

    - type: none
```

#### How agents use the `auth` block

1. Agent discovers a manifest and inspects units for `access` values (see §4.11).
2. Any unit with `access: authenticated` or `access: restricted` triggers credential
   acquisition.
3. Agent reads `auth.methods` to determine how to acquire credentials.
4. Agent selects the first method it supports and proceeds.
5. If no supported method exists, the agent SHOULD surface this to its operator rather than
   silently failing.

#### `auth.methods`

The `methods` list declares one or more authentication schemes supported by this knowledge
source, in preference order. Agents try methods in list order until one succeeds. This enables
graceful degradation across environments (e.g. prefer OAuth 2.1 but accept API key).

Each entry MUST have a `type` field. The following types are defined in this version of the
specification:

| Type | Description | Sub-fields |
|------|-------------|------------|
| `none` | No credentials required. | None. |
| `oauth2` | OAuth 2.1 authentication. | `issuer`, `scopes`, `registration_url` |
| `api_key` | API key passed in a named HTTP header. | `header`, `registration_url` |

Unknown `type` values MUST be silently ignored by parsers. This enables forward compatibility
with additional auth types defined in [RFC-0002](./RFC-0002-Auth-and-Delegation.md) (e.g.
`spiffe`, `did`, `bearer_token`, `http_signature`) without requiring core spec changes.

##### `type: none`

Declares that the knowledge source accepts unauthenticated access. When `none` appears in a
list alongside other methods, it serves as a fallback — agents that cannot satisfy any other
method may access content without credentials.

**Interaction with `access`:** Units with `access: public` are implicitly accessible via
`type: none`. Units with `access: authenticated` or `access: restricted` require a
non-`none` method; the presence of `type: none` in the methods list does not satisfy their
access requirement. In practice, manifests with mixed access levels (some public, some
restricted) will typically list `type: none` as the last method to signal that the public
units are freely accessible.

##### `type: oauth2`

Declares that the knowledge source supports [OAuth 2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-12)
authentication. This is the baseline auth mechanism — consistent with
[MCP's authorization specification](https://modelcontextprotocol.io/specification/draft/basic/authorization),
which also requires OAuth 2.1.

| Sub-field | Required | Description |
|-----------|----------|-------------|
| `issuer` | REQUIRED | OAuth 2.1 issuer URL. Agents can discover endpoints via `{issuer}/.well-known/oauth-authorization-server` ([RFC 8414](https://datatracker.ietf.org/doc/html/rfc8414)). MUST use HTTPS. |
| `scopes` | OPTIONAL | List of OAuth scopes to request. |
| `registration_url` | OPTIONAL | URL where agents or operators can register for credentials. |

##### `type: api_key`

Declares that the knowledge source accepts an API key passed in a named HTTP header. This is
a simpler alternative to OAuth for knowledge sources that do not require delegated
authorization.

| Sub-field | Required | Description |
|-----------|----------|-------------|
| `header` | REQUIRED | Name of the HTTP header that carries the API key (e.g. `"X-API-Key"`, `"Authorization"`). |
| `registration_url` | OPTIONAL | URL where agents or operators can register for an API key. |

#### `auth` block conformance

- `auth` is OPTIONAL. Omitting it means no authentication metadata is declared.
- Parsers MUST NOT reject a manifest because `auth` is absent, even if units declare
  `access: authenticated` or `access: restricted`.
- When `auth` is present but no method in `auth.methods` is recognised by the parser, the
  parser SHOULD emit a warning and MUST NOT reject the manifest.
- Unknown sub-fields within any method entry MUST be silently ignored.

---

### 3.4 `delegation`

The root-level `delegation` block declares constraints on how knowledge units in this manifest
may be accessed through agent delegation chains. Multi-agent systems commonly involve a human
authorising an orchestrator, which delegates to sub-agents, which may further delegate. This
block limits how far that chain may extend and what conditions it must satisfy.

```yaml
delegation:
  max_depth: 3                         # OPTIONAL; maximum hops from resource owner to accessing agent
  require_capability_attenuation: true # OPTIONAL; each hop MUST narrow permissions, not expand them
  require_delegation_proof: false      # OPTIONAL; agent MUST present a verifiable chain
  audit_chain: true                    # OPTIONAL; W3C Trace Context required on all access requests
  human_in_the_loop:                   # OPTIONAL; root-level human approval default
    required: false
    approval_mechanism: oauth_consent  # uma | oauth_consent | custom
    docs_url: "https://..."            # required when approval_mechanism is "custom"
```

#### `delegation` field reference

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `max_depth` | OPTIONAL | integer | Maximum delegation chain depth. **The resource owner operates at depth 0.** The first agent to which access is delegated operates at depth 1. `max_depth: 0` means no delegation is permitted — only the resource owner may access the unit directly. Omit for no constraint. |
| `require_capability_attenuation` | OPTIONAL | boolean | If `true`, each hop in the delegation chain MUST present narrower permissions than the delegating agent held. Agents SHOULD reject delegation tokens that grant equal or greater scope than the parent token. |
| `audit_chain` | OPTIONAL | boolean | If `true`, agents MUST include W3C Trace Context `traceparent`/`tracestate` headers on all access requests, enabling full delegation chain reconstruction from access logs. Compatible with OpenTelemetry. |
| `human_in_the_loop` | OPTIONAL | object | Root-level human approval requirement. May be overridden at unit level. |

#### `delegation.human_in_the_loop` sub-fields

| Sub-field | Required | Description |
|-----------|----------|-------------|
| `required` | OPTIONAL | If `true`, a human MUST approve agent access before the unit may be loaded. Default: `false`. |
| `approval_mechanism` | OPTIONAL | How human approval is obtained: `oauth_consent` (OAuth 2.1 authorization code flow with user present), `uma` (UMA 2.0 asynchronous resource owner policy), or `custom` (described at `docs_url`). |
| `docs_url` | OPTIONAL | URL describing the approval flow. REQUIRED when `approval_mechanism` is `custom`. |

#### Per-unit `delegation` overrides

Individual units MAY declare a `delegation` block to tighten constraints beyond the root
defaults. A per-unit `delegation` block MUST NOT relax root constraints (e.g. `max_depth` at
unit level MUST NOT exceed `max_depth` at root level).

```yaml
units:
  - id: patient-records
    path: data/patients.md
    access: restricted
    auth_scope: clinical-staff
    delegation:
      max_depth: 1
      human_in_the_loop:
        required: true
        approval_mechanism: oauth_consent
```

#### Security properties

| Attack class | Mitigation |
|--------------|-----------|
| Agent session smuggling | `max_depth` limits the delegation chain length |
| Cross-agent privilege escalation | `require_capability_attenuation` |
| Unauthorised autonomous access | `human_in_the_loop.required: true` |
| Audit evasion | `audit_chain` + W3C Trace Context |

#### Known limitations (v0.7)

**Capability attenuation is declarative.** `require_capability_attenuation: true` declares
that scope narrowing MUST occur but does not define a scope comparison function. Implementations
SHOULD treat OAuth scope strings as a hierarchy where a more specific scope (e.g.
`read:case:external-summary`) is a narrowing of its prefix (e.g. `read:case`). A formal scope
comparison specification is planned for a future version.

**Delegation chain integrity requires signed tokens.** Without signed lineage tokens, a
misconfigured or malicious agent may claim any delegation depth. A `require_delegation_proof`
field (reserved for future use) is specified in RFC-0002 for implementations that issue signed
delegation credentials; the token format and normative field definition are deferred to v0.9+.

#### `delegation` block conformance

- `delegation` is OPTIONAL at both root and unit level.
- Parsers MUST NOT reject a manifest because `delegation` is absent.
- Parsers MUST silently ignore unrecognised sub-fields.
- When a per-unit `max_depth` is present, it MUST be ≤ the root `max_depth` if a root value is set.
- `kcp_version: "0.7"` manifests MUST NOT fail validation if `delegation` is present; parsers
  that do not recognise `delegation` MUST silently ignore it.

---

### 3.5 `compliance`

The root-level `compliance` block declares the regulatory context, data residency requirements,
sensitivity classification, and processing restrictions that apply to this manifest. It is
advisory metadata: compliance enforcement depends on the consuming agent and its runtime
environment.

Agents that cannot satisfy the compliance requirements declared for a unit SHOULD NOT access
that unit and SHOULD surface the constraint to their operator.

```yaml
compliance:
  data_residency: [EEA]        # permitted geographic regions (ISO 3166-1 alpha-2 or region identifiers)
  regulations: [GDPR, NIS2]   # applicable regulations (see regulation vocabulary)
  sensitivity: internal        # public | internal | confidential | restricted
  restrictions:                # processing restrictions (see restriction vocabulary)
    - no-external-llm
    - audit-required
```

#### `compliance` field reference

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `data_residency` | OPTIONAL | list | Permitted geographic regions for data storage and processing. ISO 3166-1 alpha-2 codes or region identifiers (e.g. `EEA`, `US`, `CH`). See region vocabulary. |
| `regulations` | OPTIONAL | list | Named regulations that apply to this knowledge. Unknown values MUST be silently ignored. |
| `sensitivity` | OPTIONAL | string | Information sensitivity level. Overrides `sensitivity` declared at the unit level (§4.11) when used in a root compliance block; at unit level they are equivalent. |
| `restrictions` | OPTIONAL | list | Processing restrictions. Unknown values MUST be silently ignored. |

#### Sensitivity levels

Aligned with ISO 27001 and common national information classification frameworks:

| Value | Meaning |
|-------|---------|
| `public` | No restrictions. Freely shareable. |
| `internal` | For internal use only. Not for external parties without authorisation. |
| `confidential` | Restricted within the organisation. Need-to-know basis. |
| `restricted` | Highest sensitivity. Strict access controls required. |

#### Regulation vocabulary

Implementations SHOULD recognise the following named values:

| Value | Regulation |
|-------|-----------|
| `GDPR` | EU General Data Protection Regulation |
| `ePrivacy` | EU ePrivacy Directive / Regulation |
| `NIS2` | EU Network and Information Security Directive 2 |
| `HIPAA` | US Health Insurance Portability and Accountability Act |
| `HITECH` | US Health Information Technology for Economic and Clinical Health Act |
| `CCPA` | California Consumer Privacy Act |
| `MiFID2` | EU Markets in Financial Instruments Directive II |
| `DORA` | EU Digital Operational Resilience Act (financial sector) |
| `AML5D` | EU 5th Anti-Money Laundering Directive |
| `FATF` | Financial Action Task Force recommendations |
| `ITAR` | US International Traffic in Arms Regulations |
| `eIDAS` | EU Electronic Identification, Authentication and Trust Services |

Unknown values MUST be silently ignored.

#### Restriction vocabulary

| Value | Meaning |
|-------|---------|
| `no-external-llm` | Knowledge MUST NOT be sent to an externally-hosted language model. |
| `no-logging` | Knowledge MUST NOT be written to persistent logs. |
| `no-cross-border` | Knowledge MUST NOT leave the declared `data_residency` regions. |
| `no-ai-training` | Knowledge MUST NOT be used to train or fine-tune AI models. |
| `audit-required` | All access MUST be logged with sufficient detail for compliance audit. |
| `human-approval-required` | A human MUST approve access before the unit is loaded. Connects to `delegation.human_in_the_loop`. |

Unknown values MUST be silently ignored.

#### Per-unit `compliance` overrides

Units MAY declare their own `compliance` block to tighten or loosen the root defaults for that
unit specifically:

```yaml
units:
  - id: customer-data
    path: data/customers.md
    compliance:
      data_residency: [EEA]
      regulations: [GDPR, ePrivacy]
      sensitivity: confidential
      restrictions:
        - no-external-llm
        - audit-required

  - id: public-overview
    path: README.md
    compliance:
      sensitivity: public     # override: less restrictive than root default
```

#### Known limitations (v0.7)

**Restriction enforcement is application-defined.** The restriction vocabulary declares intent.
There is no standard technical mechanism to verify that a `no-ai-training` restriction has been
honoured by the consuming agent. Agents SHOULD treat restrictions as contractual commitments
and record them in audit logs as evidence of acknowledged obligation.

**Data residency evaluation requires runtime context.** A parser cannot determine the
geographic location of the consuming agent at parse time. Agents SHOULD evaluate
`data_residency` against their deployment context and decline to access units they cannot
process in compliance.

#### `compliance` block conformance

- `compliance` is OPTIONAL at both root and unit level.
- Parsers MUST NOT reject a manifest because `compliance` is absent.
- Parsers MUST silently ignore unrecognised sub-fields and unknown list values.
- Per-unit `compliance` values override root `compliance` for that unit only.
- `kcp_version: "0.7"` manifests MUST NOT fail validation if `compliance` is present; parsers
  that do not recognise `compliance` MUST silently ignore it.

### 3.6 Federation

The optional `manifests` block declares external KCP manifests that this manifest has
a relationship with. This enables cross-manifest dependency tracking and federated
knowledge graphs.

#### `manifests` block

```yaml
manifests:
  - id: platform
    url: "https://platform-team.example.com/knowledge.yaml"
    label: "Platform Engineering"
    relationship: foundation
    update_frequency: weekly
    local_mirror: "./mirrors/platform-knowledge.yaml"
```

#### `manifests` entry fields

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `id` | REQUIRED | string | Local identifier. MUST match `^[a-z0-9.\-]+$`. MUST be unique within this manifest's `manifests` block. |
| `url` | REQUIRED | string | HTTPS URL of the remote `knowledge.yaml`. MUST use HTTPS. MUST NOT resolve to private address ranges (§14.3). |
| `label` | RECOMMENDED | string | Human-readable description. |
| `relationship` | RECOMMENDED | string | How this sub-manifest relates to the declaring manifest. See values below. |
| `auth` | OPTIONAL | object | Auth block (per §3.3) for fetching this specific manifest. Overrides root `auth` block for this fetch. |
| `update_frequency` | OPTIONAL | string | How often this remote manifest typically changes. Uses the §4.6b vocabulary: `daily`, `weekly`, `monthly`, `rarely`, `never`. Agents MAY use this for cache freshness decisions. |
| `local_mirror` | OPTIONAL | string | Relative path (forward slashes, relative to this manifest) to a local copy of the remote manifest. When present and the file exists, parsers MUST load from that path instead of fetching `url`. |

#### `manifests[].relationship` values

| Value | Meaning |
|-------|---------|
| `child` | Sub-manifest depends on the declaring manifest's context. |
| `foundation` | Sub-manifest provides foundational knowledge the declaring manifest builds on. |
| `governs` | Sub-manifest contains authoritative policies that govern the declaring manifest. |
| `peer` | Sub-manifest is at the same level; the relationship is symmetric. |
| `archive` | Sub-manifest is historical. Agents MAY skip unless specifically requested. |

Unknown `relationship` values MUST be silently ignored.

#### Transitive resolution

A manifest declared in a `manifests` block MAY itself contain a `manifests` block.
Parsers MUST resolve the full transitive graph of `manifests` declarations.

**Topology:** DAG with local authority. Each manifest is authoritative only over the
sub-manifests it directly declares. Trust does not propagate transitively.

**Cycle detection:** Parsers MUST maintain a visited set of resolved manifest URLs
across the entire resolution session. A manifest URL already in the visited set MUST
NOT be fetched again. This handles both cycles (A -> B -> A) and diamonds
(A -> B, A -> C, B -> D, C -> D) correctly — D is fetched once.

**Fetch limits:**
- Parsers MUST enforce a maximum of unique manifests per session. RECOMMENDED default: 50.
- Parsers SHOULD emit a warning when this limit is reached.
- Remote manifests larger than 1 MB SHOULD be rejected with a warning.
- Remote manifests containing more than 10,000 units SHOULD be rejected with a warning.

**Fetch timeout:** Parsers MUST enforce a timeout on remote manifest fetches.
RECOMMENDED default: 10 seconds. A timed-out fetch is treated as a network error.

**Local mirror resolution order:**
1. If `local_mirror` is present and the referenced file exists, the parser MUST load
   from that path. `url` is NOT fetched.
2. If `local_mirror` is absent or the file does not exist, the parser SHOULD fetch `url`.
3. If the URL fetch fails, apply `on_failure` behaviour from `external_depends_on` entries
   that reference this manifest.

#### `external_depends_on` (unit-level)

A unit may declare cross-manifest dependencies:

```yaml
units:
  - id: data-handling
    path: compliance/data.md
    intent: "How does this service handle personal data?"
    external_depends_on:
      - manifest: security        # references manifests[].id
        unit: gdpr-policy         # unit id in the remote manifest
        on_failure: degrade       # skip | warn | degrade (default: skip)
```

#### `external_depends_on` entry fields

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `manifest` | REQUIRED | string | The `id` of an entry in this manifest's `manifests` block. Unknown IDs MUST produce a validation warning. |
| `unit` | REQUIRED | string | The `id` of a unit in the referenced manifest. Advisory at parse time — existence cannot be verified without fetching. |
| `on_failure` | OPTIONAL | string | Agent behaviour when the external unit cannot be resolved. `skip` (silently ignore, default), `warn` (emit a warning to the operator), `degrade` (agent MUST indicate output is operating with incomplete dependencies). Unknown values MUST be treated as `skip`. |

#### `external_relationships` (root-level)

Explicit typed relationships between units across manifest boundaries:

```yaml
external_relationships:
  - from_manifest: security       # OPTIONAL — omit = this manifest
    from_unit: gdpr-policy        # REQUIRED
    to_unit: data-handling        # REQUIRED
    type: governs                 # same vocabulary as §5
```

#### `external_relationships` entry fields

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `from_manifest` | OPTIONAL | string | Source manifest `id`. Omit = this manifest. |
| `from_unit` | REQUIRED | string | Source unit `id`. |
| `to_manifest` | OPTIONAL | string | Target manifest `id`. Omit = this manifest. |
| `to_unit` | REQUIRED | string | Target unit `id`. |
| `type` | REQUIRED | string | Relationship type. Same vocabulary as §5. Unknown types MUST be silently ignored. |

At least one of `from_manifest` or `to_manifest` SHOULD be present (otherwise the
relationship belongs in `relationships`, not `external_relationships`).

#### Authority model

Each manifest is authoritative only over the sub-manifests it directly declares.
A manifest does NOT inherit authority over sub-manifests declared by its transitive
dependencies.

Trust propagation across transitive boundaries is an agent-level policy decision,
outside the scope of this specification.

#### Self-contained manifests

A manifest with a `manifests` block MUST remain valid and parseable when loaded in
isolation without fetching any remote manifests. The `manifests` block is metadata
for federation-capable tools; tools that do not support federation MUST silently
ignore it (per §2, forward compatibility).

#### Known limitations (0.9.0)

- **Version pinning**: Remote manifests are fetched at their current version. Pinning
  to a specific version is not supported. Planned for 0.10.
- **Peer-to-peer without a declaring manifest**: Any manifest can declare sub-manifests,
  but a manifest cannot reference another without one of them declaring the relationship.
  Arbitrary undeclared cross-referencing is not supported.

---

## 4. Knowledge Units

Each entry in `units` describes a self-contained piece of knowledge.

### 4.1 Unit Fields

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `id` | REQUIRED | string | Unique identifier within this manifest. See §4.2. |
| `path` | REQUIRED | string | Relative path to the content file. See §4.3. |
| `kind` | OPTIONAL | string | Type of artifact. One of: `knowledge`, `schema`, `service`, `policy`, `executable`. See §4.3a. Default: `knowledge`. |
| `intent` | REQUIRED | string | One sentence: what question does this unit answer? See §4.4. |
| `format` | OPTIONAL | string | Content format of the referenced file. See §4.4a. |
| `content_type` | OPTIONAL | string | MIME type for precise format identification. See §4.4b. |
| `language` | OPTIONAL | string | BCP 47 language tag. See §4.4c. |
| `scope` | REQUIRED | string | Breadth of applicability. One of: `global`, `project`, `module`. |
| `audience` | REQUIRED | list of strings | Who this unit is for. See §4.6. |
| `license` | OPTIONAL | string or object | SPDX identifier or structured license metadata. See §4.6a. |
| `validated` | RECOMMENDED | string | ISO 8601 date (quoted) when a human last confirmed the content was accurate. See §4.1.1. |
| `update_frequency` | OPTIONAL | string | How often this content typically changes. See §4.6b. |
| `indexing` | OPTIONAL | string or object | AI crawling and indexing permissions. See §4.6c. |
| `depends_on` | OPTIONAL | list of strings | IDs of units that SHOULD be loaded before this one. See §4.7. |
| `supersedes` | OPTIONAL | string | ID of the unit this replaces. See §4.8. |
| `triggers` | OPTIONAL | list of strings | Keywords or task contexts that make this unit relevant. See §4.9. |
| `hints` | OPTIONAL | object | Advisory context window hints: size, loading strategy, and summary relationships. See §4.10. |
| `access` | OPTIONAL | string | Who can fetch this unit's content. One of: `public`, `authenticated`, `restricted`. Default: `public`. See §4.11. |
| `auth_scope` | OPTIONAL | string | Named scope, role, or group required when `access` is `restricted`. See §4.11. |
| `sensitivity` | OPTIONAL | string | Information classification level. One of: `public`, `internal`, `confidential`, `restricted`. See §4.12. |
| `deprecated` | OPTIONAL | boolean | If `true`, this unit is present but should not be used for new development. See §4.13. |
| `payment` | OPTIONAL | object | Monetisation tier for this unit. Overrides root-level `payment` default. See §4.14. |

#### 4.1.1 Date Fields

The `validated` (unit) and `updated` (root) fields MUST contain an ISO 8601 date string
in `YYYY-MM-DD` format.

**YAML encoding:** Date values SHOULD be quoted in YAML to prevent YAML 1.1 parsers from
coercing them to native date objects:

```yaml
validated: "2026-02-25"   # correct — stays a string
validated: 2026-02-25     # may be parsed as a date object by some YAML libraries
```

Parsers SHOULD coerce native date objects to their ISO 8601 string representation when
reading `validated` and `updated` fields, and MUST NOT reject a manifest solely because
a date field was parsed as a native date type.

### 4.2 `id`

The `id` MUST be unique within the manifest. It MUST contain only lowercase ASCII letters
(`a-z`), digits (`0-9`), hyphens (`-`), and dots (`.`). It MUST NOT be empty.

```yaml
id: api-authentication-guide
```

### 4.3 `path`

The `path` MUST be a relative path from the manifest file's location to the content file.
Forward slashes MUST be used as path separators regardless of operating system.

```yaml
path: docs/authentication/guide.md
```

Parsers SHOULD warn if a declared `path` does not exist, but MUST NOT treat a missing file as
a parse error. The manifest may describe knowledge that has not yet been created.

### 4.3a `kind`

The `kind` field declares what type of artifact a knowledge unit represents. It provides a
machine-readable dispatch signal that tells agents how to interact with the unit: load and
embed (knowledge), parse as a structured definition (schema), invoke via protocol (service),
evaluate as a gate (policy), or run on demand (executable).

| Value | Meaning | Agent behaviour |
|-------|---------|-----------------|
| `knowledge` | Documentation, guides, explanations, prose | Load and embed in context (default) |
| `schema` | Machine-readable definitions: OpenAPI, AsyncAPI, gRPC proto, JSON Schema | Parse as structured definition |
| `service` | A running or callable endpoint: API, MCP server, webhook | Invoke via protocol |
| `policy` | Rules, constraints, compliance documents | Evaluate as authoritative gate |
| `executable` | Runnable artifacts: scripts, notebooks, workflow definitions | Invoke on demand |

If `kind` is omitted, parsers MUST treat the unit as `kind: knowledge`. This ensures full
backward compatibility with v0.2 manifests.

Unknown `kind` values MUST be silently ignored by parsers.

```yaml
kind: schema
```

### 4.4 `intent`

The `intent` MUST be a single sentence describing the question this unit answers or the task it
enables. It SHOULD be written in the form of a question or task description rather than a title.

```yaml
intent: "How do I authenticate API requests using OAuth 2.0?"
```

The intent is the primary signal for agent task routing. Implementations that do not generate
useful intents SHOULD omit the field rather than populate it with the file name or path.

### 4.4a `format`

The `format` field declares the content format of the file referenced by `path`. It enables
agents to make loading decisions before fetching the content — for example, skipping PDF
units when only Markdown processing is available, or prioritising OpenAPI specs for API
integration tasks.

| Value | Description |
|-------|-------------|
| `markdown` | Markdown document (default if omitted for `.md` files) |
| `pdf` | PDF document |
| `openapi` | OpenAPI / Swagger specification |
| `json-schema` | JSON Schema document |
| `jupyter` | Jupyter notebook (.ipynb) |
| `html` | HTML document |
| `asciidoc` | AsciiDoc document |
| `rst` | reStructuredText |
| `vtt` | WebVTT subtitle/transcript |
| `yaml` | Generic YAML (not OpenAPI or KCP) |
| `json` | Generic JSON |
| `csv` | Tabular data |
| `text` | Plain text |

If `format` is omitted, parsers MAY infer the format from the file extension but MUST NOT
treat inference failures as errors.

Unknown `format` values MUST be silently ignored by parsers.

```yaml
format: openapi
```

### 4.4b `content_type`

The `content_type` field provides a full MIME type for cases where `format` alone is
insufficient. When both `format` and `content_type` are present, `content_type` takes
precedence for format identification.

```yaml
content_type: "application/vnd.oai.openapi+yaml;version=3.1"
```

`content_type` is OPTIONAL. If present, it MUST be a valid MIME type string.

### 4.4c `language`

The `language` field declares the human language of the unit's content using a BCP 47
language tag ([RFC 5646](https://www.rfc-editor.org/rfc/rfc5646)).

```yaml
language: en
```

A root-level `language` field MAY be declared as a default for all units in the manifest.
Unit-level `language` values override the root default.

Common values: `en`, `en-GB`, `en-US`, `no`, `nb`, `nn`, `de`, `fr`, `es`, `ja`, `zh`.

If `language` is omitted at both root and unit level, the language is undeclared. Agents
SHOULD treat this as unknown rather than assuming a default language.

Unknown `language` values MUST be silently ignored by parsers.

### 4.5 `scope`

| Value | Meaning |
|-------|---------|
| `global` | Relevant to the entire project or system |
| `project` | Relevant to a specific project, service, or repository within a larger system |
| `module` | Relevant to a specific module, component, or subsystem |

### 4.6 `audience`

The `audience` field is a list of one or more values indicating who this unit is intended for.
Recognised values:

| Value | Intended reader |
|-------|----------------|
| `human` | Human readers (documentation, guides) |
| `agent` | AI agents (machine-navigable context) |
| `developer` | Software developers |
| `architect` | System architects |
| `operator` | Operations / DevOps / SRE |
| `devops` | Equivalent to `operator` |

A unit MAY have multiple audience values. A unit intended for both humans and agents would
declare `audience: [human, agent]`.

Unknown audience values MUST be silently ignored by parsers.

### 4.6a `license`

The `license` field declares what an agent is permitted to do with a knowledge unit's
content after loading it.

**Shorthand form** — an SPDX license identifier string:

```yaml
license: "Apache-2.0"
```

**Structured form** — an object with explicit fields:

```yaml
license:
  spdx: "CC-BY-4.0"
  url: "https://creativecommons.org/licenses/by/4.0/"
  attribution_required: true
```

| Subfield | Type | Description |
|----------|------|-------------|
| `spdx` | string | An [SPDX license identifier](https://spdx.org/licenses/). Use `LicenseRef-Proprietary` for custom or proprietary licenses. |
| `url` | string | URL to the full license text. |
| `attribution_required` | boolean | Whether the agent must cite the source when reproducing content. Default: `false`. |

A root-level `license` field MAY be declared as a default for all units in the manifest.
Unit-level `license` values override the root default.

If `license` is omitted, no machine-readable usage terms are declared. Agents SHOULD treat
this as unknown and apply conservative defaults.

Unknown subfields within `license` MUST be silently ignored. Parsers MUST NOT reject a
manifest for an unrecognised `spdx` value.

### 4.6b `update_frequency`

The `update_frequency` field is an advisory hint declaring how often the content of a unit
typically changes. It helps agents decide how long to cache a unit's content and when to
re-fetch.

| Value | Meaning |
|-------|---------|
| `hourly` | Changes multiple times per day |
| `daily` | Changes roughly once per day |
| `weekly` | Changes roughly once per week |
| `monthly` | Changes roughly once per month |
| `rarely` | Changes less than once per month |
| `never` | Content is immutable (e.g. versioned release notes) |

```yaml
update_frequency: weekly
```

`update_frequency` is OPTIONAL. Omitting it means no caching guidance is declared. Agents
apply their own defaults.

`update_frequency` complements the `validated` field: `validated` answers "when did a human
last confirm this was accurate?", while `update_frequency` answers "how often does this
content typically change?"

Unknown `update_frequency` values MUST be silently ignored by parsers.

### 4.6c `indexing`

The `indexing` field declares whether AI agents and crawlers may index, cache, train on, or
reproduce the content of a knowledge unit.

**Shorthand form** — a string keyword:

| Value | Meaning |
|-------|---------|
| `open` | All operations permitted: read, index, reproduce, train |
| `read-only` | Read permitted; no indexing, training, or reproduction |
| `no-train` | All operations except model training are permitted |
| `none` | No AI access whatsoever |

```yaml
indexing: no-train
```

**Structured form** — an object with explicit allow/deny lists:

```yaml
indexing:
  allow: [read, index, reproduce-in-response]
  deny: [train]
  attribution_required: true
```

| Permission | Meaning |
|------------|---------|
| `read` | Agent may load and use content for reasoning |
| `index` | Agent/crawler may add to a searchable index |
| `reproduce-in-response` | Agent may quote or reproduce in its output |
| `train` | Content may be used for model training |
| `cache-permanently` | Content may be cached beyond the current session |
| `share-externally` | Content may be sent to external systems/APIs |
| `summarise` | Agent may generate and share summaries |

A root-level `indexing` field MAY be declared as a default for all units in the manifest.
Unit-level `indexing` values override the root default.

If `indexing` is omitted, no machine-readable crawling permissions are declared. Agents
SHOULD apply conservative defaults.

Unknown permission values MUST be silently ignored.

### 4.7 `depends_on`

The `depends_on` field declares units that SHOULD be loaded and understood before this unit.
This allows agents to respect prerequisite ordering when building context.

```yaml
depends_on: [project-overview, authentication-concepts]
```

Values MUST be `id` strings of units declared in the same manifest. References to unknown IDs
SHOULD produce a validation warning and MUST be silently ignored at runtime.

**Circular dependencies:** Parsers MUST detect cycles in `depends_on` relationships and MUST
silently ignore the edge that would close the cycle. A graph with cycles does not indicate
invalid knowledge — the cycle may be semantically meaningful — but parsers cannot traverse it
without cycle detection. No error or warning is required.

### 4.8 `supersedes`

Declares that this unit replaces a previous unit. The referenced unit-id MAY be a unit that no
longer exists in the manifest (representing a deleted unit). Agents SHOULD prefer this unit over
the one it supersedes.

```yaml
supersedes: api-authentication-v1
```

### 4.9 `triggers`

Keywords or short phrases that indicate when this unit is relevant. Used by agents and tools for
task-based retrieval.

```yaml
triggers: [oauth2, authentication, bearer-token, jwt]
```

**Constraints:**
- Each trigger MUST NOT exceed 60 characters.
- A unit MUST NOT declare more than 20 triggers.
- Trigger values are case-insensitive for matching purposes.
- Parsers SHOULD truncate triggers exceeding 60 characters rather than rejecting the manifest.
- Parsers SHOULD silently ignore triggers beyond the 20th.

### 4.10 `hints`

The `hints` block contains advisory metadata about the size, cost, and loading characteristics
of a knowledge unit. All fields are optional. Agents that do not support `hints` MUST silently
ignore the block — it carries no normative weight and does not change whether a manifest is valid.

```yaml
hints:
  token_estimate: 42000
  token_estimate_method: measured
  size_bytes: 168000
  load_strategy: lazy
  priority: supplementary
  density: dense
  summary_available: true
  summary_unit: spec-summary
  partial_load_supported: false
```

#### Hint fields

| Field | Type | Description |
|-------|------|-------------|
| `token_estimate` | integer | Approximate token count for a typical LLM tokenizer. Advisory — actual count varies by model. |
| `token_estimate_method` | enum | How the estimate was produced: `measured` (actual tokenizer run) or `estimated` (rough heuristic). Default: `estimated`. |
| `size_bytes` | integer | Raw file size in bytes. Model-agnostic; useful for non-text assets (PDFs, images, audio). |
| `load_strategy` | enum | When to load this unit relative to manifest processing. See [Load strategy](#load-strategy) below. Default: `lazy`. |
| `priority` | enum | Importance when the agent must evict units due to context budget pressure. See [Priority](#priority) below. Default: `supplementary`. |
| `density` | enum | Information-to-token ratio of the content. See [Density](#density) below. Default: `standard`. |
| `summary_available` | boolean | A shorter summary of this unit exists in this manifest. When `true`, `summary_unit` SHOULD be declared. |
| `summary_unit` | string | Unit `id` of the shorter summary. Required when `summary_available: true`. |
| `summary_of` | string | Unit `id` that this unit summarises. Declared on the summary side of the relationship. |
| `partial_load_supported` | boolean | The content source supports partial or range requests (e.g. HTTP Range, PDF page ranges). Default: `false`. |
| `chunked` | boolean | This unit has been split into sequential chunks loadable independently. `chunk_count` SHOULD be declared alongside. |
| `chunk_count` | integer | Total number of chunks. Declared on the parent unit alongside `chunked: true`. |
| `chunk_of` | string | Unit `id` of the parent this chunk belongs to. |
| `chunk_index` | integer | 1-based position of this chunk within the parent sequence. |
| `total_chunks` | integer | Total chunks in the parent. Mirrors `chunk_count` on the parent for local access. |
| `chunk_topic` | string | Short phrase describing what this chunk covers. Helps agents select the right chunk without loading all of them. |

All hint fields are OPTIONAL. Parsers MUST silently ignore unknown hint fields. This ensures
forward compatibility as new hints are introduced in future spec versions.

#### Load strategy

`load_strategy` advises the agent on when to load a unit relative to manifest initialisation.

| Value | Meaning |
|-------|---------|
| `eager` | Load immediately when the manifest is processed. This unit is nearly always needed. |
| `lazy` | Load on demand when the agent determines the unit is relevant to the current task. Default. |
| `never` | Do not load proactively. Load only if explicitly requested by id. Appropriate for large archives, raw data files, or units where the agent should read a summary instead. |

#### Priority

`priority` advises the agent on which units to retain when context must be cleared or truncated.

| Value | Meaning |
|-------|---------|
| `critical` | Evict last. This unit contains essential facts the agent must retain to function correctly. |
| `supplementary` | Standard priority. Load and use normally; may be evicted if budget is tight. Default. |
| `reference` | Evict first. Reference material (API specs, full changelogs, raw data) used for spot lookups rather than sustained reasoning. |

An agent managing its own context window SHOULD evict `reference` units before `supplementary`
units, and `supplementary` before `critical`.

#### Density

`density` describes the information-to-token ratio of the content, helping agents decide whether
to compress or summarise before placing in context.

| Value | Meaning |
|-------|---------|
| `dense` | Nearly every sentence is load-bearing. Compression risks information loss. Full text is preferred. |
| `standard` | Normal prose. Some compression acceptable. Default. |
| `verbose` | High token count relative to information content (tutorials, narrative explanations). Summarisation before loading is likely worthwhile. |

#### Summary relationships

When a short summary of a large unit exists in the same manifest, both sides of the relationship
SHOULD be declared:

```yaml
units:
  - id: full-specification
    path: SPEC.md
    intent: "What are the normative rules for a knowledge.yaml manifest?"
    hints:
      token_estimate: 42000
      load_strategy: lazy
      summary_available: true
      summary_unit: spec-summary      # → points to the summary

  - id: spec-summary
    path: SPEC-tldr.md
    intent: "What are the key points of the spec in 500 words?"
    hints:
      token_estimate: 600
      load_strategy: eager
      priority: critical
      summary_of: full-specification  # ← points back to the full unit
```

`summary_unit` on the full unit and `summary_of` on the summary unit are complementary. A
validator SHOULD warn when `summary_available: true` has no matching `summary_unit`, or when
`summary_of` references a unit that does not declare `summary_available: true`.

#### Chunked units

Large documents may be split into sequential chunks that can be loaded independently. This allows
an agent to load only the relevant section of a large document:

```yaml
units:
  - id: api-reference
    path: api/reference.md
    intent: "What endpoints does the API expose?"
    hints:
      token_estimate: 62000
      load_strategy: never
      chunked: true
      chunk_count: 5

  - id: api-ref-auth
    path: api/reference-auth.md
    intent: "What are the authentication endpoints and token schemas?"
    hints:
      token_estimate: 9400
      load_strategy: lazy
      chunk_of: api-reference
      chunk_index: 1
      total_chunks: 5
      chunk_topic: "Authentication and token management"
```

`chunk_of` MUST reference a unit in the same manifest. A validator SHOULD warn when `chunk_of`
references a non-existent unit, or when `chunk_index` exceeds `total_chunks`.

#### Root-level `hints` block

The root manifest MAY contain a `hints` block with aggregate information about the entire
manifest. This allows an agent to assess total context cost before loading any unit.

```yaml
hints:
  total_token_estimate: 840000
  unit_count: 94
  recommended_entry_point: overview
  has_summaries: true
  has_chunks: false
```

| Field | Type | Description |
|-------|------|-------------|
| `total_token_estimate` | integer | Advisory sum of all unit `token_estimate` values. |
| `unit_count` | integer | Total number of units declared in this manifest. |
| `recommended_entry_point` | string | Unit `id` the publisher recommends loading first. Typically an overview, index, or getting-started unit. |
| `has_summaries` | boolean | At least one unit in this manifest has `summary_available: true`. |
| `has_chunks` | boolean | At least one unit in this manifest has `chunked: true`. |

`total_token_estimate` SHOULD be recomputed by tooling when unit estimates change. Publishers
SHOULD NOT maintain it by hand; a stale value is worse than an absent value.

### 4.11 `access` and `auth_scope`

The `access` field is a lightweight advisory signal indicating what kind of credential, if any,
is required to fetch the content of this unit.

| Value | Meaning |
|-------|---------|
| `public` | No credential required. Freely accessible. Default when `access` is omitted. |
| `authenticated` | Any valid credential for this knowledge source is sufficient. The root-level `auth` block (§3.3) describes how to acquire one. |
| `restricted` | A specific scope or role is required. Declare it in `auth_scope`. The root-level `auth` block (§3.3) describes how to authenticate. |

The `auth_scope` field is an optional companion to `access: restricted`. It is a free-form
string naming the scope, role, or group required to access the unit. Examples: `"ops-team"`,
`"read:internal"`, `"cn=data-analysts"`. Parsers treat it as an opaque advisory string — no
validation of the value itself is performed.

When both `auth_scope` and `auth.methods` (§3.3) are present, the scope value in `auth_scope`
SHOULD correspond to a scope or role supported by the declared auth method. For OAuth 2.1,
the `auth_scope` value typically matches one of the `scopes` declared in the `auth.methods`
entry. This correspondence is advisory; parsers MUST NOT validate it.

```yaml
units:
  - id: public-overview
    path: README.md
    intent: "What is this project?"
    scope: global
    audience: [human, agent]
    # access omitted = public

  - id: internal-runbook
    path: ops/runbook.md
    intent: "How do I handle a production incident?"
    scope: project
    audience: [operator, agent]
    access: authenticated

  - id: executive-report
    path: reports/exec-summary.md
    intent: "What are the key business metrics this quarter?"
    scope: module
    audience: [agent]
    access: restricted
    auth_scope: executive-team
```

`access` is an advisory declaration. It does not constitute an access control mechanism — a
manifest declaring `access: restricted` does not prevent an agent from loading the content if
no enforcement layer exists at the transport or storage level. See §14.1.

Unknown `access` values MUST be silently ignored by parsers.

`auth_scope` is OPTIONAL. Parsers MUST NOT reject a manifest because `auth_scope` is absent
on a unit with `access: restricted`. A validator SHOULD warn when `auth_scope` is present on
a unit whose `access` is not `restricted`, as the scope has no effect without the access gate.

### 4.12 `sensitivity`

The `sensitivity` field classifies the information content of a unit using standard information
security levels. It is an advisory signal to agents and orchestration layers about how carefully
this content should be handled.

| Value | Meaning |
|-------|---------|
| `public` | No restrictions. Freely shareable. |
| `internal` | For internal use only. Not for external parties. |
| `confidential` | Restricted to a need-to-know subset. |
| `restricted` | Highest sensitivity. Strict handling required. |

These levels align with common information classification frameworks (ISO 27001, many national
standards). The gradient `public → internal → confidential → restricted` is intentional and
ordered.

```yaml
units:
  - id: security-runbook
    path: ops/security-runbook.md
    intent: "How do we respond to a security incident?"
    scope: project
    audience: [operator, agent]
    sensitivity: confidential
    access: restricted
```

`sensitivity` is omitted by default. When omitted, the sensitivity of the unit is undeclared —
agents SHOULD treat this as unknown rather than assuming `public`.

Unknown `sensitivity` values MUST be silently ignored by parsers.

### 4.13 `deprecated`

The `deprecated` field signals that a unit is still present in the manifest but should not be
used for new development. It is equivalent to a deprecation annotation in code.

```yaml
units:
  - id: old-api-guide
    path: docs/api-v1.md
    intent: "How do I use the v1 API?"
    scope: module
    audience: [developer, agent]
    deprecated: true
```

When `deprecated: true`:

- Agents SHOULD prefer non-deprecated alternatives when they exist.
- If a `supersedes` field on another unit references this unit, that unit is the preferred
  replacement.
- A validator SHOULD warn when `deprecated: true` is declared but no other unit declares
  `supersedes: <this-unit-id>`.

`deprecated` is OPTIONAL. Default: `false`. When omitted or `false`, no deprecation is implied.

### 4.14 `payment`

The `payment` field declares the monetisation model for this unit. It is an advisory signal
that allows agents to assess whether access will incur a cost before attempting to load content.

In this version, only the `default_tier` sub-field is defined. Additional sub-fields (payment
methods, x402 micropayment details, rate limits) are specified in RFC-0005 and may be promoted
in a future version.

**Unit-level form:**

```yaml
units:
  - id: premium-research
    path: reports/market-analysis.md
    intent: "What does the market analysis show?"
    scope: module
    audience: [agent]
    payment:
      default_tier: metered
```

**Root-level default (overridable per unit):**

```yaml
payment:
  default_tier: free   # applies to all units unless overridden
```

#### `payment` sub-fields

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `default_tier` | OPTIONAL | string | Monetisation tier. One of: `free`, `metered`, `subscription`. Default: `free`. |

| Tier | Meaning |
|------|---------|
| `free` | No cost to access. Default when `payment` is omitted. |
| `metered` | Per-request or per-token billing. Agent should check its budget before loading. |
| `subscription` | Access requires an active subscription plan. |

`payment` is OPTIONAL at both root and unit level. When omitted entirely, all units are assumed
to be `free`.

Unit-level `payment` overrides the root-level `payment` default for that unit.

Unknown `payment` sub-fields MUST be silently ignored.

---

### 4.15 `rate_limits`

The `rate_limits` block declares the maximum request rate an agent should observe when
accessing this manifest's knowledge units. It is advisory metadata that allows agents to
self-throttle without waiting for a `429 Too Many Requests` response.

**Root-level (applies to all units unless overridden):**

```yaml
rate_limits:
  default:
    requests_per_minute: 60
    requests_per_day: 1000
```

**Unit-level override:**

```yaml
units:
  - id: high-volume-index
    path: index/full.md
    rate_limits:
      default:
        requests_per_minute: 10
        requests_per_day: 100
```

#### `rate_limits` field reference

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `rate_limits` | OPTIONAL | object | Rate limit declarations. Root-level applies as default; unit-level overrides root. |
| `rate_limits.default` | OPTIONAL | object | Default rate limit applied when no tier-specific limit is declared. |
| `rate_limits.default.requests_per_minute` | OPTIONAL | integer | Maximum requests per 60-second rolling window. Omit for no limit. |
| `rate_limits.default.requests_per_day` | OPTIONAL | integer | Maximum requests per calendar day (UTC). Omit for no limit. |

Both `requests_per_minute` and `requests_per_day` are OPTIONAL. Declaring one without the
other is valid. When both are declared, agents MUST respect the more restrictive limit in any
given window.

`rate_limits` is OPTIONAL at both root and unit level. Omitting it entirely means no rate
limit is declared — agents SHOULD NOT infer a specific limit.

Unknown sub-fields within `rate_limits` MUST be silently ignored. Additional rate limit
sub-fields (per-tier, token-based, header mapping) are specified in RFC-0005 and may be
promoted in a future version.

---

## 5. Relationships

The optional `relationships` section declares explicit directional relationships between units.
This is separate from the inline `depends_on` field and supports richer graph semantics.

```yaml
relationships:
  - from: <unit-id>
    to: <unit-id>
    type: enables | context | supersedes | contradicts | depends_on | governs
```

| Type | Meaning | Agent navigation implication |
|------|---------|------------------------------|
| `enables` | `from` enables or unlocks `to` | Load `from` first when user is not yet ready for `to` |
| `context` | `from` provides useful background for `to` (advisory) | Load `from` for deeper understanding; `to` works alone |
| `supersedes` | `from` replaces `to` | Prefer `from`; skip `to` unless historical content requested |
| `contradicts` | `from` conflicts with `to` | Surface both as known conflict; do not treat as simultaneously authoritative |
| `depends_on` | `from` depends on `to` | Load `to` before `from`; `from` may be incomplete without `to` |
| `governs` | `from` declares authoritative policy/standards that `to` must comply with | When compliance/standards questions arise about `to`, load `from` as authoritative source; `from` takes precedence on conflict |

The relationship type vocabulary is shared between `relationships` (§5) and
`external_relationships` (§3.6). All types are valid in both sections.

Both `from` and `to` MUST be `id` values of units declared in the same manifest. Relationships
referencing unknown IDs SHOULD produce a validation warning and MUST be silently ignored.

Unknown relationship types MUST be silently ignored.

---

## 6. Versioning

### 6.1 Spec Version (`kcp_version`)

`kcp_version` identifies which version of this specification the manifest conforms to. Current
valid value: `"0.9"`. The values `"0.1"` through `"0.8"` refer to prior drafts (January–March
2026); parsers SHOULD treat these manifests as conformant with this version, as v0.9 is a
strict superset of v0.8 (new fields only, no removals or breaking changes). Parsers
encountering an unknown `kcp_version` SHOULD process the manifest using the closest known
version and SHOULD emit a warning.

### 6.2 Manifest Version (`version`)

The `version` field is the manifest author's own version of the knowledge index, independent of
the spec version. It follows [Semantic Versioning 2.0.0](https://semver.org/). Authors SHOULD
increment this value when units are added, removed, or materially changed.

---

## 7. Validation

A conformant parser MUST accept any manifest that satisfies the REQUIRED fields in §3 and §4.
The following conditions SHOULD produce warnings but MUST NOT cause the parser to reject the
manifest:

- A `path` value that does not resolve to an existing file
- A `depends_on` or `relationships` reference to an unknown `id`
- A `triggers` entry exceeding 60 characters (truncate and warn)
- More than 20 `triggers` entries on a single unit (ignore excess and warn)
- An unknown `audience` value
- An unknown `kind` value
- An unknown `format` value
- An unknown `update_frequency` value
- An unknown relationship `type`
- A `kcp_version` the parser does not recognise
- Duplicate `id` values (parsers SHOULD use the first occurrence)
- `auth_scope` present on a unit whose `access` is not `restricted`
- `auth.methods` containing no recognised `type` values

The following conditions MUST cause the parser to reject the manifest:

- The file is not valid YAML
- The file is not UTF-8 encoded
- The `project` field is absent or empty
- The `units` field is absent or empty
- A unit is missing its `id`, `path`, `intent`, `scope`, or `audience` field

---

### 7.1 JSON Schema

A JSON Schema (draft-07) for `knowledge.yaml` is available at
[`schema/knowledge-schema.json`](./schema/knowledge-schema.json). It covers all fields defined in
this specification: root fields (`kcp_version`, `project`, `version`, `updated`, `language`,
`license`, `indexing`, `hints`, `trust`, `auth`, `delegation`, `compliance`, `payment`,
`rate_limits`, `manifests`, `external_relationships`), unit fields (`id`, `path`, `kind`,
`intent`, `format`, `content_type`, `language`, `scope`, `audience`, `license`, `validated`,
`update_frequency`, `indexing`, `depends_on`, `supersedes`, `triggers`, `hints`, `access`,
`auth_scope`, `sensitivity`, `deprecated`, `delegation`, `compliance`, `payment`, `rate_limits`,
`external_depends_on`), and relationship fields (`from`, `to`, `type`).

The schema enforces required fields, value constraints (e.g. `id` pattern, `kind` enum,
`format` enum, trigger `maxLength` and `maxItems`), and structural rules. It can be used with
any JSON Schema validator to check manifest correctness before parsing, and by editors for
autocompletion and inline validation.

---

## 8. Conformance Levels

Implementations are encouraged to adopt KCP incrementally. Three levels are defined:

**Level 1 — Minimal**
The manifest contains `project`, `units`, and for each unit: `id`, `path`, `intent`, `scope`,
and `audience`. A Level 1 manifest answers the question: "what knowledge exists, what does
each piece answer, and who is it for?" Parsers SHOULD supply default values when `scope` or
`audience` are absent (`scope` defaults to `global`; `audience` defaults to an empty list).

**Level 2 — Structured**
Extends Level 1 with `validated`, `depends_on`, `kind`, `format`, `language`, the core
`hints` fields (`token_estimate`, `load_strategy`, `summary_available`, `summary_unit`,
`summary_of`), the access and classification fields (`access`, `auth_scope`, `sensitivity`,
`deprecated`, `payment`), and the root-level `trust.provenance` block. A Level 2 manifest
supports freshness-aware retrieval, dependency-ordered loading, artifact type classification,
content format awareness, multilingual navigation, basic context-budget planning (agents know
unit sizes and can prefer summaries over full documents), access-tier routing (agents skip
units they cannot or should not load), scope-based access qualification, and basic publisher
attribution.

**Level 3 — Full**
Extends Level 2 with `triggers`, `supersedes`, `license`, `update_frequency`, `indexing`,
advanced `hints` (`priority`, `density`, chunking fields), root-level `hints`, a
`relationships` section, the root-level `auth` block (§3.3) with authentication method
descriptions, the `trust.audit` sub-block (§3.2) with access logging and trace context
requirements, the `manifests` block (§3.6) with federation declarations, `external_depends_on`
(unit-level cross-manifest dependencies), `external_relationships` (root-level cross-manifest
relationship declarations), and `local_mirror` for air-gapped federation. A Level 3 manifest
supports task-based routing, knowledge graph navigation, drift detection, usage rights
declaration, cache management, AI crawling permissions, context eviction ordering,
large-document chunked access, authentication discovery, auditable knowledge access, and
federated multi-manifest knowledge graphs.

All three levels are valid KCP. A tool MUST NOT reject a manifest for being below the
level it was designed for — graceful degradation is required.

---

## 9. Relationship to llms.txt

KCP and llms.txt address adjacent problems and are designed to coexist.

`llms.txt` answers the question: "what does this site contain?" It is a flat, human-readable
index optimised for simple cases.

KCP answers the question: "how is this knowledge structured, how fresh is it, and how should
an agent navigate it?" It is not a replacement for `llms.txt`.

A project MAY maintain both files. When both are present, a KCP-aware agent SHOULD prefer
`knowledge.yaml` for navigation and treat `llms.txt` as a fallback for tools that do not
support KCP.

---

## 10. Relationship to MCP

The Model Context Protocol (MCP) defines how AI agents connect to tools and data sources.
KCP defines how the knowledge those tools serve is structured.

The two protocols are complementary:

- MCP provides the transport and tool invocation layer
- KCP provides the knowledge structure and metadata layer

A knowledge server MAY expose KCP-structured content via MCP. The knowledge units declared in
`knowledge.yaml` correspond naturally to the resources a KCP-aware MCP server would expose.

---

## 11. Relationship to HATEOAS

HATEOAS (Hypermedia As The Engine Of Application State) is a REST architectural constraint in
which server responses include typed links describing the transitions available to the client from
its current state. KCP shares the same foundational insight — that typed, directional
relationships between resources are necessary for navigation — but applies it in a different
domain with a different execution model.

**Shared insight:** Both reject the "flat list of resources" model. A consumer navigating an
information space needs to know not just what exists, but how resources relate, what transitions
are valid, and what each resource is for. KCP's `depends_on`, `supersedes`, and `relationships`
fields are the same idea as HATEOAS link relations: typed directed edges that tell the consumer
how to move through the space.

**Key difference — static vs dynamic:** HATEOAS links are generated per-response, reflecting the
current state of the resource. A HATEOAS server may offer a `cancel` link on a pending order and
omit it on a fulfilled one. KCP is a committed file. The manifest declares topology at authoring
time; it does not adapt to the agent's current task or the state of the knowledge base at query
time. This is a deliberate design choice that enables KCP to work without a server, but it means
KCP cannot express conditional navigation ("this unit is relevant only if you have already loaded
unit X").

**Where KCP goes beyond HATEOAS:** HATEOAS link relations express what action a client can take
next. KCP's `intent` field expresses what question a unit answers — a different kind of metadata
that enables semantic routing without loading content. KCP's `validated` field distinguishes human
confirmation of accuracy from file modification time, which has no equivalent in HTTP caching
semantics. KCP's `audience` field and `triggers` list address relevance filtering concerns that
REST never needed because its consumers are not context-window-constrained agents navigating
heterogeneous corpora.

**Where HATEOAS goes beyond KCP:** Runtime, state-dependent navigation. A HATEOAS server adjusts
its link set based on live resource state. KCP cannot model this. If future implementations add a
KCP-aware query server (such as a KCP-over-MCP bridge), this gap narrows for the dynamic case.
For the static file format, it is a fundamental constraint.

Implementations that are familiar with HATEOAS may find the `relationships` section the most
natural entry point into KCP. The conceptual vocabulary — typed links, link relations, navigation
graph — transfers directly. The intent, freshness, and audience fields represent concerns that
arise specifically when the consumer is an AI agent rather than an API client.

---

## 12. Relationship to A2A

Google's [Agent-to-Agent (A2A) protocol](https://google.github.io/A2A/) defines how AI agents
discover and invoke each other. An A2A Agent Card, published at `/.well-known/agent.json`,
describes an agent's identity, skills, capabilities, and how to authenticate when calling it.

KCP and A2A address adjacent but distinct concerns and are designed to compose:

- **A2A** answers: *"Who is this agent, what can it do, and how do I call it?"*
- **KCP** answers: *"What knowledge does this agent have access to, and what does each piece require to read?"*

Neither protocol can express what the other expresses.

### The two-layer model

| Concern | A2A Agent Card | KCP Manifest |
|---------|---------------|--------------|
| **Describes** | The agent (service) | The knowledge (content units) |
| **Published at** | `/.well-known/agent.json` | `knowledge.yaml` (project root) |
| **Format** | JSON | YAML |
| **Auth granularity** | Per-agent (transport layer) | Per-knowledge-unit (access layer) |
| **Sensitivity labels** | Not in scope | `public`, `internal`, `confidential`, `restricted` |
| **Delegation controls** | Not in scope | `max_depth`, capability attenuation, audit chain (§3.4) |
| **Human-in-the-loop** | Not in scope | Per-unit, with approval mechanism (§3.4) |
| **Compliance metadata** | Not in scope | `regulations`, `data_residency`, `restrictions` (§3.5) |
| **Discovery** | Agent skills, I/O modes, capabilities | Knowledge units, intents, triggers, freshness |

### Authentication — two layers, not competing

Both protocols reference OAuth2. This is where a superficial reading might suggest redundancy.
It is not.

**A2A auth** operates at the transport layer. It defines how a calling agent authenticates
*to* the target agent — the question is: "Are you allowed to talk to this agent at all?"

**KCP auth** (§3.3) operates at the knowledge-access layer. It defines what credentials are
needed to access a *specific knowledge unit* within the agent. The question is: "Now that you
are connected, are you allowed to read this particular file?"

An agent that holds a valid A2A transport token may still be denied access to a specific KCP
unit because it lacks the required `auth_scope`, has exceeded the unit's `max_depth` delegation
limit, or because the unit requires human-in-the-loop approval before an agent may read it.

### How they compose

An A2A Agent Card MAY reference a KCP manifest using the `knowledgeManifest` field (a proposed
convention — not part of the A2A specification):

```json
{
  "name": "Research Agent",
  "url": "https://research.example.com/agent",
  "knowledgeManifest": "/.well-known/kcp.json"
}
```

This enables a calling agent to read both documents before deciding how to interact:

1. **A2A Card** — discover endpoint, authenticate (transport), identify skills
2. **KCP manifest** — inspect knowledge units, evaluate per-unit access requirements,
   determine which units to load and which require additional credentials or human approval

A2A without KCP gives you a well-described front door with no access control inside. KCP
without A2A gives you fine-grained knowledge access control with no standard way for agents to
find or call the agent hosting it. Together, they form a complete two-layer stack.

### Working example

[`examples/a2a-agent-card/`](./examples/a2a-agent-card/) contains a complete worked example:
an A2A Agent Card and KCP manifest for the same clinical research agent, with a runnable Java
simulator (`examples/a2a-agent-card/simulator/`) that executes all four composition phases
and verifies each access decision with 30 tests.

---

## 13. Extension Fields

Implementations MAY add custom fields to the root manifest or to individual units. Custom fields
SHOULD use a namespaced prefix to avoid collisions with future spec fields (e.g.
`x-myorg-priority: high`).

Parsers MUST silently ignore fields they do not recognise, including extension fields from other
implementations. This is required for forward compatibility.

---

## 14. Security Considerations


**Path traversal:** Parsers MUST NOT resolve `path` values that traverse outside the manifest's
root directory (i.e. paths containing `..` that escape the root). Such paths SHOULD be rejected
with an error.

**Denial of service:** Parsers operating in untrusted environments SHOULD impose limits on
manifest size, unit count, and string field lengths to guard against resource exhaustion.

**Trust:** A `knowledge.yaml` is as trustworthy as its source. Agents consuming KCP manifests
from untrusted sources SHOULD treat the content as untrusted input.

### 14.1 Trust Model

KCP is a declarative format. A manifest describes properties of knowledge units — their
intended audience, freshness, access requirements, compliance scope, and dependencies. It does
not enforce any of these properties. Enforcement is the responsibility of the systems and agents
that consume the manifest.

**All KCP metadata is advisory.** The presence of a field in a manifest is a declaration by the
publisher, not a guarantee of correctness or a technical control. Agents and tools MUST NOT
treat KCP metadata as a substitute for independent verification where verification is required.

The following specific cases apply:

- **Freshness (`validated`):** A `validated` date declares when a human last confirmed the
  content. It does not prove the content is accurate at the time of consumption. Agents that
  require freshness guarantees MUST verify content independently.

- **Compliance scope (e.g. `regulations`, `data_residency`):** A manifest declaring compliance
  with a regulation (e.g. GDPR, HIPAA) asserts the publisher's stated intent, not verified
  compliance status. Agents and operators MUST NOT rely on compliance metadata as a legal basis
  for processing decisions. Compliance verification remains the responsibility of the data
  controller.

- **Access requirements (e.g. `access`, `auth`):** Auth metadata declares the publisher's
  intended access controls. It does not constitute an access control mechanism. A manifest
  declaring `access: restricted` does not prevent an agent from loading the referenced content
  if no enforcement layer exists at the transport or storage level.

- **Processing restrictions (e.g. `no-external-llm`, `no-training`):** Restrictions declared
  in a manifest are signals to the agent's orchestration layer. They MUST be evaluated before
  content is loaded into an agent context. An orchestration layer that loads content and then
  checks restrictions has already violated them.

- **Publisher identity:** Free-text publisher fields (e.g. `publisher: "Example Corp"`) carry
  no trust value. Only cryptographically verified identifiers (e.g. a DID resolved and verified
  at consumption time) provide publisher identity assurance.

**Agents SHOULD surface trust limitations to operators** when acting on metadata that has
security or legal implications. An agent that silently treats advisory compliance metadata as
enforced is creating hidden liability for its operator.

### 14.2 YAML Safety

Parsers MUST use a safe YAML constructor that disables arbitrary type instantiation. YAML
documents containing type tags that instantiate non-primitive types (e.g.
`!!javax.script.ScriptEngineManager` in Java) MUST be rejected.

Parsers MUST NOT use YAML loaders that execute code embedded in the document. This requirement
applies to all YAML content, including content fetched from remote sources.

### 14.3 Remote Content

Parsers and agents that fetch remote manifests (e.g. via federation or external references)
MUST apply the following constraints:

- Remote manifest URLs MUST use HTTPS. Plain HTTP URLs MUST be rejected.
- Parsers MUST NOT resolve URLs that target private address ranges (RFC 1918: 10.0.0.0/8,
  172.16.0.0/12, 192.168.0.0/16), link-local addresses (169.254.0.0/16), or loopback
  addresses (127.0.0.0/8, ::1). This check MUST be performed after DNS resolution to
  guard against DNS rebinding attacks.
- Parsers MUST detect and halt on cycles in remote manifest references. A visited-set of
  resolved manifest URLs MUST be maintained across the fetch chain. A manifest URL that
  appears in its own transitive fetch chain MUST be silently ignored.
- Parsers SHOULD enforce a maximum remote fetch depth. The RECOMMENDED default is 5.
- The YAML safety requirements of §14.2 apply to all remotely fetched manifests.

**Federation-specific constraints (§3.6):**

- Parsers MUST enforce a maximum of 50 unique manifests per federation resolution session
  (RECOMMENDED default). Exceeding this limit SHOULD produce a warning.
- Remote manifests larger than 1 MB SHOULD be rejected with a warning.
- Remote manifests containing more than 10,000 units SHOULD be rejected with a warning.
- Fetch timeout: RECOMMENDED default of 10 seconds per remote manifest.
- Trust does NOT propagate transitively across federation boundaries. A manifest is
  authoritative only over the sub-manifests it directly declares. Trust escalation
  through transitive chains MUST NOT be assumed.

---

## Appendix A: Minimal Example

```yaml
kcp_version: "0.9"
project: my-project
version: 1.0.0
units:
  - id: overview
    path: README.md
    intent: "What is this project and how do I get started?"
    scope: global
    audience: [human, agent]
```

---

## Appendix B: Full Example

```yaml
kcp_version: "0.9"
project: wiki.example.org
version: 2.3.0
updated: "2026-03-07"
language: en
license: "Apache-2.0"
indexing: open
hints:
  total_token_estimate: 84000
  unit_count: 9
  recommended_entry_point: about
  has_summaries: true
trust:
  provenance:
    publisher: "Example Corp"
    publisher_url: "https://wiki.example.org"
    contact: "docs@example.org"
  audit:
    agent_must_log: true
    require_trace_context: false
auth:
  methods:
    - type: oauth2
      issuer: "https://auth.example.org"
      scopes: ["read:docs"]
    - type: none
payment:
  default_tier: free

units:
  - id: about
    path: about.md
    intent: "Who maintains this project and what is it for?"
    scope: global
    audience: [human, agent]
    validated: "2026-02-24"
    update_frequency: monthly
    hints:
      token_estimate: 800
      load_strategy: eager
      priority: critical

  - id: architecture-overview
    path: architecture/overview.md
    intent: "What is the high-level architecture and which components exist?"
    format: markdown
    scope: global
    audience: [developer, architect, agent]
    validated: "2026-01-15"
    update_frequency: monthly
    depends_on: [about]
    triggers: [architecture, components, system-design, overview]
    hints:
      token_estimate: 18000
      load_strategy: lazy
      priority: supplementary
      density: dense
      summary_available: true
      summary_unit: architecture-tldr

  - id: architecture-tldr
    path: architecture/overview-tldr.md
    intent: "What is the high-level architecture in 400 words?"
    format: markdown
    scope: global
    audience: [developer, architect, agent]
    depends_on: [about]
    hints:
      token_estimate: 500
      load_strategy: eager
      priority: critical
      summary_of: architecture-overview

  - id: api-spec
    path: api/openapi.yaml
    kind: schema
    intent: "What endpoints does the API expose and what do they accept?"
    format: openapi
    content_type: "application/vnd.oai.openapi+yaml;version=3.1"
    scope: module
    audience: [developer, agent]
    validated: "2026-02-25"
    update_frequency: weekly
    depends_on: [architecture-overview]
    triggers: [api, endpoints, openapi, rest]
    hints:
      token_estimate: 12000
      load_strategy: lazy
      priority: reference
      density: dense

  - id: deployment-guide
    path: ops/deployment.md
    intent: "How do I deploy version 3.x to production?"
    scope: project
    audience: [operator, developer, agent]
    validated: "2026-02-20"
    update_frequency: rarely
    depends_on: [architecture-overview]
    supersedes: deployment-guide-v2
    triggers: [deployment, production, release, kubernetes, docker]
    hints:
      token_estimate: 8000
      load_strategy: lazy
      priority: supplementary

  - id: authentication-api
    path: api/authentication.md
    intent: "How do I authenticate API requests using OAuth 2.0?"
    scope: module
    audience: [developer, agent]
    validated: "2026-02-18"
    depends_on: [architecture-overview]
    triggers: [oauth2, authentication, bearer-token, jwt, api-security]
    hints:
      token_estimate: 4000
      load_strategy: lazy

  - id: pre-commit-gate
    kind: policy
    path: .husky/pre-commit
    intent: "What checks run automatically before every commit?"
    format: text
    scope: project
    audience: [developer, agent]
    validated: "2026-02-10"
    access: restricted
    auth_scope: dev-team
    sensitivity: internal
    indexing: read-only
    hints:
      token_estimate: 200
      load_strategy: lazy

  - id: methodology-no
    path: docs/methodology-no.md
    intent: "Hvilken utviklingsmetodologi brukes?"
    language: "no"
    scope: global
    audience: [human]
    validated: "2026-01-15"
    license:
      spdx: "CC-BY-4.0"
      url: "https://creativecommons.org/licenses/by/4.0/"
      attribution_required: true
    hints:
      token_estimate: 6000
      load_strategy: lazy

  - id: deployment-guide-v2
    path: archive/deployment-v2.md
    intent: "Legacy deployment procedure for version 2.x (superseded)."
    scope: project
    audience: [agent]
    validated: "2025-09-01"
    deprecated: true
    hints:
      load_strategy: never
      priority: reference

relationships:
  - from: architecture-overview
    to: deployment-guide
    type: enables
  - from: architecture-overview
    to: authentication-api
    type: enables
  - from: about
    to: architecture-overview
    type: context
  - from: deployment-guide
    to: deployment-guide-v2
    type: supersedes
```

---

## Appendix C: llms.txt Integration

To declare a KCP manifest that is not at the root, add a `knowledge:` line to the `llms.txt`
header:

```
# My Project

> A project that does useful things.
> knowledge: /docs/knowledge.yaml

## Docs

- /docs/overview.md: Project overview
```

---

## Appendix D: Reference Implementations

The following open-source projects implement KCP concepts and serve as reference implementations.

### Synthesis

[github.com/exoreaction/synthesis](https://github.com/exoreaction/synthesis)

A KCP-native knowledge infrastructure server. Synthesis indexes workspaces (code, documentation,
configuration, PDFs) and exposes them via MCP with sub-second retrieval. It produces a
`knowledge.yaml` from any indexed workspace via `synthesis export --format kcp`.

### kcp-commands

[github.com/cantara/kcp-commands](https://github.com/cantara/kcp-commands)

A Claude Code hook that applies KCP at the Bash tool boundary. Each manifest is a
`knowledge.yaml`-compatible description of a CLI command. Three phases:

- **Phase A** (PreToolUse): injects concise syntax context before execution — eliminates `--help` calls
- **Phase B** (PostToolUse, via daemon `/filter/{key}`): strips noise patterns and truncates large outputs before they reach the model's context window
- **Phase C** (v0.9.0, EventLogger): appends a JSON event to `~/.kcp/events.jsonl` on every Bash hook call — `{"ts":"...","session_id":"...","project_dir":"...","tool":"Bash","command":"...","manifest_key":"..."}` — consumed by kcp-memory for tool-level episodic memory

Ships with 283 bundled manifests covering Git, Linux/macOS, Docker, Kubernetes, cloud CLIs,
build tools, database clients, and more. Daemon on `localhost:7734` (virtual threads, ~12ms
warm latency). Falls back to Node.js CLI if daemon not running.

Measured impact: **67,352 tokens saved per session — 33.7% of a 200K context window recovered.**

### opencode-kcp-plugin

[npmjs.com/package/opencode-kcp-plugin](https://www.npmjs.com/package/opencode-kcp-plugin) ·
[source](https://github.com/Cantara/knowledge-context-protocol/tree/main/plugins/opencode)

A plugin for [OpenCode](https://github.com/anomalyco/opencode) that reduces explore-agent tool
calls by 73–80% using a project's `knowledge.yaml` manifest. Uses two hooks:
`experimental.chat.system.transform` to inject the full knowledge map into every session's system
prompt, and `tool.execute.after` to annotate glob/grep results with KCP intent strings.

Install: `npm install opencode-kcp-plugin`. Configure: `"plugin": ["opencode-kcp-plugin"]` in
`opencode.json`. Zero overhead when no `knowledge.yaml` is present.

### kcp-memory

[github.com/cantara/kcp-memory](https://github.com/Cantara/kcp-memory)

The episodic memory layer for Claude Code. Indexes `~/.claude/projects/**/*.jsonl` session
transcripts and `~/.kcp/events.jsonl` tool-call events (written by kcp-commands Phase C) into
a local SQLite+FTS5 database, making past sessions and individual tool invocations searchable
in milliseconds.

Three-layer model this completes:

| Layer | What it holds | Provided by |
|-------|--------------|-------------|
| **Working** | Current context window | Claude Code |
| **Episodic** | What happened in past sessions | kcp-memory |
| **Semantic** | What the workspace means | Synthesis |

Available as a CLI, HTTP daemon (`localhost:7735`), and MCP server (6 tools):

| MCP Tool | What it answers |
|----------|----------------|
| `kcp_memory_search` | FTS5 search over session transcripts |
| `kcp_memory_events_search` | FTS5 search over tool-call events |
| `kcp_memory_list` | Recent sessions, optionally by project |
| `kcp_memory_stats` | Aggregate statistics |
| `kcp_memory_session_detail` | Full content of a specific session |
| `kcp_memory_project_context` | Auto-detect project from `PWD`, return last 5 sessions + 20 events |

`kcp_memory_project_context` is designed for session-start invocation: it reads the current
working directory from the process environment and surfaces recent history with no query required
— closing the blank-slate problem structurally.

Install: `curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-memory/main/bin/install.sh | bash`

---

*Knowledge Context Protocol — proposed by [eXOReaction AS](https://www.exoreaction.com), Oslo, Norway.*
*Spec repository: [github.com/cantara/knowledge-context-protocol](https://github.com/cantara/knowledge-context-protocol)*
