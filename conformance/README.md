# KCP Conformance Test Suite

Shared YAML fixtures and expected outcomes for verifying that KCP parser and validator implementations produce consistent results across languages.

## Structure

```
conformance/
  fixtures/
    level1/    — minimum viable: id, path, intent, scope, audience
    level2/    — adds depends_on, validated, hints, auth_scope
    level3/    — adds trust, auth.methods, compliance, delegation
    edge-cases/ — unicode IDs, empty units, path traversal, unknown versions
  runners/
    java/      — standalone Java runner using KcpParser + KcpValidator
    python/    — Python script using kcp.parser + kcp.validator
    typescript/ — TypeScript script using bridge parser + validator
```

## Fixture format

Each fixture is a pair of files:

- `<name>.yaml` — the KCP manifest to parse and validate
- `<name>.expected.json` — the expected outcome

### Expected JSON shape

```json
{
  "valid": true,
  "errors": [],
  "warnings": ["optional warning text"],
  "unit_count": 2,
  "relationship_count": 1
}
```

For fixtures that should fail at parse time (before validation):

```json
{
  "parse_error": true,
  "errors": ["description of expected error"]
}
```

### Comparison rules

Runners do NOT compare exact error/warning text (implementations phrase messages differently). Instead:

| Field | Rule |
|-------|------|
| `valid` | Exact boolean match |
| `errors` | If `valid` is `false`, actual errors must be non-empty (at least one error) |
| `warnings` | If present and non-empty in expected, actual warnings must be non-empty |
| `parse_error` | If `true`, parsing must throw/raise an exception |
| `unit_count` | Exact integer match |
| `relationship_count` | Exact integer match |

The `_note` field in expected JSON is informational only and ignored by runners.

## Conformance levels

| Level | Fields covered | Description |
|-------|---------------|-------------|
| Level 1 | id, path, intent, scope, audience, kind, format, triggers | Core manifest structure |
| Level 2 | depends_on, validated, hints, auth_scope, sensitivity | Inter-unit references and metadata |
| Level 3 | trust, auth.methods, delegation, compliance | Security, governance, and regulatory |

An implementation may claim **Level N conformance** if it passes all fixtures in levels 1 through N.

## Running the tests

### Java

```bash
cd /path/to/knowledge-context-protocol
cd parsers/java && mvn install -q && cd ../..
javac -cp parsers/java/target/classes \
  conformance/runners/java/ConformanceRunner.java \
  -d conformance/runners/java/
java -cp conformance/runners/java:parsers/java/target/classes:parsers/java/target/dependency/* \
  no.cantara.kcp.conformance.ConformanceRunner conformance/fixtures
```

### Python

```bash
cd /path/to/knowledge-context-protocol
pip install -e parsers/python
python conformance/runners/python/conformance_runner.py conformance/fixtures
```

### TypeScript

```bash
cd /path/to/knowledge-context-protocol
cd bridge/typescript && npm install && npm run build && cd ../..
npx tsx conformance/runners/typescript/conformance_runner.ts conformance/fixtures
```

## Adding new fixtures

1. Create a `<name>.yaml` file in the appropriate level directory
2. Create a matching `<name>.expected.json` with the expected outcome
3. Run all three runners to verify cross-language consistency
4. Commit both files together

## Known cross-implementation differences

Some edge cases have documented differences between implementations:

- **Empty units**: Java/Python treat as error; TypeScript treats as warning
- **Missing audience**: TypeScript requires non-empty audience; Java/Python do not
- **Duplicate IDs**: TypeScript treats as error; Java/Python treat as warning

These are tracked with `_note` fields in the expected JSON and the runners handle them gracefully.
