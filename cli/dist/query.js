// kcp query — simulate agent search against a knowledge.yaml manifest
/**
 * Score a unit against query terms.
 * Mirrors the bridge's scoreUnit function:
 * - trigger match: 5 pts per matching trigger
 * - intent match: 3 pts per term
 * - id/path match: 1 pt per term
 */
function scoreUnit(unit, terms) {
    let score = 0;
    const matchReason = new Set();
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
    };
}
export function runQuery(manifest, query) {
    const terms = query
        .toLowerCase()
        .split(/\s+/)
        .filter((t) => t.length > 2); // skip stopwords (a, an, is, ...)
    if (terms.length === 0) {
        process.stderr.write("Query too short — use at least one word with 3+ characters.\n");
        process.exit(1);
    }
    const results = manifest.units
        .map((unit) => scoreUnit(unit, terms))
        .filter((r) => r.score > 0)
        .sort((a, b) => b.score - a.score)
        .slice(0, 5);
    if (results.length === 0) {
        process.stdout.write(`No units matched "${query}".\n`);
        return;
    }
    const cyan = (s) => `\x1b[36m${s}\x1b[0m`;
    const dim = (s) => `\x1b[2m${s}\x1b[0m`;
    const bold = (s) => `\x1b[1m${s}\x1b[0m`;
    process.stdout.write(`\n${bold(`Search: "${query}"`)}\n`);
    process.stdout.write(dim(`Project: ${manifest.project} | ${results.length} result(s)\n\n`));
    for (let i = 0; i < results.length; i++) {
        const r = results[i];
        const rank = `${i + 1}.`;
        const reasons = r.match_reason.length > 0 ? dim(` [${r.match_reason.join(", ")}]`) : "";
        process.stdout.write(`${bold(rank)} ${cyan(r.id)}${reasons}\n`);
        process.stdout.write(`   ${r.intent}\n`);
        process.stdout.write(dim(`   ${r.path} | score: ${r.score} | audience: ${r.audience.join(", ") || "all"}\n`));
        process.stdout.write("\n");
    }
}
//# sourceMappingURL=query.js.map