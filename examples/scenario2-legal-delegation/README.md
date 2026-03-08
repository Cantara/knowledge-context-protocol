# Scenario 2: Legal Document Review (3-Hop Delegation Chain)

A three-agent simulation demonstrating delegation chains, capability attenuation, `max_depth` enforcement, and no-delegation units in a legal document review domain.

## Domain Context

A law firm operates a document review pipeline where a Case Orchestrator Agent coordinates with a Legal Research Agent, which in turn delegates sub-tasks to an External Case Law Agent. The simulation exercises the full delegation depth chain: from the resource owner (depth=0) through a first-hop delegatee (depth=1) to a third-party external agent (depth=2), with escalating restrictions at each level.

## Agent Topology

```
  +---------------------------+          +---------------------------+
  | CaseOrchestratorAgent     |  A2A     | LegalResearchAgent        |
  | (depth=1 delegatee)       | -------> | (resource owner, depth=0) |
  +---------------------------+   KCP    +---------------------------+
                                               |
                                               | delegates (depth=2)
                                               v
                                         +---------------------------+
                                         | ExternalCaseLawAgent      |
                                         | (depth=2 delegatee)       |
                                         +---------------------------+
```

## KCP Manifest Summary

| Unit                    | access          | sensitivity    | auth_scope     | HITL     | delegation max_depth |
|-------------------------|-----------------|----------------|----------------|----------|----------------------|
| public-precedents       | public          | public         | --             | no       | 3 (root)             |
| case-briefs             | authenticated   | internal       | read:case      | no       | 3 (root)             |
| client-communications   | restricted      | confidential   | attorney       | required | 2 (unit override)    |
| sealed-records          | restricted      | restricted     | court-officer  | no       | 0 (NO delegation)    |

Root delegation: `max_depth: 3`, `require_capability_attenuation: true`, `audit_chain: true`

## Key Delegation Behaviours Demonstrated

| Behaviour | Where | Outcome |
|-----------|-------|---------|
| `max_depth: 0` = absolute no-delegation | Phase 1, step 1c (sealed-records) | DENIED: orchestrator at depth=1, but unit forbids all delegation |
| Capability attenuation enforcement | Phase 2, step 2b (case-briefs) | BLOCKED: same scope `read:case` passed to delegatee |
| Narrowed scope accepted | Phase 2, step 2b-fix (case-briefs) | DELEGATED: `read:case` narrowed to `read:case:external-summary` |
| Exact depth limit | Phase 2, step 2c (client-communications) | DELEGATED at depth=2 (= max_depth=2), with HITL gate |
| Depth exceeded | Phase 2, step 2d (client-communications) | BLOCKED: depth=3 exceeds max_depth=2 |

## How to Build and Run

```bash
cd examples/scenario2-legal-delegation/simulator

# Run tests
mvn test

# Build fat jar
mvn package -DskipTests

# Run simulation (auto-approve HITL)
java -jar target/kcp-scenario2-legal-delegation-0.1.0-jar-with-dependencies.jar --auto-approve
```

## Sample Output (key lines)

```
=== SCENARIO 2: Legal Document Review (3-Hop Delegation) ===
Agents: CaseOrchestratorAgent -> LegalResearchAgent -> ExternalCaseLawAgent

-- Phase 1: Direct Access (CaseOrchestrator -> LegalResearch, depth=1) -----

[1a]   Requesting unit: public-precedents
[KCP]  Result: LOADED

[1b]   Requesting unit: case-briefs
[KCP]  Result: LOADED

[1c]   Requesting unit: sealed-records (depth=1, orchestrator is delegatee)
[KCP]  Delegation check: max_depth=0 for this unit
[KCP]  Result: DENIED (Unit has max_depth=0: delegation is not permitted)
[AUDIT] VIOLATION: delegation attempt on no-delegation unit 'sealed-records'

-- Phase 2: Delegation Chain (LegalResearch -> ExternalCaseLaw, depth=2) ---

[2b]   Delegating 'case-briefs' to ExternalCaseLawAgent with scope 'read:case'
[KCP]  Result: BLOCKED (Capability attenuation required: delegated scope must be narrower)
[AUDIT] ATTENUATION VIOLATION: same-scope delegation blocked

[2b-fix] Re-delegating 'case-briefs' with narrowed scope 'read:case:external-summary'
[KCP]  Result: DELEGATED (attenuated scope, depth=2)

[2c]   Delegating 'client-communications' to ExternalCaseLawAgent (depth=2)
[KCP]  Human-in-the-loop: REQUIRED (approval_mechanism: manual)
[KCP]  Result: DELEGATED (after human approval, depth=2)

[2d]   Hypothetical: delegate 'client-communications' to depth=3
[KCP]  Result: BLOCKED (Depth 3 exceeds max_depth=2)
[AUDIT] DEPTH VIOLATION: depth=3 exceeds max_depth=2 for 'client-communications'

=== SIMULATION SUMMARY ===
  Units loaded successfully:  5
  Access denied (policy):     1
  Access denied (delegation): 2
  HITL approvals:             1
  Audit entries:              9
```

## Spec Gaps Surfaced

### 1. Capability attenuation is declarative, not mechanical (v0.6 gap)

`require_capability_attenuation: true` declares that a delegated agent MUST receive a narrower scope than the delegating agent held. However, the spec does not define:

- How the receiving agent **verifies** that the presented token has narrower scope than the delegating agent held (it would need to see both tokens)
- What "narrower" means formally (is `read:case:external-summary` narrower than `read:case`? by what rule?)
- Whether scope hierarchy is lexicographic, prefix-based, or defined by a separate scope ontology

**Status:** v0.7 gap. The simulator adopts a simple "not-equal means narrower" heuristic, which is clearly insufficient for production systems.

**Recommendation:** Define a scope comparison function or require scope registries to declare parent-child relationships.

### 2. Depth counting ambiguity (v0.6 gap)

The spec does not define whether the resource-owning agent is at depth=0 or depth=1 in the delegation chain. This matters because `max_depth: 2` could mean either:

- **Owner=0 convention:** Owner at depth=0, first delegatee at depth=1, second at depth=2 (3 agents in chain)
- **Owner=1 convention:** Owner at depth=1, first delegatee at depth=2 (only 2 agents allowed)

The simulator adopts **Owner=0**: the resource owner is depth=0, and the first external caller is depth=1. This is flagged as needing a normative definition.

**Recommendation:** Add a one-line normative statement: "The resource owner operates at depth 0. The first agent to which access is delegated operates at depth 1."

### 3. `max_depth: 0` interpretation (v0.6 gap)

`max_depth: 0` is used in this scenario to mean "this unit cannot be delegated at all -- only the resource owner may access it." However, the spec does not explicitly state whether `max_depth: 0` means:

- (a) "No delegation ever -- only the owner may access" (the simulator's interpretation)
- (b) "Delegation to depth 0 only" (which is equivalent to (a) under the owner=0 convention, but ambiguous under owner=1)

These are equivalent under the owner=0 convention adopted here, but the spec should state this explicitly to avoid implementer confusion.

**Recommendation:** Add a note: "`max_depth: 0` means no delegation is permitted for this unit. Only the resource owner may access it."

## Tests

36 tests across 3 test classes:

- **DelegationChainEngineTest** (14 tests): max_depth=0 blocking, depth exceeded, capability attenuation (same scope blocked, narrowed allowed, null scope blocked), isDelegatable checks, root fallback
- **AgentCardParserTest** (5 tests): Legal agent card parsing, skills, scopes, provider
- **LegalSimulatorIntegrationTest** (17 tests): Full 2-phase simulation, per-step output verification, delegation blocking, HITL gating, audit trail, summary counts, stats accessors
