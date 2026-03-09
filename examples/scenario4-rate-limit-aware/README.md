# Scenario 4: Rate-Limit-Aware Agent Simulator

Two agents -- one polite, one greedy -- access the same KCP manifest. The manifest declares advisory `rate_limits` at root and unit level. The simulator demonstrates the difference between an agent that self-throttles and one that ignores the advisory entirely.

## What this demonstrates

1. **Advisory semantics**: `rate_limits` in KCP is metadata, not enforcement. Agents are expected to self-throttle.
2. **Root vs unit override**: Units can override the root-level default. Units without their own `rate_limits` inherit the root.
3. **Audit trail**: Every request is logged with a `withinLimit` flag. Violations are flagged as `ADVISORY VIOLATION`.
4. **Self-throttling**: The PoliteAgent checks `isWithinLimit()` before each request and waits (simulated) if the budget is exhausted.

## Agents

### PoliteAgent

- Checks `isWithinLimit()` before every request
- If at limit: waits (simulated via clock abstraction, not real sleep) until the minute window resets
- Logs throttle events: `"Throttling: unit=X, used=Y/min, limit=Z/min -- waiting Ns"`
- **Always has 0 advisory violations**

### GreedyAgent

- Ignores `rate_limits` entirely
- Bursts all requests immediately
- Logs each violation: `"ADVISORY VIOLATION: unit=X, used=Y/min, limit=Z/min"`
- Completes faster but with a trail of advisory violations

## Sample output (side by side)

```
PoliteAgent                                  GreedyAgent
-----------                                  -----------
Request: unit=public-docs, request #1        Request: unit=public-docs, request #1
Request: unit=public-docs, request #2        Request: unit=public-docs, request #2
Request: unit=public-docs, request #3        Request: unit=public-docs, request #3
Throttling: unit=compliance-data,            ADVISORY VIOLATION: unit=compliance-data,
  used=2/min, limit=2/min -- waiting 58s       used=3/min, limit=2/min
Request: unit=compliance-data, request #3    Request: unit=compliance-data, request #3
```

## Why rate_limits is not enforced by KCP

KCP is a metadata specification, not a runtime system. It declares *what an agent should do*, not what a server will force. Enforcement belongs to:

- **HTTP servers** via `429 Too Many Requests` responses
- **API gateways** via token-bucket or leaky-bucket rate limiting
- **Agent runtimes** that read the manifest and build internal budgets

KCP's role is to **declare the limits** so that well-behaved agents can plan ahead. A polite agent avoids 429s entirely by reading the manifest first. A greedy agent will eventually hit server-side enforcement but has no advance warning.

## Running

```bash
cd simulator
mvn test -q             # run all tests
mvn package -q          # build jar
java -jar target/kcp-scenario4-rate-limit-aware-0.1.0-jar-with-dependencies.jar \
     --manifest ../knowledge.yaml --requests 10
```

## Tests

| Test class | Tests | What it covers |
|------------|-------|----------------|
| `RequestBudgetTest` | 10 | Within/at/over limit, window reset, per-day, independent units |
| `ManifestParserTest` | 7 | Root inherit, unit override, no limits, multi-unit, depends_on, real manifest |
| `PoliteAgentTest` | 5 | All requests complete, throttles correctly, zero violations, multi-unit |
| `GreedyAgentTest` | 6 | Completes all, logs violations, ADVISORY VIOLATION messages, separate tracking |
| `Scenario4Test` | 6 | End-to-end: polite=0 violations, greedy>0, tighter=more violations, agent names |
