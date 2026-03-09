# Multi-Language Interoperability Test

Verifies that the TypeScript and Python MCP bridges expose identical
resources when given the same `knowledge.yaml` manifest.

## How it works

1. A shared `knowledge.yaml` defines 3 units with triggers, dependencies, and relationships
2. A TypeScript client connects to the TS bridge via stdio, lists all resources, reads each one
3. A Python client connects to the Python bridge via stdio, lists all resources, reads each one
4. `assert_parity.py` compares the results: same URIs, names, and content must appear

## What is compared

| Check | Description |
|-------|-------------|
| Resource count | Both bridges expose the same number of resources |
| URIs | Every URI from TS must appear in Python and vice versa |
| Names | Resource names must match |
| Content | File content (markdown) must be identical; manifest JSON is compared structurally |

## Running

```bash
# One-liner (handles all setup)
./run-interop.sh

# Or step by step:

# 1. Build TS bridge
cd ../../bridge/typescript && npm install && npm run build && cd -

# 2. Install Python bridge
pip install -e ../../parsers/python
pip install -e ../../bridge/python

# 3. Run TS client
cd ts-client && npx tsx interop_client.ts ../knowledge.yaml ../ts-results.json && cd ..

# 4. Run Python client
python3 python-client/interop_client.py knowledge.yaml python-results.json

# 5. Compare
python3 assert_parity.py ts-results.json python-results.json
```

## Expected output

```
  PASS  resource_count: 4
  PASS  knowledge://interop-test/auth-guide
  PASS  knowledge://interop-test/env-config
  PASS  knowledge://interop-test/manifest
  PASS  knowledge://interop-test/rate-limiting

Results: 5 passed, 0 failed
All resources match across TypeScript and Python implementations.
```

## CI

The interop test runs in `.github/workflows/interop.yml` on every push to main.
