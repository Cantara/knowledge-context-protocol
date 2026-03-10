# Scenario 6: Federation Resolution Simulation

A developer agent working on a payments platform resolves knowledge from three
separate team manifests using KCP v0.9 federation (SPEC.md section 3.6). The
simulation walks through the full resolution lifecycle: discovering sub-manifests,
ordering them by relationship type, handling failures gracefully, resolving
cross-manifest dependencies, and producing `list_manifests` tool output. Federation
enables organisations to split knowledge ownership across teams while preserving a
single, navigable knowledge graph for agents.

## Topology

```
                    payments-gateway (root)
                   /        |          \
            foundation    child       archive
               |            |            |
       compliance-core  fraud-detection  legacy-billing
       (3 units)        (3 units,        (2 units,
                         UNAVAILABLE)     SKIPPED)
```

Resolution order:
1. `compliance-core` (foundation) -- loaded via `local_mirror`
2. `fraud-detection` (child) -- remote unavailable, degraded
3. `legacy-billing` (archive) -- skipped by agent

## Learning objectives

1. **DAG resolution**: Agent loads the root manifest, discovers the `manifests:` block, and resolves sub-manifests in dependency order (foundation before child, archive last).
2. **`on_failure: degrade` semantics**: When `fraud-detection` is unreachable, the agent continues with degraded capability instead of failing entirely.
3. **`local_mirror` fallback**: `compliance-core` has a `local_mirror` path; the agent loads from the local copy without fetching the remote URL (per SPEC section 3.6 resolution order).
4. **`archive` skipping**: `legacy-billing` has `relationship: archive`; the agent logs its existence but does not load it.
5. **`external_depends_on` resolution**: Units in `payments-gateway` declare cross-manifest dependencies on units in `compliance-core` and `fraud-detection`, with varying `on_failure` behaviour.
6. **`governs` external relationship**: `compliance-core/pci-dss-controls` governs `payments-gateway/payment-processing` via the `external_relationships` block.
7. **`list_manifests` tool output**: The simulation formats the federation state as structured JSON, matching the MCP tool response format.

## How to run

### Option A: JBang (recommended)

```bash
cd examples/scenario6-federation-resolution
jbang FederationResolutionSimulation.java
```

### Option B: Manual compile

```bash
cd examples/scenario6-federation-resolution

# Compile (requires snakeyaml-2.6.jar on classpath)
javac -cp ~/.m2/repository/org/yaml/snakeyaml/2.6/snakeyaml-2.6.jar \
    FederationResolutionSimulation.java

# Run
java -cp .:~/.m2/repository/org/yaml/snakeyaml/2.6/snakeyaml-2.6.jar \
    FederationResolutionSimulation
```

### Option C: Specify directory

```bash
java -cp .:snakeyaml-2.6.jar FederationResolutionSimulation \
    --dir /path/to/scenario6-federation-resolution
```

## Expected output

```
=== SCENARIO 6: Federation Resolution Simulation ===
Root manifest: ./knowledge.yaml

--- Step 1: Parse Root Manifest ---
  Project: payments-gateway  Version: 3.1.0  KCP: 0.9

--- Step 2: Discover Sub-Manifests (3) ---
  compliance-core      relationship=foundation   url=https://governance.clearpay.example.com/compliance/knowledge.yaml  local_mirror=./sub-manifests/compliance-core.yaml
  fraud-detection      relationship=child        url=https://fraud-team.clearpay.example.com/knowledge.yaml
  legacy-billing       relationship=archive      url=https://archive.clearpay.example.com/billing-v1/knowledge.yaml

--- Step 3: DAG Resolution Order ---
  1. compliance-core      [foundation] LOAD
  2. fraud-detection      [child] LOAD
  3. legacy-billing       [archive] SKIP (archive)

--- Step 4: Resolve Sub-Manifests ---
  [MIRROR] compliance-core — local_mirror exists at ./sub-manifests/compliance-core.yaml, loading from mirror
           (Per SPEC section 3.6: when local_mirror exists, URL is NOT fetched)
  [OK]    compliance-core — loaded 3 units (project=compliance-core, version=2.4.0)
  [FETCH] fraud-detection — attempting https://fraud-team.clearpay.example.com/knowledge.yaml
  [FAIL]  fraud-detection — remote URL unreachable (simulated network error)
  [DEGRADE] fraud-detection — no local_mirror, applying on_failure: degrade
  [SKIP] legacy-billing — relationship=archive, agent skips loading

--- Step 5: Resolve external_depends_on ---
  payment-processing -> compliance-core/pci-dss-controls : RESOLVED (unit found in loaded manifest)
  payment-processing -> fraud-detection/risk-scoring : DEGRADED — manifest unavailable, agent operates with incomplete dependencies
  merchant-onboarding -> compliance-core/kyc-procedures : RESOLVED (unit found in loaded manifest)

--- Step 6: External Relationships ---
  compliance-core/pci-dss-controls -[governs]-> root/payment-processing : ACTIVE
  compliance-core/kyc-procedures -[governs]-> root/merchant-onboarding : ACTIVE
  fraud-detection/risk-scoring -[enables]-> root/payment-processing : PARTIAL (from unavailable)

--- Step 7: list_manifests Tool Output ---
  {
    "tool": "list_manifests",
    "result": {
      "root_project": "payments-gateway",
      "root_version": "3.1.0",
      "manifests": [
        {
          "id": "compliance-core",
          "label": "Compliance Core — Shared Governance",
          "relationship": "foundation",
          "url": "https://governance.clearpay.example.com/compliance/knowledge.yaml",
          "local_mirror": "./sub-manifests/compliance-core.yaml",
          "status": "loaded"
        },
        {
          "id": "fraud-detection",
          "label": "Fraud Detection Sub-System",
          "relationship": "child",
          "url": "https://fraud-team.clearpay.example.com/knowledge.yaml",
          "status": "degraded"
        },
        {
          "id": "legacy-billing",
          "label": "Legacy Billing (v1, retired)",
          "relationship": "archive",
          "url": "https://archive.clearpay.example.com/billing-v1/knowledge.yaml",
          "status": "skipped"
        }
      ]
    }
  }

=== SUMMARY ===
  Loaded 6 units from 2 manifest(s), skipped 1 archive manifest(s), degraded on 1 manifest(s)

  Loaded manifests:
    payments-gateway     3 unit(s)  [root]
    compliance-core      3 unit(s)  [foundation]
  Degraded manifests:
    fraud-detection (on_failure: degrade)
  Skipped manifests:
    legacy-billing (archive)
```

## Key YAML patterns

### 1. Federation block with local_mirror

```yaml
# Root manifest declares sub-manifests with relationship types
manifests:
  - id: compliance-core
    url: "https://governance.clearpay.example.com/compliance/knowledge.yaml"
    label: "Compliance Core — Shared Governance"
    relationship: foundation          # loaded first
    update_frequency: weekly
    local_mirror: "./sub-manifests/compliance-core.yaml"  # air-gapped fallback
```

When `local_mirror` is present and the file exists, the parser MUST load from
that path instead of fetching the URL (SPEC section 3.6).

### 2. Archive manifest (agent skips)

```yaml
  - id: legacy-billing
    url: "https://archive.clearpay.example.com/billing-v1/knowledge.yaml"
    label: "Legacy Billing (v1, retired)"
    relationship: archive             # agents MAY skip unless specifically requested
    update_frequency: never
```

### 3. external_depends_on with on_failure

```yaml
units:
  - id: payment-processing
    external_depends_on:
      - manifest: compliance-core     # references manifests[].id
        unit: pci-dss-controls        # unit id in the remote manifest
        on_failure: degrade           # agent operates with incomplete dependencies
      - manifest: fraud-detection
        unit: risk-scoring
        on_failure: degrade           # fraud-detection is unavailable -> degraded
```

`on_failure` values: `skip` (default, silent), `warn` (log warning), `degrade`
(agent indicates output has incomplete dependencies).

### 4. external_relationships (governs)

```yaml
external_relationships:
  # compliance-core governs payment-processing
  - from_manifest: compliance-core
    from_unit: pci-dss-controls
    to_unit: payment-processing       # omitting to_manifest = this manifest
    type: governs                     # same vocabulary as section 5 relationships
```

`governs` means the source unit contains authoritative policies that constrain
the target unit. This is how a central compliance team can express governance
over domain-specific manifests.

### 5. Compliance block on sub-manifest

```yaml
# compliance-core.yaml — sub-manifest with compliance metadata
compliance:
  sensitivity: confidential
  data_residency: [EU, EEA]
  regulations: [PCI-DSS, GDPR, PSD2]
  restrictions:
    - "Do not cache compliance content beyond session"
    - "Log all access for audit trail"
```

Sub-manifests can carry their own `compliance`, `auth`, and `rate_limits` blocks.
These are authoritative for the sub-manifest's own units, not inherited by the
declaring manifest.

### 6. Resolution order by relationship

| Priority | Relationship | Agent behaviour |
|----------|-------------|-----------------|
| 1 | `foundation` | Load first -- other manifests build on this knowledge |
| 2 | `governs` | Load next -- governance constraints apply to dependent manifests |
| 3 | `child` / `peer` | Load after foundations are available |
| 4 | `archive` | Skip unless the user specifically requests historical knowledge |
