# knowledge-context-protocol

## Purpose
The Knowledge Context Protocol (KCP) specification -- a structured metadata standard that makes knowledge navigable by AI agents. KCP is to knowledge what MCP is to tools: it defines how knowledge is structured so agent tools can serve it effectively.

## Tech Stack
- Language: Specification (YAML-based), with reference implementations in TypeScript, Java, and Python
- Build: N/A (spec), npm (TS bridge), Maven (Java bridge), pip (Python bridge)
- Key dependencies: None (the spec is standalone)

## Architecture
KCP defines a `knowledge.yaml` manifest format with hierarchical units, each having intent, scope, audience, triggers, and dependency relationships. The spec supports 4 levels of adoption (L1-L4) from basic file listing to full agent orchestration. Includes:
- **Spec:** `SPEC.md` (core specification, currently v0.6)
- **RFCs:** 6 extension RFCs (auth, federation, trust, payments, context-window hints)
- **Bridges:** TypeScript, Java, Python parsers that surface KCP metadata via MCP
- **Conformance:** Test suite and fixtures
- **Guides:** Adoption guides for existing projects

## Key Entry Points
- `SPEC.md` - Core specification
- `PROPOSAL.md` - Original proposal and rationale
- `bridge/` - TypeScript, Java, Python bridge implementations
- `examples/` - Example knowledge.yaml files
- `guides/` - Adoption and integration guides
- `conformance/` - Test fixtures and conformance suite
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
