#!/usr/bin/env bash
# KCP ecosystem installer
#
# Installs: kcp-commands, kcp-memory, kcp-dashboard
#
# Usage (curl | bash — no clone needed):
#   curl -fsSL https://raw.githubusercontent.com/Cantara/knowledge-context-protocol/main/bin/install.sh | bash
#   curl -fsSL ... | bash -s -- --java          # kcp-commands: Java daemon (recommended, requires Java 21)
#   curl -fsSL ... | bash -s -- --node          # kcp-commands: Node.js daemon (no JVM required)
#   curl -fsSL ... | bash -s -- --no-dashboard  # skip kcp-dashboard
#
# Re-running upgrades an existing installation.

set -euo pipefail

# ── Parse args ────────────────────────────────────────────────────────────────

MODE=""
SKIP_DASHBOARD=false

for arg in "$@"; do
  case $arg in
    --java)            MODE="java" ;;
    --node|--nodejs)   MODE="node" ;;
    --no-dashboard)    SKIP_DASHBOARD=true ;;
  esac
done

# ── Interactive backend prompt (if no flag given) ─────────────────────────────

if [ -z "$MODE" ]; then
  echo "KCP ecosystem installer"
  echo "========================"
  echo ""
  echo "kcp-commands backend:"
  echo "  1) Java daemon  — ~12 ms/hook call, requires Java 21  [recommended]"
  echo "  2) Node.js      — ~250 ms/hook call, requires Node.js only"
  echo ""
  read -rp "Choice [1/2, default: 1]: " choice
  case "${choice:-1}" in
    2|n|N|node|nodejs) MODE="node" ;;
    *)                 MODE="java" ;;
  esac
fi

echo ""
echo "KCP ecosystem installer"
echo "========================"
echo "  kcp-commands backend : $MODE"
echo "  kcp-dashboard        : $([ "$SKIP_DASHBOARD" = true ] && echo 'skip' || echo 'yes')"
echo ""

# ── 1. kcp-commands ───────────────────────────────────────────────────────────

echo "[ 1/$([ "$SKIP_DASHBOARD" = true ] && echo 2 || echo 3) ] kcp-commands (${MODE} backend)..."
echo ""
curl -fsSL \
  https://raw.githubusercontent.com/Cantara/kcp-commands/main/bin/install.sh \
  | bash -s -- "--${MODE}"
echo ""

# ── 2. kcp-memory ─────────────────────────────────────────────────────────────

echo "[ 2/$([ "$SKIP_DASHBOARD" = true ] && echo 2 || echo 3) ] kcp-memory..."
echo ""
curl -fsSL \
  https://raw.githubusercontent.com/Cantara/kcp-memory/main/bin/install.sh \
  | bash
echo ""

# ── 3. kcp-dashboard ──────────────────────────────────────────────────────────

if [ "$SKIP_DASHBOARD" = false ]; then
  echo "[ 3/3 ] kcp-dashboard..."
  echo ""
  curl -fsSL \
    https://raw.githubusercontent.com/Cantara/kcp-dashboard/main/bin/install.sh \
    | bash
  echo ""
fi

# ── Done ──────────────────────────────────────────────────────────────────────

echo "=========================================="
echo "KCP installed. Next steps:"
echo ""
echo "  1. Restart Claude Code   # hooks activate on restart"
echo "  2. kcp-memory scan       # index existing session history"
echo "  3. kcp stats             # verify kcp-commands is active"
if [ "$SKIP_DASHBOARD" = false ]; then
echo "  4. kcp-dashboard         # open the live TUI"
fi
echo ""
echo "Docs: https://github.com/Cantara/knowledge-context-protocol"
