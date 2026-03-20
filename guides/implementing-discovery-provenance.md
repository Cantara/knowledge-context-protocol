# Implementing RFC-0012 Discovery Provenance

Practical guide from building the first automated RFC-0012 implementation in [kcp-triage](https://github.com/StigLau/kcp-triage).

## The confidence mapping problem

Automated generators have heterogeneous data sources with different certainty levels. The key design decision is mapping internal confidence representations to RFC-0012's `verification_status` + `confidence` pair.

### Recommended mapping pattern

Define a mapping table from your pipeline's internal confidence taxonomy to discovery fields:

| Internal signal | → `verification_status` | → `source` | → `confidence` |
|-----------------|------------------------|------------|----------------|
| API confirmed by live call | `verified` | `web_traversal` | 0.95 |
| API inferred from JS bundles | `observed` | `web_traversal` | 0.70 |
| API mentioned in page text | `rumored` | `llm_inference` | 0.40 |
| Feature from marketing copy | `rumored` | `llm_inference` | 0.30 |
| Crawled page structure | `observed` | `web_traversal` | 0.85–0.95 |
| LLM-synthesized skills | `observed` | `llm_inference` | parent × 0.9 |

### Confidence inheritance

Skills and generated artifacts derive confidence from their source data. A useful pattern: multiply the parent classification's confidence by a discount factor (e.g., 0.9) to reflect the additional inference step.

## Root-level defaults reduce repetition

Most automated generators produce units with the same `source` and `verification_status`. Use root-level `discovery` defaults and override only where units differ:

```yaml
discovery:
  source: web_traversal
  verification_status: observed
  observed_at: "2026-03-20T10:00:00Z"

units:
  - id: crawled-page
    discovery:
      confidence: 0.85          # inherits source + status from root

  - id: llm-inferred-skill
    discovery:
      source: llm_inference      # overrides root source
      confidence: 0.72
```

## Normative rules to enforce in code

Two rules are easy to violate in automated generators:

1. **`rumored` → `confidence < 0.5`**: Test this as an invariant. If your LLM assigns high confidence to something you've classified as rumored, cap it or reclassify.

2. **`verified_at` must be null for non-verified**: Only set `verified_at` when `verification_status` is `verified`. A common bug is setting it to `observed_at` for all units.

## What we learned building kcp-triage

**Discovery is deterministic, not an LLM step.** The discovery block is metadata *about* how knowledge was acquired, not knowledge itself. It should be computed from pipeline data without an additional LLM call.

**The `contradicted_by` field is valuable but requires version tracking.** When re-crawling a site, you may find that an API endpoint has moved. Preserving both versions with `contradicted_by` is more useful than silently replacing — agents can then ask the user which is current.

**`deprecated` status needs an external signal.** Automated generators rarely discover deprecation on their own. Consider supporting a manual override file where operators can mark units as deprecated between crawls.

## Reference implementation

- **Repository:** [StigLau/kcp-triage](https://github.com/StigLau/kcp-triage)
- **Generator:** `src/generators/kcp-manifest.ts`
- **Schema:** `src/schemas/triage.ts` → `DiscoveryBlockSchema`
- **Tests:** `tests/kcp-manifest.test.ts` — covers all mappings and normative rules
