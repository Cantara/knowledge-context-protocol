#!/usr/bin/env bash
# KCP Multi-Language Interoperability Test
#
# Tests that the TypeScript and Python MCP bridges expose identical
# resources (list + read) for the same manifest.
#
# Prerequisites:
#   - Node.js >= 18
#   - Python >= 3.10
#   - npm dependencies installed in bridge/typescript
#   - Python kcp and kcp_mcp packages installed
#
# Usage: ./run-interop.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
MANIFEST="$SCRIPT_DIR/knowledge.yaml"

echo "=== KCP Interoperability Test ==="
echo "Manifest: $MANIFEST"
echo ""

# --- Check prerequisites ---

if ! command -v node &>/dev/null; then
  echo "ERROR: node not found"; exit 1
fi
if ! command -v python3 &>/dev/null; then
  echo "ERROR: python3 not found"; exit 1
fi

# --- Build TypeScript bridge if needed ---

TS_BRIDGE="$REPO_ROOT/bridge/typescript"
if [ ! -f "$TS_BRIDGE/dist/cli.js" ]; then
  echo "Building TypeScript bridge..."
  (cd "$TS_BRIDGE" && npm install && npm run build)
fi

# --- Install Python bridge if needed ---

PY_BRIDGE="$REPO_ROOT/bridge/python"
if ! python3 -c "import kcp_mcp" 2>/dev/null; then
  echo "Installing Python bridge..."
  pip install -e "$REPO_ROOT/parsers/python" -q
  pip install -e "$PY_BRIDGE" -q
fi

# --- Install TS client dependencies ---

TS_CLIENT="$SCRIPT_DIR/ts-client"
if [ ! -d "$TS_CLIENT/node_modules" ]; then
  echo "Installing TS client dependencies..."
  (cd "$TS_CLIENT" && npm install --ignore-scripts 2>/dev/null)
fi

# --- Run TypeScript client ---

echo ""
echo "--- Running TypeScript client ---"
(cd "$TS_CLIENT" && npx tsx interop_client.ts "$MANIFEST" "$SCRIPT_DIR/ts-results.json")

# --- Run Python client ---

echo ""
echo "--- Running Python client ---"
python3 "$SCRIPT_DIR/python-client/interop_client.py" "$MANIFEST" "$SCRIPT_DIR/python-results.json"

# --- Assert parity ---

echo ""
echo "--- Asserting parity ---"
python3 "$SCRIPT_DIR/assert_parity.py" "$SCRIPT_DIR/ts-results.json" "$SCRIPT_DIR/python-results.json"

# --- Cleanup ---
rm -f "$SCRIPT_DIR/ts-results.json" "$SCRIPT_DIR/python-results.json"
echo ""
echo "=== Interoperability test complete ==="
