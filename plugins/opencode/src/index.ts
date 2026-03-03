// opencode-kcp-plugin
// Reduces explore-agent tool calls by injecting a knowledge.yaml knowledge map
// into the system prompt and annotating glob/grep results with KCP intent strings.
//
// Hooks used:
//   experimental.chat.system.transform — prepend codebase knowledge map to system prompt
//   tool.execute.after (glob, grep)     — annotate matching file paths with KCP intent

import type { Plugin } from "@opencode-ai/plugin"
import { loadManifest, buildSystemSection, findMatchingUnit } from "./kcp.js"

const KcpPlugin: Plugin = async (ctx) => {
  const manifest = loadManifest(ctx.directory)

  // No knowledge.yaml found — register nothing, zero overhead
  if (!manifest) return {}

  const systemSection = buildSystemSection(manifest)

  return {
    // ── System prompt ──────────────────────────────────────────────────────────
    // Inject the knowledge map at the start of every session. The agent sees the
    // full file index with intent descriptions and trigger keywords before it
    // starts any search. This is the primary source of the 73-80% tool call reduction.
    "experimental.chat.system.transform": async (_input, output) => {
      output.system.push(systemSection)
    },

    // ── Glob / grep annotation ─────────────────────────────────────────────────
    // After glob or grep returns results, annotate lines that match KCP units with
    // their intent string. The agent sees:
    //   packages/opencode/src/agent/agent.ts  # KCP: How agents are defined...
    // instead of just the bare path.
    "tool.execute.after": async (input, output) => {
      if (input.tool !== "glob" && input.tool !== "grep") return
      if (!output.output) return

      const lines = output.output.split("\n")
      let annotated = false

      const result = lines.map(line => {
        const unit = findMatchingUnit(manifest, line)
        if (!unit) return line
        annotated = true
        return `${line}  # KCP: ${unit.intent}`
      })

      if (annotated) output.output = result.join("\n")
    },
  }
}

export default KcpPlugin
export { KcpPlugin }
