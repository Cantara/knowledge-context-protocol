// imperative-lint-0.2 — rule set for RFC-0018 §6.2 (imperative-mood lint).
//
// Flags imperative constructions directed at the reader, plus embedded
// tool-invocation syntax. Deliberately does NOT attempt to catch
// descriptive-mood injection ("maintainers have found that tasks fail
// unless X runs first") — that case is covered by §6.4 data-framing.
//
// Rule sets are versioned and recorded in the render output so renders
// are reproducible (RFC-0018 §3.1, C1).

export const LINT_RULES_VERSION = "imperative-lint-0.2";

interface LintRule {
  id: string;
  re: RegExp;
}

const RULES: LintRule[] = [
  {
    id: "always-never-run",
    re: /\b(always|never)\s+(run|execute|invoke|call)\b/i,
  },
  {
    id: "you-must",
    re: /\byou\s+(must|should|need\s+to|have\s+to)\s+(run|execute|invoke|call|install|download)\b/i,
  },
  {
    id: "before-any-task",
    re: /\bbefore\s+(any|each|every|starting\s+a|your\s+first)\s+(task|request|action|work|session)\b[^.]{0,120}\b(run|execute|do|invoke|source)\b/i,
  },
  {
    id: "sentence-initial-imperative",
    // imperative verb opening the field or a sentence, followed by a
    // command-ish token (path, backtick, known tool)
    re: /(^|[.!?]\s+)(run|execute|invoke|source|curl|fetch|download|install)\s+(`|\.\/|\/|~\/|[a-z0-9_-]+\.(sh|py|js)\b|mvn\b|npm\b|pip\b|bash\b|sh\b)/i,
  },
  {
    id: "shell-chain",
    re: /\$\(|`[^`]*\|\s*(bash|sh|zsh)\b|\|\s*(bash|sh|zsh)\b|&&\s*(curl|wget|bash|sh)\b/i,
  },
];

export interface LintVerdict {
  flagged: boolean;
  rule?: string;
}

export function lintFreeText(text: unknown): LintVerdict {
  if (typeof text !== "string") return { flagged: false };
  for (const rule of RULES) {
    if (rule.re.test(text)) return { flagged: true, rule: rule.id };
  }
  return { flagged: false };
}
