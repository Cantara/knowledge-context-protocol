# Scenario 5: Dependency Ordering Simulator

A knowledge ingestion agent that builds a dependency graph from `depends_on` fields and `type: depends_on` relationships, topologically sorts it using Kahn's algorithm, and loads units in safe order. Handles 5 of the 6 relationship types with appropriate logging (the sixth, `governs`, is demonstrated in the federation example).

## What this demonstrates

1. **Topological sort**: Units are loaded in dependency order -- all prerequisites before dependents.
2. **Dual dependency sources**: Merges inline `depends_on` fields and `relationships[].type: depends_on` entries, deduplicating edges.
3. **Cycle detection**: Throws `CycleException` with the cycle path if the graph contains a cycle.
4. **Cascading skip**: If a dependency fails or is skipped, all transitive dependents are also skipped.
5. **Relationship-aware logging**: Logs `SUPERSEDES` when a newer unit replaces an older one, and flags `CONFLICT` warnings for `contradicts` relationships.

## Units (NovaPlatform v1 to v2 migration)

| Unit | Access | Depends on | Special |
|------|--------|------------|---------|
| `platform-overview` | public | (none) | Entry point |
| `migration-guide` | public | platform-overview | |
| `api-v1-reference` | public | (none) | deprecated |
| `api-v2-reference` | public | migration-guide | supersedes api-v1-reference |
| `deployment-guide` | authenticated | api-v2-reference | |
| `legacy-security-policy` | authenticated | (none) | deprecated |
| `zero-trust-policy` | authenticated | (none) | supersedes legacy-security-policy |
| `troubleshooting` | public | deployment-guide | |

## 5 of 6 relationship types

| Type | Example | Agent behaviour |
|------|---------|-----------------|
| `depends_on` | migration-guide depends_on platform-overview | Enforced via topological sort |
| `enables` | platform-overview enables migration-guide | Informational (logged but not enforced) |
| `supersedes` | api-v2-reference supersedes api-v1-reference | Logged: "prefer this version" |
| `contradicts` | legacy-security-policy contradicts zero-trust-policy | Warning: "CONFLICT" |
| `context` | legacy-security-policy context for zero-trust-policy | Informational |

## Running

```bash
cd simulator
mvn test -q             # run all tests
mvn package -q          # build jar
java -jar target/kcp-scenario5-dependency-ordering-0.1.0-jar-with-dependencies.jar \
     --manifest ../knowledge.yaml
```

## Tests

| Test class | Tests | What it covers |
|------------|-------|----------------|
| `TopologicalSorterTest` | 9 | Linear chain, diamond, disconnected, cycles (3-node, 2-node, self), single node |
| `DependencyGraphTest` | 7 | Merge inline+relationship, dedup, ignore non-depends_on, empty, relationship-only nodes |
| `KnowledgeIngestionAgentTest` | 7 | Load order, cascading skip, supersedes log, contradicts warning, empty manifest |
| `ManifestParserTest` | 5 | Units+relationships, supersedes, all 5 types, access/sensitivity, real manifest |
| `Scenario5Test` | 6 | All 8 units processed, all loaded, correct order, supersedes+contradicts logged |
