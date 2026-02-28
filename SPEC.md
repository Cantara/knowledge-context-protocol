# Knowledge Context Protocol (KCP) Specification

**Version:** 0.1
**Status:** Draft
**Date:** 2026-02-25
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
relationships are out of scope for this version of the specification.

---

## 2. File Format

KCP manifests MUST be valid YAML 1.2. The file MUST be UTF-8 encoded without a BOM.

Parsers MUST silently ignore fields they do not recognise. This ensures forward compatibility:
a manifest valid for a future version of the spec remains parseable by implementations of this
version.

---

## 3. Root Manifest Structure

```yaml
kcp_version: "0.1"          # RECOMMENDED
project: <string>            # REQUIRED
version: <semver string>     # RECOMMENDED
updated: <ISO date>          # RECOMMENDED

units:                       # REQUIRED; list of knowledge units
  - ...

relationships:               # OPTIONAL; list of cross-unit relationship declarations
  - ...
```

### 3.1 Root Fields

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `kcp_version` | RECOMMENDED | string | Version of this specification. MUST be `"0.1"` for conformance with this document. |
| `project` | REQUIRED | string | Human-readable name of the project or documentation site. |
| `version` | RECOMMENDED | string | Semver version of this manifest. Increment when units are added or removed. |
| `updated` | RECOMMENDED | string | ISO 8601 date (`YYYY-MM-DD`) when this manifest was last modified. |
| `units` | REQUIRED | list | Ordered list of knowledge unit declarations. MUST contain at least one unit. |
| `relationships` | OPTIONAL | list | Explicit cross-unit relationship declarations. See §5. |

---

## 4. Knowledge Units

Each entry in `units` describes a self-contained piece of knowledge.

### 4.1 Unit Fields

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `id` | REQUIRED | string | Unique identifier within this manifest. See §4.2. |
| `path` | REQUIRED | string | Relative path to the content file. See §4.3. |
| `intent` | REQUIRED | string | One sentence: what question does this unit answer? See §4.4. |
| `scope` | REQUIRED | string | Breadth of applicability. One of: `global`, `project`, `module`. |
| `audience` | REQUIRED | list of strings | Who this unit is for. See §4.6. |
| `validated` | RECOMMENDED | string | ISO 8601 date when a human last confirmed the content was accurate. |
| `depends_on` | OPTIONAL | list of strings | IDs of units that SHOULD be loaded before this one. See §4.7. |
| `supersedes` | OPTIONAL | string | ID of the unit this replaces. See §4.8. |
| `triggers` | OPTIONAL | list of strings | Keywords or task contexts that make this unit relevant. See §4.9. |

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

### 4.4 `intent`

The `intent` MUST be a single sentence describing the question this unit answers or the task it
enables. It SHOULD be written in the form of a question or task description rather than a title.

```yaml
intent: "How do I authenticate API requests using OAuth 2.0?"
```

The intent is the primary signal for agent task routing. Implementations that do not generate
useful intents SHOULD omit the field rather than populate it with the file name or path.

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

---

## 5. Relationships

The optional `relationships` section declares explicit directional relationships between units.
This is separate from the inline `depends_on` field and supports richer graph semantics.

```yaml
relationships:
  - from: <unit-id>
    to: <unit-id>
    type: enables | context | supersedes | contradicts
```

| Type | Meaning |
|------|---------|
| `enables` | The `from` unit enables or unlocks the `to` unit |
| `context` | The `from` unit provides useful context for the `to` unit |
| `supersedes` | The `from` unit replaces the `to` unit (equivalent to `unit.supersedes`) |
| `contradicts` | The `from` unit contains information that conflicts with the `to` unit |

Both `from` and `to` MUST be `id` values of units declared in the same manifest. Relationships
referencing unknown IDs SHOULD produce a validation warning and MUST be silently ignored.

Unknown relationship types MUST be silently ignored.

---

## 6. Versioning

### 6.1 Spec Version (`kcp_version`)

`kcp_version` identifies which version of this specification the manifest conforms to. Current
valid value: `"0.1"`. Parsers encountering an unknown `kcp_version` SHOULD process the manifest
using the closest known version and SHOULD emit a warning.

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
- An unknown relationship `type`
- A `kcp_version` the parser does not recognise
- Duplicate `id` values (parsers SHOULD use the first occurrence)

The following conditions MUST cause the parser to reject the manifest:

- The file is not valid YAML
- The file is not UTF-8 encoded
- The `project` field is absent or empty
- The `units` field is absent or empty
- A unit is missing its `id`, `path`, `intent`, `scope`, or `audience` field

---

## 8. Conformance Levels

Implementations are encouraged to adopt KCP incrementally. Three levels are defined:

**Level 1 — Minimal**
The manifest contains `project`, `units`, and for each unit: `id`, `path`, `intent`, `scope`,
and `audience`. A Level 1 manifest answers the question: "what knowledge exists, what does
each piece answer, and who is it for?" Parsers SHOULD supply default values when `scope` or
`audience` are absent (`scope` defaults to `global`; `audience` defaults to an empty list).

**Level 2 — Structured**
Extends Level 1 with `validated` and `depends_on`. A Level 2 manifest supports freshness-aware
retrieval and dependency-ordered loading.

**Level 3 — Full**
Extends Level 2 with `triggers`, `supersedes`, and a `relationships` section. A Level 3
manifest supports task-based routing, knowledge graph navigation, and drift detection.

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

## 11. Extension Fields

Implementations MAY add custom fields to the root manifest or to individual units. Custom fields
SHOULD use a namespaced prefix to avoid collisions with future spec fields (e.g.
`x-myorg-priority: high`).

Parsers MUST silently ignore fields they do not recognise, including extension fields from other
implementations. This is required for forward compatibility.

---

## 12. Security Considerations

**Path traversal:** Parsers MUST NOT resolve `path` values that traverse outside the manifest's
root directory (i.e. paths containing `..` that escape the root). Such paths SHOULD be rejected
with an error.

**Denial of service:** Parsers operating in untrusted environments SHOULD impose limits on
manifest size, unit count, and string field lengths to guard against resource exhaustion.

**Trust:** A `knowledge.yaml` is as trustworthy as its source. Agents consuming KCP manifests
from untrusted sources SHOULD treat the content as untrusted input.

### 12.1 Trust Model

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

### 12.2 YAML Safety

Parsers MUST use a safe YAML constructor that disables arbitrary type instantiation. YAML
documents containing type tags that instantiate non-primitive types (e.g.
`!!javax.script.ScriptEngineManager` in Java) MUST be rejected.

Parsers MUST NOT use YAML loaders that execute code embedded in the document. This requirement
applies to all YAML content, including content fetched from remote sources.

### 12.3 Remote Content

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
- The YAML safety requirements of §12.2 apply to all remotely fetched manifests.

---

## Appendix A: Minimal Example

```yaml
kcp_version: "0.1"
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
kcp_version: "0.1"
project: wiki.example.org
version: 2.1.0
updated: 2026-02-25

units:
  - id: about
    path: about.md
    intent: "Who maintains this project and what is it for?"
    scope: global
    audience: [human, agent]
    validated: 2026-02-24

  - id: architecture-overview
    path: architecture/overview.md
    intent: "What is the high-level architecture and which components exist?"
    scope: global
    audience: [developer, architect, agent]
    validated: 2026-01-15
    depends_on: [about]
    triggers: [architecture, components, system-design, overview]

  - id: deployment-guide
    path: ops/deployment.md
    intent: "How do I deploy version 3.x to production?"
    scope: project
    audience: [operator, developer, agent]
    validated: 2026-02-20
    depends_on: [architecture-overview]
    supersedes: deployment-guide-v2
    triggers: [deployment, production, release, kubernetes, docker]

  - id: authentication-api
    path: api/authentication.md
    intent: "How do I authenticate API requests using OAuth 2.0?"
    scope: module
    audience: [developer, agent]
    validated: 2026-02-18
    depends_on: [architecture-overview]
    triggers: [oauth2, authentication, bearer-token, jwt, api-security]

  - id: deployment-guide-v2
    path: archive/deployment-v2.md
    intent: "Legacy deployment procedure for version 2.x (superseded)."
    scope: project
    audience: [agent]
    validated: 2025-09-01

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

*Knowledge Context Protocol — proposed by [eXOReaction AS](https://www.exoreaction.com), Oslo, Norway.*
*Spec repository: [github.com/cantara/knowledge-context-protocol](https://github.com/cantara/knowledge-context-protocol)*
