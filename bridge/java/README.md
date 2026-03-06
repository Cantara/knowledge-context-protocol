# kcp-mcp — Java MCP Bridge for KCP

Exposes a [`knowledge.yaml`](https://github.com/Cantara/knowledge-context-protocol) manifest as an MCP server. Every AI agent that speaks MCP — Claude Code, GitHub Copilot, Cursor, Windsurf — can navigate your knowledge, search units, and get CLI syntax guidance without loading everything at once.

A `knowledge.yaml` file maps your project's documentation — what each file answers, its audience, and how docs relate to each other. See the [KCP specification](https://github.com/Cantara/knowledge-context-protocol) for the schema and a starter template.

**v0.10.0:** Added MCP tools (`search_knowledge`, `get_unit`, `get_command_syntax`), MCP prompts (`sdd-review`, `kcp-explore`), `--generate-instructions`, and three-tier static integration (`--generate-all`, `--output-dir`, `--split-by`, `--generate-agent`) for zero-infra Copilot support.

## Install

```bash
# Build a fat jar from source
cd bridge/java
mvn package -DskipTests
# Produces target/kcp-mcp-0.10.0-jar-with-dependencies.jar
```

## Quick start

```bash
# Serve ./knowledge.yaml via stdio
java -jar kcp-mcp-0.10.0-jar-with-dependencies.jar

# Serve with kcp-commands syntax guidance
java -jar kcp-mcp-0.10.0-jar-with-dependencies.jar knowledge.yaml \
  --commands-dir /path/to/kcp-commands/commands

# Generate .github/copilot-instructions.md (no server needed)
java -jar kcp-mcp-0.10.0-jar-with-dependencies.jar \
  --generate-instructions knowledge.yaml > .github/copilot-instructions.md

# Agent-only units
java -jar kcp-mcp-0.10.0-jar-with-dependencies.jar knowledge.yaml --agent-only
```

## Three-tier generation (enterprise / no MCP)

Generate all three tiers of static Copilot integration in one command:

```bash
# All three tiers in one command
java -jar kcp-mcp-0.10.0-jar-with-dependencies.jar --generate-all knowledge.yaml

# Fine-grained control
java -jar kcp-mcp-0.10.0-jar-with-dependencies.jar --generate-instructions knowledge.yaml --output-format compact > .github/copilot-instructions.md
java -jar kcp-mcp-0.10.0-jar-with-dependencies.jar --generate-instructions knowledge.yaml --output-dir .github/instructions/ --split-by directory
java -jar kcp-mcp-0.10.0-jar-with-dependencies.jar --generate-agent knowledge.yaml --max-chars 25000 > .github/agents/kcp-expert.agent.md
```

See [Copilot setup guide](../../docs/guides/copilot-setup.md) for details on each tier.

## Configure in Claude Code

Add to `.mcp.json` in your project root or `~/.claude/mcp.json`:

```json
{
  "mcpServers": {
    "project-knowledge": {
      "command": "java",
      "args": ["-jar", "/path/to/kcp-mcp-0.10.0-jar-with-dependencies.jar", "knowledge.yaml"]
    }
  }
}
```

With kcp-commands syntax injection:

```json
{
  "mcpServers": {
    "project-knowledge": {
      "command": "java",
      "args": [
        "-jar", "/path/to/kcp-mcp-0.10.0-jar-with-dependencies.jar",
        "knowledge.yaml",
        "--commands-dir", "/path/to/kcp-commands/commands"
      ]
    }
  }
}
```

## Configure in GitHub Copilot (VS Code / JetBrains / CLI)

Add `.vscode/mcp.json` to your project:

```json
{
  "servers": {
    "project-knowledge": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/path/to/kcp-mcp-0.10.0-jar-with-dependencies.jar", "knowledge.yaml"]
    }
  }
}
```

See [Copilot setup guide](../../docs/guides/copilot-setup.md) for detailed instructions per IDE.

## MCP capabilities

### Resources

Every knowledge unit becomes an MCP resource at `knowledge://{project-slug}/{unit.id}`.
A manifest meta-resource at `knowledge://{slug}/manifest` returns the full unit index as JSON — the recommended entry point for agents.

| MCP field | Source |
|-----------|--------|
| `uri` | `knowledge://{project-slug}/{unit.id}` |
| `name` | `unit.id` |
| `title` | `unit.intent` |
| `description` | intent + triggers + depends_on |
| `mimeType` | resolved from `content_type` -> `format` -> file extension |
| `annotations.priority` | `global=1.0`, `project=0.7`, `module=0.5` |
| `annotations.audience` | `[assistant]` if `agent` in audience |

### Tools (v0.10.0)

**`search_knowledge`** — Find units by keyword. Agents call this instead of loading the entire manifest.

```
Input:  { query: string, audience?: string, scope?: string }
Output: JSON array of top-5 matching units with id, intent, path, uri, score
```

Scoring: trigger match = 5 pts, intent match = 3 pts, id/path match = 1 pt.

**`get_unit`** — Fetch the content of a specific unit by id.

```
Input:  { unit_id: string }
Output: Full text content of the unit file
```

**`get_command_syntax`** — Get CLI syntax guidance from kcp-commands manifests.
Only available when `--commands-dir` is set.

```
Input:  { command: string }   e.g. "git commit", "mvn", "docker build"
Output: Compact syntax block with usage, key flags, and preferred invocations
```

Example output:
```
[kcp] git commit: Record staged changes to the repository
Usage: git commit [<options>]
Key flags:
  -m '<message>': Commit message inline  -> Simple one-line commits
Preferred:
  git commit -m 'Add feature X'  # Standard single-line commit
```

### Prompts (v0.10.0)

**`sdd-review`** — Review code or architecture using SDD (Skill-Driven Development) methodology.
Optional argument: `focus` (`architecture` | `quality` | `security` | `performance`).

**`kcp-explore`** — Explore available knowledge units for a topic.
Required argument: `topic`.

## Zero-infra option: `--generate-instructions`

For teams that cannot run MCP servers (locked-down enterprise environments, GitHub.com Copilot):

```bash
# Generate .github/copilot-instructions.md
java -jar kcp-mcp-0.10.0-jar-with-dependencies.jar \
  --generate-instructions knowledge.yaml > .github/copilot-instructions.md

# Agent-facing units only
java -jar kcp-mcp-0.10.0-jar-with-dependencies.jar \
  --generate-instructions knowledge.yaml --audience agent > .github/copilot-instructions.md
```

The output is a static markdown file that Copilot injects into every chat interaction in the repository. No server, no runtime, no configuration beyond committing the file.

## Sub-manifests

Merge multiple `knowledge.yaml` files into a single MCP namespace. Units from sub-manifests are merged under the primary project slug; the primary manifest wins on duplicate ids.

```bash
java -jar kcp-mcp-0.10.0-jar-with-dependencies.jar knowledge.yaml \
  --sub-manifests path/to/sub1/knowledge.yaml path/to/sub2/knowledge.yaml
```

## CLI reference

```
kcp-mcp [path/to/knowledge.yaml] [options]

Options:
  --agent-only              Only expose units with audience: [agent]
  --sub-manifests path ...  Additional manifests to merge
  --commands-dir <path>     Load kcp-commands manifests (enables get_command_syntax tool)
  --generate-instructions   Write copilot-instructions.md to stdout and exit
  --audience <value>        Filter units by audience (use with --generate-instructions or --generate-agent)
  --output-format <fmt>     Output format: full | compact | agent (default: full)
  --output-dir <path>       Write to directory instead of stdout (enables split mode)
  --split-by <strategy>     Split strategy: directory | scope | unit | none (default: directory, requires --output-dir)
  --generate-agent          Write kcp-expert .agent.md to stdout and exit
  --max-chars <n>           Truncate agent file to n characters, dropping module-scope units first (default: 0 = no limit)
  --generate-all            Generate all three tiers to .github/ (copilot-instructions.md + instructions/ + agents/)
  --no-warnings             Suppress KCP validation warnings
  --help, -h                Show help
```

## Use as a library

```java
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import no.cantara.kcp.mcp.KcpCommands;
import no.cantara.kcp.mcp.KcpServer;

import java.nio.file.Path;
import java.util.Map;

// Basic server
StdioServerTransportProvider transport =
    new StdioServerTransportProvider(McpJsonDefaults.getMapper());

McpSyncServer server = KcpServer.createServer(
    Path.of("knowledge.yaml"), transport, false, true);

// With command syntax tools
Map<String, KcpCommands.CommandManifest> commands =
    KcpCommands.loadCommandManifests(Path.of("/path/to/kcp-commands/commands"));

McpSyncServer serverWithCommands = KcpServer.createServer(
    Path.of("knowledge.yaml"), transport, false, true,
    java.util.List.of(), commands);
```

Instructions generator:

```java
import no.cantara.kcp.mcp.KcpInstructions;

String md = KcpInstructions.generateInstructions(
    Path.of("knowledge.yaml"), "agent");
System.out.print(md);
```

## Development

```bash
# First install the KCP parser to local Maven repo
cd parsers/java && mvn install -q

# Then build and test the bridge
cd bridge/java
mvn test
```

## License

Apache-2.0 — same as the KCP specification.
