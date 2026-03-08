# Scenario 3: Financial AML Intelligence (Adversarial)

A five-agent adversarial simulation demonstrating AML compliance orchestration with delegation chains, rogue agent attacks, compliance blocks, and multi-HITL workflows. Models the kind of system a bank would run for anti-money-laundering compliance.

## Domain Context

A bank operates an AML compliance pipeline where a Compliance Orchestrator coordinates with a Transaction Intelligence Agent that owns sensitive financial data. Legitimate downstream agents (Sanctions Screening, ML Risk Scoring) receive delegated access with attenuated scopes. A Rogue Agent attempts several violations: depth exceeding, scope elevation, and accessing no-delegation units. The simulation also demonstrates GDPR data residency enforcement.

## Agent Topology

```
  +-------------------------------+          +-------------------------------+
  | ComplianceOrchestratorAgent   |  A2A     | TransactionIntelligenceAgent  |
  | (top-level coordinator)       | -------> | (owns 5 knowledge units)      |
  +-------------------------------+   KCP    +-------------------------------+
                                                  |              |
                                     depth=1      |              |     depth=2
                                                  v              v
                                     +-------------------+  +-------------------+
                                     | SanctionsScreening|  | MLRiskScoring     |
                                     | Agent (depth=1)   |->| Agent (depth=2)   |
                                     +-------------------+  +-------------------+

                                     +-------------------+
                                     | RogueAgent        |  <-- adversarial
                                     | (misconfigured)   |
                                     +-------------------+
```

## KCP Manifest Summary

| Unit                 | access          | sensitivity    | auth_scope          | HITL     | max_depth | regulations         | residency | restrictions               |
|----------------------|-----------------|----------------|---------------------|----------|-----------|---------------------|-----------|----------------------------|
| sanctions-lists      | public          | public         | --                  | no       | 3 (root)  | --                  | --        | --                         |
| transaction-patterns | authenticated   | internal       | read:transactions   | no       | 3 (root)  | --                  | --        | --                         |
| customer-profiles    | restricted      | confidential   | aml-analyst         | required | 2         | GDPR, AML5D         | EU        | --                         |
| sar-drafts           | restricted      | restricted     | compliance-officer  | required | 1         | AML5D, FATF         | --        | --                         |
| raw-wire-transfers   | restricted      | restricted     | compliance-officer  | no       | 0 (NONE)  | GDPR, AML5D, NIS2   | EU        | no_ai_training, no_cross_border |

Root delegation: `max_depth: 3`, `require_capability_attenuation: true`, `audit_chain: true`

## Simulation Phases (7 total)

| Phase | Description | Agents | Outcome |
|-------|-------------|--------|---------|
| 1 | Happy path: public + authenticated | Orchestrator -> TxIntel | 2 units LOADED |
| 2 | HITL chain: restricted units | Orchestrator -> TxIntel | 2 units LOADED (2 HITL approvals) |
| 3 | Legitimate delegation: depth=1 -> depth=2 | TxIntel -> Sanctions -> MLRisk | 2 units DELEGATED (attenuated scopes) |
| 4 | Rogue: delegation depth violation | RogueAgent | BLOCKED (depth=3 > max_depth=2) |
| 5 | Rogue: scope elevation attempt | RogueAgent | BLOCKED (read:transactions cannot access aml-analyst unit) |
| 6 | Rogue: max_depth=0 violation | RogueAgent | BLOCKED (raw-wire-transfers cannot be delegated) |
| 7 | Compliance: GDPR data residency | Any agent from US | BLOCKED (EU-only unit) |

**Expected summary:**
```
=== SIMULATION SUMMARY ===
  Units loaded successfully:  6
  Access denied (policy):     1
  Access denied (delegation): 2
  Access denied (compliance): 1
  HITL approvals:             2
  Audit entries:              14
  Violations detected:        4
```

## How to Build and Run

```bash
cd examples/scenario3-financial-aml/simulator

# Run tests
mvn test

# Build fat jar
mvn package -DskipTests

# Run simulation (auto-approve HITL)
java -jar target/kcp-scenario3-financial-aml-0.1.0-jar-with-dependencies.jar --auto-approve
```

## Sample Output (key lines)

```
=== SCENARIO 3: Financial AML Intelligence (Adversarial) ===
Agents: ComplianceOrchestrator, TransactionIntelligence, SanctionsScreening, MLRiskScoring, RogueAgent

-- Phase 1: Happy Path (public + authenticated access) ---------------------
[P1-a] Requesting unit: sanctions-lists
[KCP]  Result: LOADED

-- Phase 2: HITL Chain (restricted units, human approval required) ----------
[P2-a] Requesting unit: customer-profiles
[KCP]  Human-in-the-loop: REQUIRED (approval_mechanism: oauth_consent)
[KCP]  Result: LOADED (after human approval)

[P2-b] Requesting unit: sar-drafts
[KCP]  Human-in-the-loop: REQUIRED (approval_mechanism: manual)
[KCP]  Result: LOADED (after human approval)

-- Phase 3: Legitimate Delegation Chain (depth=1 -> depth=2) ----------------
[P3-a] Delegating 'transaction-patterns' to SanctionsScreeningAgent (depth=1)
[KCP]  Scope narrowed: 'read:transactions' -> 'read:transactions:sanctions-only'
[KCP]  Result: DELEGATED to SanctionsScreeningAgent (depth=1)

-- Phase 4: RogueAgent -- Delegation Depth Violation ------------------------
[P4]   RogueAgent claims depth=3 delegatee for 'customer-profiles' (max_depth=2)
[KCP]  Result: BLOCKED (Depth 3 exceeds max_depth=2)
[AUDIT] VIOLATION: RogueAgent depth=3 exceeds max_depth=2

-- Phase 5: RogueAgent -- Scope Elevation Attempt --------------------------
[P5]   RogueAgent has 'read:transactions' token, requests 'customer-profiles'
[KCP]  Result: BLOCKED (Invalid token or missing scope: aml-analyst)
[AUDIT] VIOLATION: RogueAgent scope elevation attempt

-- Phase 6: RogueAgent -- max_depth=0 Violation Attempt ---------------------
[P6]   RogueAgent attempts 'raw-wire-transfers' as delegatee (max_depth=0)
[KCP]  Result: BLOCKED (this unit cannot be delegated)

-- Phase 7: Compliance Block (GDPR Data Residency) -------------------------
[P7]   Request for 'customer-profiles' with data_residency claim: US
[KCP]  Result: BLOCKED (Data residency violation: request from 'US' but unit restricted to [EU])
[AUDIT] COMPLIANCE VIOLATION: GDPR data residency
```

## Spec Gaps Surfaced

### 1. Compliance blocks are advisory in v0.6

`compliance.data_residency` is not in the v0.6 core spec -- it comes from RFC-0004. The simulator implements a best-effort check, but must note: this enforcement is application-defined until RFC-0004 promotes to core. An agent could ignore these fields entirely and remain spec-compliant.

**Status:** RFC-0004 field. Application-defined enforcement until promoted.

**Impact:** Cross-vendor compliance enforcement is impossible until these fields become normative. A bank deploying agents from different vendors cannot guarantee that all agents respect data residency constraints.

### 2. No mechanism for cross-agent identity verification

When RogueAgent claims to be a depth=3 delegatee, the spec has no cryptographic chain for verifying the delegation lineage. W3C Trace Context propagation (which the spec recommends via `trust.audit.require_trace_context`) helps with audit traceability but does not prevent spoofing. A malicious agent can fabricate a traceparent and claim any delegation depth.

**Status:** Delegation chain integrity requires signed delegation tokens. See RFC-0002 open question on delegation token format.

**Recommendation:** Define a signed delegation token format where each hop in the chain signs the narrowed scope and depth, creating a verifiable chain. Without this, `max_depth` enforcement is advisory at best.

### 3. `no_ai_training` restriction enforcement

`restrictions: [no_ai_training]` appears in the manifest but the spec defines no standard enforcement mechanism. An agent can claim compliance but the system cannot verify it. The restriction is purely declarative -- there is no technical control preventing an agent from feeding the data into a training pipeline.

**Status:** v0.7 gap. The simulator logs the restriction but cannot enforce it.

**Recommendation:** Consider: (a) a `restrictions` acknowledgment field in the agent's access request, creating an auditable claim; (b) a monitoring/attestation protocol for post-hoc verification; (c) integration with DRM or data lineage systems.

### 4. Multiple HITL gates in a single request flow

The spec says HITL is per-unit but does not define what happens when an orchestrator needs approvals for multiple units in one workflow (this simulation triggers 2 HITL gates sequentially in Phase 2). Should they be:

- **Sequential** (as implemented here): each unit prompts independently
- **Batched**: a single approval screen listing all units needing approval
- **Parallel**: concurrent approval requests with aggregation

The sequential approach has poor UX for complex workflows (compliance officers would face many individual prompts). The batched approach requires a standard batch approval format not defined in the spec.

**Status:** Implementation-defined in v0.6. The simulator uses sequential HITL but flags this as a usability concern for real-world deployments.

## Tests

48 tests across 4 test classes:

- **ComplianceConfigTest** (6 tests): Parsing regulations, data residency, restrictions, empty compliance, full block
- **ComplianceEngineTest** (9 tests): EU/US residency checks, restriction queries, unknown units, config access
- **DelegationChainEngineTest** (10 tests): max_depth=0, depth exceeded, capability attenuation, sar-drafts depth=1/2, root fallback
- **AmlSimulatorIntegrationTest** (23 tests): All 7 phases, per-phase output verification, violation detection, audit trails, summary counts, stats accessors
