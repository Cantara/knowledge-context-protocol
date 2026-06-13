# knowledge-context-protocol

## Purpose
The Knowledge Context Protocol (KCP) specification -- a structured metadata standard that makes knowledge navigable by AI agents. KCP is to knowledge what MCP is to tools: it defines how knowledge is structured so agent tools can serve it effectively.

## Tech Stack
- Language: Specification (YAML-based), with reference implementations in TypeScript, Java, and Python
- Build: N/A (spec), npm (TS bridge), Maven (Java bridge), pip (Python bridge)
- Key dependencies: None (the spec is standalone)

## Architecture
KCP defines a `knowledge.yaml` manifest format with hierarchical units, each having intent, scope, audience, triggers, and dependency relationships. The spec supports 4 levels of adoption (L1-L4) from basic file listing to full agent orchestration. Includes:
- **Spec:** `SPEC.md` (core specification, currently v0.21; note: there is no v0.15 — the number was skipped to re-sync with the CLI release train)
- **RFCs:** 22 RFCs (auth, federation, trust, payments, context-window hints, query vocabulary, visibility/authority, discovery provenance, catalog, composition, negative space, content structure, observability, trusted render pipeline, unit content integrity, bi-temporal validity, temporal composition, federation temporal, composition integrity); promoted ones are marked Accepted in their headers
- **Bridges:** TypeScript, Java, Python parsers that surface KCP metadata via MCP
- **CLI:** `kcp` developer CLI — init, validate, query, stats, render (§16 trusted render pipeline)
- **Conformance:** Test suite and fixtures
- **Guides:** Adoption guides for existing projects

## Key Entry Points
- `SPEC.md` - Core specification
- `PROPOSAL.md` - Original proposal and rationale
- `CHANGELOG.md` - Release history (themed promotion waves)
- `bridge/` - TypeScript, Java, Python bridge implementations
- `cli/` - `kcp` developer CLI (TypeScript)
- `parsers/` - Standalone Java and Python parser/validator libraries
- `examples/` - Example knowledge.yaml files
- `skills/` - Portable Agent Skills (SKILL.md): kcp-adopt, kcp-author, kcp-navigate, kcp-render
- `guides/` - Adoption and integration guides
- `conformance/` - Test fixtures and conformance suite
- `experiments/` - Executable validation harnesses for RFCs (e.g. rfc-0018-render)
- `docs/` - GitHub Pages site

## Development
```bash
# TypeScript bridge
cd bridge/typescript && npm install && npm test

# Java bridge
cd bridge/java && mvn clean install

# Python bridge
cd bridge/python && pip install -e . && pytest
```

## Domain Context
AI agent knowledge infrastructure specification. Defines how projects describe their knowledge structure so AI agents can navigate codebases efficiently instead of guessing. Validated to reduce agent tool calls by 53-80% versus unguided exploration.
