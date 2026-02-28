# kcp-mcp — Java MCP Bridge for KCP

Exposes a [`knowledge.yaml`](https://github.com/Cantara/knowledge-context-protocol) manifest as [MCP resources](https://modelcontextprotocol.io/specification/draft/server/resources). Every AI agent that speaks MCP can navigate your knowledge without loading everything at once.

## Install

```bash
# Build a fat jar from source
cd bridge/java
mvn package -DskipTests
# Produces target/kcp-mcp-0.1.0-jar-with-dependencies.jar
```

## Usage

```bash
# Serve ./knowledge.yaml via stdio (default)
java -jar kcp-mcp-0.1.0-jar-with-dependencies.jar

# Specify a path
java -jar kcp-mcp-0.1.0-jar-with-dependencies.jar path/to/knowledge.yaml

# Only expose units with audience: [agent]
java -jar kcp-mcp-0.1.0-jar-with-dependencies.jar knowledge.yaml --agent-only

# Suppress validation warnings
java -jar kcp-mcp-0.1.0-jar-with-dependencies.jar knowledge.yaml --no-warnings
```

## Configure in Claude Code

Add to your project's `.mcp.json` or `~/.claude/mcp.json`:

```json
{
  "mcpServers": {
    "project-knowledge": {
      "command": "java",
      "args": ["-jar", "/path/to/kcp-mcp-0.1.0-jar-with-dependencies.jar", "knowledge.yaml"]
    }
  }
}
```

## What the agent sees

On connection, the agent calls `resources/list` to see all knowledge units. Each unit is an MCP resource:

| MCP field | Source |
|-----------|--------|
| `uri` | `knowledge://{project-slug}/{unit.id}` |
| `name` | `unit.id` |
| `title` | `unit.intent` |
| `description` | intent + triggers + depends_on |
| `mimeType` | resolved from `content_type` → `format` → file extension |
| `annotations.priority` | `global=1.0`, `project=0.7`, `module=0.5` |
| `annotations.audience` | `[assistant]` if `agent` in audience |

A synthetic **manifest resource** at `knowledge://{slug}/manifest` returns the full unit index as JSON — the recommended entry point for agents.

## Use as a library

```java
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import no.cantara.kcp.mcp.KcpServer;

import java.nio.file.Path;

// Create the server
StdioServerTransportProvider transport =
    new StdioServerTransportProvider(McpJsonDefaults.getMapper());

McpSyncServer server = KcpServer.createServer(
    Path.of("knowledge.yaml"), transport, false, true);
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
