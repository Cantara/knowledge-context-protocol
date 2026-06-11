// kcp query — simulate agent search against a knowledge.yaml manifest

import type { KnowledgeManifest, KnowledgeUnit } from "./model.js";

interface SearchResult {
  id: string;
  intent: string;
  path: string;
  audience: string[];
  score: number;
  match_reason: string[];
  caution: string | null;
}

/**
 * Negative-space evaluation (§15.11): compare not_for entries against the
 * query terms (case-insensitive substring, same basis as scoring). Returns
 * the matched phrase, or null when no entry matches.
 */
export function matchNotFor(unit: KnowledgeUnit, terms: string[]): string | null {
  for (const phrase of unit.not_for ?? []) {
    const lower = phrase.toLowerCase();
    if (terms.some((t) => lower.includes(t.toLowerCase()))) return phrase;
  }
  return null;
}

/**
 * Score a unit against query terms.
 * Mirrors the bridge's scoreUnit function:
 * - trigger match: 5 pts per matching trigger
 * - intent match: 3 pts per term
 * - id/path match: 1 pt per term
 */
function scoreUnit(unit: KnowledgeUnit, terms: string[]): SearchResult {
  let score = 0;
  const matchReason = new Set<string>();
  const lowerTriggers = unit.triggers.map((t) => t.toLowerCase());
  const lowerIntent = unit.intent.toLowerCase();
  const lowerId = unit.id.toLowerCase();
  const lowerPath = unit.path.toLowerCase();

  for (const term of terms) {
    const lterm = term.toLowerCase();

    for (const trig of lowerTriggers) {
      if (trig.includes(lterm)) {
        score += 5;
        matchReason.add("trigger");
      }
    }
    if (lowerIntent.includes(lterm)) {
      score += 3;
      matchReason.add("intent");
    }
    if (lowerId.includes(lterm)) {
      score += 1;
      matchReason.add("id");
    }
    if (lowerPath.includes(lterm)) {
      score += 1;
      matchReason.add("path");
    }
  }

  return {
    id: unit.id,
    intent: unit.intent,
    path: unit.path,
    audience: unit.audience,
    score,
    match_reason: [...matchReason],
    caution: null,
  };
}

/**
 * Apply §15.11 negative-space filtering to scored results: evaluated after
 * scoring and before the top-N cut. Strict matches are excluded; soft
 * matches are demoted (halved, floored at 1) and annotated with `caution`.
 */
export function applyNotFor(
  units: KnowledgeUnit[],
  results: SearchResult[],
  terms: string[]
): SearchResult[] {
  const byId = new Map(units.map((u) => [u.id, u]));
  const out: SearchResult[] = [];
  for (const r of results) {
    const unit = byId.get(r.id);
    const matched = unit ? matchNotFor(unit, terms) : null;
    if (matched === null) {
      out.push(r);
    } else if (unit?.not_for_strict) {
      continue; // strict exclusion: as if it had not matched the query
    } else {
      out.push({
        ...r,
        score: Math.max(1, Math.floor(r.score / 2)),
        caution: `not_for match: '${matched}'`,
      });
    }
  }
  return out;
}

export function runQuery(manifest: KnowledgeManifest, query: string): void {
  const terms = query
    .toLowerCase()
    .split(/\s+/)
    .filter((t) => t.length > 2); // skip stopwords (a, an, is, ...)

  if (terms.length === 0) {
    process.stderr.write("Query too short — use at least one word with 3+ characters.\n");
    process.exit(1);
  }

  const scored = manifest.units
    .map((unit) => scoreUnit(unit, terms))
    .filter((r) => r.score > 0);
  // §15.11: not_for is evaluated after scoring, before the top-N cut.
  const results = applyNotFor(manifest.units, scored, terms)
    .sort((a, b) => b.score - a.score)
    .slice(0, 5);

  if (results.length === 0) {
    process.stdout.write(`No units matched "${query}".\n`);
    return;
  }

  const cyan = (s: string) => `\x1b[36m${s}\x1b[0m`;
  const dim = (s: string) => `\x1b[2m${s}\x1b[0m`;
  const bold = (s: string) => `\x1b[1m${s}\x1b[0m`;

  process.stdout.write(`\n${bold(`Search: "${query}"`)}\n`);
  process.stdout.write(dim(`Project: ${manifest.project} | ${results.length} result(s)\n\n`));

  for (let i = 0; i < results.length; i++) {
    const r = results[i];
    const rank = `${i + 1}.`;
    const reasons = r.match_reason.length > 0 ? dim(` [${r.match_reason.join(", ")}]`) : "";
    process.stdout.write(`${bold(rank)} ${cyan(r.id)}${reasons}\n`);
    process.stdout.write(`   ${r.intent}\n`);
    process.stdout.write(dim(`   ${r.path} | score: ${r.score} | audience: ${r.audience.join(", ") || "all"}\n`));
    if (r.caution) process.stdout.write(`   \x1b[33m⚠ ${r.caution}\x1b[0m\n`);
    process.stdout.write("\n");
  }
}
