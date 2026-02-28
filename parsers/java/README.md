# kcp-parser -- Knowledge Context Protocol Java Parser

Reference parser and validator for the Knowledge Context Protocol (KCP).

## Prerequisites

- Java 17 or later
- Maven 3.8+

## Build

```bash
cd parsers/java
mvn clean package
```

This produces `target/kcp-parser-0.1.0.jar`.

## Install (as a library dependency)

Add to your `pom.xml`:

```xml
<dependency>
  <groupId>no.cantara.kcp</groupId>
  <artifactId>kcp-parser</artifactId>
  <version>0.1.0</version>
</dependency>
```

The parser is not yet published to Maven Central. For now, build from source
and install to your local repository:

```bash
mvn install
```

## Usage (as a library)

```java
import no.cantara.kcp.KcpParser;
import no.cantara.kcp.KcpValidator;
import no.cantara.kcp.KcpValidator.ValidationResult;
import no.cantara.kcp.model.KnowledgeManifest;

import java.nio.file.Path;

// Parse a manifest from disk
KnowledgeManifest manifest = KcpParser.parse(Path.of("knowledge.yaml"));

// Validate against the spec
ValidationResult result = KcpValidator.validate(manifest);

if (!result.isValid()) {
    System.err.println("Errors:");
    result.errors().forEach(e -> System.err.println("  " + e));
}
if (result.hasWarnings()) {
    System.out.println("Warnings:");
    result.warnings().forEach(w -> System.out.println("  " + w));
}
```

### Validate with path existence checking

Pass the manifest directory to check that declared paths exist on disk:

```java
Path manifestFile = Path.of("knowledge.yaml");
KnowledgeManifest manifest = KcpParser.parse(manifestFile);

// Warn if declared paths do not exist relative to the manifest directory
ValidationResult result = KcpValidator.validate(manifest, manifestFile.getParent());
```

### Parse from a Map

```java
import java.util.Map;
import java.util.List;

Map<String, Object> data = Map.of(
    "project", "my-project",
    "version", "1.0.0",
    "units", List.of(Map.of(
        "id", "overview",
        "path", "README.md",
        "intent", "What is this project?",
        "scope", "global",
        "audience", List.of("human", "agent")
    ))
);

KnowledgeManifest manifest = KcpParser.fromMap(data);
```

## CLI Usage

```bash
java -jar target/kcp-parser-0.1.0.jar knowledge.yaml
```

Output on success:
```
  knowledge.yaml is valid -- project 'my-project' v1.0.0, 5 unit(s), 2 relationship(s)
```

Output on failure:
```
  Validation failed -- 2 error(s):
    unit 'broken': 'scope' is required
    unit 'broken': 'intent' is required
```

Warnings are printed to stderr:
```
  manifest: 'kcp_version' not declared; assuming 0.1
```

## API Reference

### `KcpParser`

| Method | Description |
|--------|-------------|
| `parse(Path path)` | Parse a `knowledge.yaml` file from disk. Throws `IOException`. |
| `parse(InputStream is)` | Parse from an input stream (YAML content). |
| `fromMap(Map<String, Object> data)` | Parse from a pre-loaded map (e.g. from another YAML loader). |

### `KcpValidator`

| Method | Description |
|--------|-------------|
| `validate(KnowledgeManifest manifest)` | Validate a manifest without path existence checking. |
| `validate(KnowledgeManifest manifest, Path manifestDir)` | Validate with optional path existence checking. |
| `detectCycles(List<KnowledgeUnit> units, Set<String> unitIds)` | Detect cycles in `depends_on` graph. Returns cycle-closing edges. |

### `ValidationResult` (record)

| Method | Description |
|--------|-------------|
| `errors()` | `List<String>` of conditions that make the manifest invalid (MUST fix). |
| `warnings()` | `List<String>` of conditions that are permitted but suspicious (SHOULD fix). |
| `isValid()` | `true` if there are no errors. |
| `hasWarnings()` | `true` if there are warnings. |

### Model classes

- `KnowledgeManifest` -- root manifest: `kcpVersion`, `project`, `version`, `updated`, `units`, `relationships`
- `KnowledgeUnit` -- a knowledge unit: `id`, `path`, `intent`, `scope`, `audience`, `validated`, `dependsOn`, `supersedes`, `triggers`
- `Relationship` -- a directed relationship: `fromId`, `toId`, `type`

All model classes are Java records with immutable fields.

## Running Tests

```bash
mvn test
```

Tests cover:
- Parsing (minimal, complete, individual fields)
- Validation (required fields, scopes, audiences, relationships)
- Duplicate ID detection
- ID format validation
- Trigger constraints (max length, max count)
- Cycle detection in `depends_on` graphs
- Path traversal rejection (security)
- Path existence warnings

## Security

The parser uses SnakeYAML's `SafeConstructor` to prevent arbitrary Java type
instantiation via YAML tags (e.g. `!!javax.script.ScriptEngineManager`). This
is required by [SPEC.md section 12.2](../../SPEC.md).

Path traversal is validated at parse time: absolute paths and paths containing
`..` that escape the manifest root are rejected with an `IllegalArgumentException`.
