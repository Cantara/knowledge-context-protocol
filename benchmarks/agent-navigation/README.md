# Agent Navigation Benchmark

Infrastructure for recording a before/after screencast showing how KCP
improves agent codebase navigation efficiency.

## Structure

```
agent-navigation/
  without-kcp/
    README.md              — instructions for the "before" recording
    test-queries.md        — 5 specific queries to ask the agent
    sample-repo/           — small Node.js API (no knowledge.yaml)
  with-kcp/
    README.md              — instructions for the "after" recording
    sample-repo/           — same files PLUS knowledge.yaml
    knowledge.yaml         — maps each file to intent + triggers
  measure.sh               — parse agent logs for tool call counts
  RECORDING-SCRIPT.md      — exact narration script for the screencast
```

## The sample project

A "Bookshelf API" — a minimal Node.js REST API with:

| File | Purpose |
|------|---------|
| `src/server.js` | Express app, 3 endpoints (health, list books, create book) |
| `src/auth.js` | JWT authentication middleware |
| `src/middleware.js` | Per-IP rate limiter with 429 + Retry-After |
| `config/settings.js` | All environment variables and defaults |
| `README.md` | Deliberately minimal (forces agent to read source) |

## The 5 test queries

1. "How do I authenticate with this API?"
2. "What environment variables does this project need?"
3. "Where is the rate limiting configured and what are the defaults?"
4. "How do I create a new book via the API?"
5. "What happens when I send a request without authentication?"

## Expected results

| Metric | Without KCP | With KCP | Improvement |
|--------|-------------|----------|-------------|
| File reads per query | 3-5 | 1 | 3-5x fewer |
| Tool calls per query | 3-6 | 1-2 | 3x fewer |
| Time for 5 queries | ~60s | ~15s | 4x faster |
| Answer accuracy | Correct | Correct | Same |

## How to record

See `RECORDING-SCRIPT.md` for the complete step-by-step narration script
including what to say, what to type, and when to pause.

## How to measure

If your agent logs tool calls to a file:

```bash
./measure.sh agent-session.log
```

This counts file reads, directory listings, and search operations.
