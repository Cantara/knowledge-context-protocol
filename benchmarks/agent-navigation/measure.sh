#!/usr/bin/env bash
# Optional: parse agent tool call logs to count file reads per query.
#
# This script is a template. Different agents produce logs in different formats.
# Adapt the parsing logic to your agent's output format.
#
# Usage: ./measure.sh <agent-log-file>

set -euo pipefail

LOG_FILE="${1:-}"
if [ -z "$LOG_FILE" ]; then
  echo "Usage: ./measure.sh <agent-log-file>"
  echo ""
  echo "Counts file read operations and tool calls from an agent session log."
  echo "Adapt the grep patterns below to match your agent's log format."
  exit 1
fi

echo "=== Agent Navigation Benchmark Metrics ==="
echo "Log: $LOG_FILE"
echo ""

# Count file reads (adapt pattern to your agent)
FILE_READS=$(grep -c -i "read_file\|ReadFile\|cat \|Read(" "$LOG_FILE" 2>/dev/null || echo 0)
echo "Total file reads: $FILE_READS"

# Count tool calls
TOOL_CALLS=$(grep -c -i "tool_call\|ToolCall\|function_call" "$LOG_FILE" 2>/dev/null || echo 0)
echo "Total tool calls: $TOOL_CALLS"

# Count directory listings
DIR_LISTS=$(grep -c -i "list_dir\|ListDir\|ls \|Glob(" "$LOG_FILE" 2>/dev/null || echo 0)
echo "Directory listings: $DIR_LISTS"

# Count search/grep operations
SEARCHES=$(grep -c -i "search\|grep\|Grep(" "$LOG_FILE" 2>/dev/null || echo 0)
echo "Search operations: $SEARCHES"

echo ""
echo "Efficiency ratio: $FILE_READS file reads / 5 queries = $(echo "scale=1; $FILE_READS / 5" | bc) reads/query"
