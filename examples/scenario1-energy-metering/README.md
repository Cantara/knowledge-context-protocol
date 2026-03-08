# Scenario 1: Smart Energy Metering

A two-agent simulation demonstrating escalating KCP access levels across 4 knowledge units in a utility energy metering domain.

## Domain Context

A utility company operates a smart grid with household meters. A Grid Orchestrator Agent discovers an Energy Metering Agent via its A2A Agent Card, then requests knowledge units ranging from publicly available tariff schedules through to restricted raw 15-second smart meter telemetry that requires human-in-the-loop approval before access is granted.

## Agent Topology

```
  +---------------------------+          +---------------------------+
  | GridOrchestratorAgent     |  A2A     | EnergyMeteringAgent       |
  | (discovers & requests)    | -------> | (owns 4 knowledge units)  |
  +---------------------------+   KCP    +---------------------------+
                                          |  1. tariff-schedule      |
                                          |  2. meter-readings       |
                                          |  3. billing-history      |
                                          |  4. smart-meter-raw      |
                                          +---------------------------+
```

## KCP Manifest Summary

| Unit              | access          | sensitivity  | auth_scope     | HITL     | delegation max_depth |
|-------------------|-----------------|--------------|----------------|----------|----------------------|
| tariff-schedule   | public          | public       | --             | no       | 2 (root)             |
| meter-readings    | authenticated   | internal     | read:meter     | no       | 2 (root)             |
| billing-history   | authenticated   | internal     | read:billing   | no       | 2 (root)             |
| smart-meter-raw   | restricted      | restricted   | grid-engineer  | required | 1 (unit override)    |

Root delegation: `max_depth: 2`, `require_capability_attenuation: true`, `audit_chain: true`

## How to Build and Run

```bash
cd examples/scenario1-energy-metering/simulator

# Run tests
mvn test

# Build fat jar
mvn package -DskipTests

# Run simulation (auto-approve HITL for non-interactive mode)
java -jar target/kcp-scenario1-energy-metering-0.1.0-jar-with-dependencies.jar --auto-approve

# Run interactively (prompts for human approval on smart-meter-raw)
java -jar target/kcp-scenario1-energy-metering-0.1.0-jar-with-dependencies.jar
```

## Sample Output (key lines)

```
=== SCENARIO 1: Smart Energy Metering ===
Domain: Utility Grid Operations | KCP v0.6 | A2A Agent Card v1.0.0

-- Phase 1: Agent Discovery (A2A Layer) ------------------------------------
[A2A]  Discovered: "Energy Metering Agent" at https://grid.example.com/agent
[A2A]  Skills: tariff-lookup, consumption-analysis, grid-diagnostics

-- Phase 4: Knowledge Access (Escalating Access Levels) --------------------

[U1]   Requesting unit: tariff-schedule
[KCP]  Access: public -> no credential required
[KCP]  Result: LOADED
[AUDIT] trace: 00-a1b2c3...-01

[U2]   Requesting unit: meter-readings
[KCP]  Access: authenticated -> checking credential
[KCP]  Token valid: yes | Scope 'read:meter': yes
[KCP]  Result: LOADED

[U3]   Requesting unit: billing-history
[KCP]  Access: authenticated -> checking credential
[KCP]  Token valid: yes | Scope 'read:billing': yes
[KCP]  Result: LOADED

[U4]   Requesting unit: smart-meter-raw
[KCP]  Access: restricted | auth_scope: grid-engineer | sensitivity: restricted
[KCP]  Delegation: max_depth=1 (unit override)
[KCP]  Human-in-the-loop: REQUIRED (approval_mechanism: oauth_consent)
[HITL] Approval required for unit 'smart-meter-raw' (sensitivity: restricted, PII data)
[HITL] Approved by: researcher@example.com
[KCP]  Result: LOADED (after human approval)
[AUDIT] trace: 00-d4e5f6...-01
[AUDIT] unit: smart-meter-raw | human_approval: researcher@example.com

=== SIMULATION SUMMARY ===
  Units loaded successfully:  4
  Access denied:              0
  HITL approvals:             1
  Audit entries:              4
```

## Spec Gaps Surfaced

### 1. HITL approval_mechanism is declared but not defined (v0.6)

The `human_in_the_loop.approval_mechanism: oauth_consent` field in the KCP manifest tells the consuming agent WHAT mechanism to use for obtaining human approval, but the spec does not define:

- The consent request format (what payload does the agent send to the approval endpoint?)
- How the approval signal is verified (is it a signed token? a callback? a polling endpoint?)
- The trust chain between the HITL gate and the knowledge owner (how does the owner know the approval was genuine?)

**Status:** Implementation-defined in v0.6. RFC-0002 Proposal 4 tracks the formal HITL protocol design.

**Impact:** Every implementer will build a different approval flow, making cross-vendor agent interoperability on HITL-gated units effectively impossible until this is standardised.

**Recommendation:** v0.7 should define at minimum: (a) a consent request JSON schema, (b) a signed approval token format, and (c) a verification endpoint or callback contract.

## Tests

36 tests across 3 test classes:

- **AccessDecisionEngineTest** (14 tests): All 4 access levels, scope validation, HITL triggering, wrong-scope denial
- **AgentCardParserTest** (7 tests): Agent card structure, skills, scopes, provider parsing
- **EnergySimulatorIntegrationTest** (15 tests): Full end-to-end simulation, per-unit output verification, audit trail, summary counts
