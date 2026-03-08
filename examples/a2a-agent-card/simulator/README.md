# A2A + KCP Composition Simulator

Runnable Java demo showing how an A2A Agent Card and a KCP manifest compose as two complementary layers in a multi-agent system. The simulator walks through agent discovery, knowledge discovery, OAuth2 authentication, and per-unit access decisions with escalating controls (public, authenticated, restricted + human-in-the-loop).

See the [parent README](../README.md) for the full conceptual explanation.

## Prerequisites

- Java 17+
- Maven 3.8+
- Install the KCP parser to your local Maven repository first:
  ```bash
  cd ../../parsers/java
  mvn install -q
  ```

## Build

```bash
mvn package
```

## Run

Auto-approve mode (for CI, no stdin prompts):

```bash
java -jar target/kcp-a2a-simulator-0.1.0-jar-with-dependencies.jar --auto-approve
```

Interactive mode (prompts for human approval on restricted units):

```bash
java -jar target/kcp-a2a-simulator-0.1.0-jar-with-dependencies.jar
```

Custom file paths:

```bash
java -jar target/kcp-a2a-simulator-0.1.0-jar-with-dependencies.jar \
  --agent-card ../agent-card.json \
  --manifest ../knowledge.yaml \
  --auto-approve
```

## Test

```bash
mvn test
```
