# Without KCP — Baseline

This directory contains a small Node.js API project with **no knowledge.yaml**.

The agent must discover the codebase structure by reading files, searching
for patterns, and navigating the directory tree manually.

## Instructions for recording

1. Open a fresh agent session (Claude Code, Cursor, Copilot, etc.)
2. Point the agent at `sample-repo/`
3. Ask each query from `test-queries.md` one at a time
4. For each query, note:
   - How many files the agent read
   - How many tool calls it made
   - How many seconds until the correct answer appeared
5. Record the screen the entire time

## Expected behaviour

The agent will typically:
- Read README.md first (minimal help)
- List directory contents
- Read each source file searching for the answer
- Take 3-6 tool calls per question
- Total time: 60-120 seconds for all 5 questions
