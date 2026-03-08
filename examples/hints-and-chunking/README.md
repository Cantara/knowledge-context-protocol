# Hints and Chunking Example

Demonstrates the context window hints system from SPEC.md section 4.10.

## What this shows

### Summary relationships

The `architecture-overview` unit (18,000 tokens) has a shorter `architecture-tldr` (500 tokens).
The pair is linked via:

- `summary_available: true` + `summary_unit: architecture-tldr` on the full document
- `summary_of: architecture-overview` on the TL;DR

Agents should load the TL;DR eagerly and only fetch the full version when they need detail.

### Chunking

The `api-reference` unit (20,000 tokens) is split into 3 sequential chunks.
The parent declares:

- `chunked: true` and `chunk_count: 3`
- `load_strategy: never` (agents should load chunks instead)

Each chunk declares:

- `chunk_of: api-reference` (parent reference)
- `chunk_index: 1|2|3` (1-based position)
- `total_chunks: 3` (mirrors parent's chunk_count)
- `chunk_topic: "..."` (short description of what this chunk covers)

### Load strategy values

| Value | Meaning |
|-------|---------|
| `eager` | Load immediately into context window |
| `lazy` | Load on demand (default) |
| `never` | Only load if explicitly requested (e.g. chunked parent) |

### Priority values

| Value | Meaning |
|-------|---------|
| `critical` | Evict last when context budget is tight |
| `supplementary` | Default eviction priority |
| `reference` | Evict first |
