# With KCP — Enhanced

This directory contains the **same** Node.js API project, but with a
`knowledge.yaml` manifest that provides intent, triggers, and dependency
information for each source file.

When an agent has access to a KCP-aware MCP bridge, it can match queries
to the right file immediately using trigger phrases and intent descriptions.

## Instructions for recording

1. Open a fresh agent session with the KCP MCP bridge connected
2. Point the agent at `sample-repo/`
3. Ask each query from `../without-kcp/test-queries.md` (same queries!)
4. For each query, note:
   - How many files the agent read
   - How many tool calls it made
   - How many seconds until the correct answer appeared
5. Record the screen the entire time

## Expected behaviour

The agent will typically:
- Query the KCP bridge with the natural language question
- Get back the exact file to read (via trigger matching)
- Read only 1 file per question
- Take 1-2 tool calls per question
- Total time: 15-30 seconds for all 5 questions

## Key difference

Without KCP: agent searches blindly, reads 3-5 files per question.
With KCP: agent gets directed to the right file immediately, reads 1 file per question.
