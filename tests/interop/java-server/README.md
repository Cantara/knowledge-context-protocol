# Java MCP Bridge Server (Interop)

The Java MCP bridge uses stdio transport only (no HTTP mode).

To use it in interop testing, start it as a subprocess:

```bash
# Build the fat JAR first
cd ../../bridge/java
mvn package -q

# Run via stdio (clients connect via stdin/stdout)
java -jar target/kcp-mcp-*-jar-with-dependencies.jar \
  ../../tests/interop/knowledge.yaml
```

The interop test script (`run-interop.sh`) currently uses the TypeScript and Python
bridges which both support stdio client connections. The Java bridge can be tested
by replacing either the TS or Python bridge in the script.
