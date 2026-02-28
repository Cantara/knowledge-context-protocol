# Knowledge Context Protocol (KCP) Specification

**Version:** 0.3
**Status:** Draft
**Date:** 2026-02-28
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
kcp_version: "0.3"          # RECOMMENDED
project: <string>            # REQUIRED
version: <semver string>     # RECOMMENDED
updated: "<ISO date>"        # RECOMMENDED; quote the value (see §4.1.1)
language: <BCP 47 tag>       # OPTIONAL; default language for all units (see §4.4c)
license: <string or object>  # OPTIONAL; default license for all units (see §4.6a)
indexing: <string or object>  # OPTIONAL; default indexing permissions (see §4.6c)

units:                       # REQUIRED; list of knowledge units
  - ...

relationships:               # OPTIONAL; list of cross-unit relationship declarations
  - ...
```

### 3.1 Root Fields

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `kcp_version` | RECOMMENDED | string | Version of this specification. MUST be `"0.3"` for conformance with this document. |
| `project` | REQUIRED | string | Human-readable name of the project or documentation site. |
| `version` | RECOMMENDED | string | Semver version of this manifest. Increment when units are added or removed. |
| `updated` | RECOMMENDED | string | ISO 8601 date (`YYYY-MM-DD`) when this manifest was last modified. |
| `language` | OPTIONAL | string | BCP 47 language tag as default for all units. See §4.4c. |
| `license` | OPTIONAL | string or object | Default license for all units. See §4.6a. |
| `indexing` | OPTIONAL | string or object | Default indexing permissions for all units. See §4.6c. |
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
valid value: `"0.3"`. The values `"0.1"` and `"0.2"` refer to prior drafts (February 2026);
parsers SHOULD treat `"0.1"` and `"0.2"` manifests as conformant with this version. Parsers
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
`license`, `indexing`), unit fields (`id`, `path`, `kind`, `intent`, `format`, `content_type`,
`language`, `scope`, `audience`, `license`, `validated`, `update_frequency`, `indexing`,
`depends_on`, `supersedes`, `triggers`), and relationship fields (`from`, `to`, `type`).

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
Extends Level 1 with `validated`, `depends_on`, `kind`, `format`, and `language`. A Level 2
manifest supports freshness-aware retrieval, dependency-ordered loading, artifact type
classification, content format awareness, and multilingual navigation.

**Level 3 — Full**
Extends Level 2 with `triggers`, `supersedes`, `license`, `update_frequency`, `indexing`,
and a `relationships` section. A Level 3 manifest supports task-based routing, knowledge
graph navigation, drift detection, usage rights declaration, cache management, and AI
crawling permissions.

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

## 12. Extension Fields

Implementations MAY add custom fields to the root manifest or to individual units. Custom fields
SHOULD use a namespaced prefix to avoid collisions with future spec fields (e.g.
`x-myorg-priority: high`).

Parsers MUST silently ignore fields they do not recognise, including extension fields from other
implementations. This is required for forward compatibility.

---

## 13. Security Considerations

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
kcp_version: "0.3"
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
kcp_version: "0.3"
project: wiki.example.org
version: 2.1.0
updated: "2026-02-28"
language: en
license: "Apache-2.0"
indexing: open

units:
  - id: about
    path: about.md
    intent: "Who maintains this project and what is it for?"
    scope: global
    audience: [human, agent]
    validated: "2026-02-24"
    update_frequency: monthly

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

  - id: authentication-api
    path: api/authentication.md
    intent: "How do I authenticate API requests using OAuth 2.0?"
    scope: module
    audience: [developer, agent]
    validated: "2026-02-18"
    depends_on: [architecture-overview]
    triggers: [oauth2, authentication, bearer-token, jwt, api-security]

  - id: pre-commit-gate
    kind: policy
    path: .husky/pre-commit
    intent: "What checks run automatically before every commit?"
    format: text
    scope: project
    audience: [developer, agent]
    validated: "2026-02-10"
    indexing: read-only

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

  - id: deployment-guide-v2
    path: archive/deployment-v2.md
    intent: "Legacy deployment procedure for version 2.x (superseded)."
    scope: project
    audience: [agent]
    validated: "2025-09-01"

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
