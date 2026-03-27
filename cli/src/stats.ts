// kcp stats — show KCP usage statistics from ~/.kcp/usage.db
// See RFC-0017: Observability Hooks

import { homedir } from "node:os";
import { join } from "node:path";
import { existsSync } from "node:fs";

export interface StatsOptions {
  json: boolean;
  days: number;
  project?: string;
}

const DB_PATH = join(homedir(), ".kcp", "usage.db");

const bold  = (s: string) => `\x1b[1m${s}\x1b[0m`;
const cyan  = (s: string) => `\x1b[36m${s}\x1b[0m`;
const dim   = (s: string) => `\x1b[2m${s}\x1b[0m`;
const green = (s: string) => `\x1b[32m${s}\x1b[0m`;

function fmt(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + "M";
  if (n >= 1_000)     return (n / 1_000).toFixed(1) + "k";
  return String(n);
}

export function runStats(options: StatsOptions): void {
  if (!existsSync(DB_PATH)) {
    if (options.json) {
      process.stdout.write(
        JSON.stringify({ error: "no_data", db_path: DB_PATH,
          message: "No usage data found. Use kcp-mcp v0.15.0+ to start collecting." }) + "\n"
      );
    } else {
      process.stderr.write(`No usage data at ${DB_PATH}\n`);
      process.stderr.write("Start using kcp-mcp v0.15.0+ to begin collecting statistics.\n");
    }
    process.exit(1);
  }

  // Lazy-load better-sqlite3 — keeps startup fast for other commands
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const Database = require("better-sqlite3") as typeof import("better-sqlite3");
  const db = new Database(DB_PATH, { readonly: true });

  const sinceDate = new Date();
  sinceDate.setDate(sinceDate.getDate() - options.days);
  const since = sinceDate.toISOString();

  const projectClause = options.project ? " AND project = ?" : "";
  const baseParams: unknown[] = options.project ? [since, options.project] : [since];

  const counts = db.prepare(`
    SELECT
      COUNT(CASE WHEN event_type = 'search'   THEN 1 END) AS total_searches,
      COUNT(CASE WHEN event_type = 'get_unit' THEN 1 END) AS total_gets,
      COUNT(*) AS total_events
    FROM usage_events
    WHERE timestamp >= ?${projectClause}
  `).get(...baseParams) as { total_searches: number; total_gets: number; total_events: number };

  const savings = db.prepare(`
    SELECT COALESCE(SUM(manifest_token_total - token_estimate), 0) AS tokens_saved
    FROM usage_events
    WHERE event_type = 'get_unit'
      AND token_estimate IS NOT NULL
      AND manifest_token_total IS NOT NULL
      AND timestamp >= ?${projectClause}
  `).get(...baseParams) as { tokens_saved: number };

  const topUnits = db.prepare(`
    SELECT unit_id,
           COUNT(*) AS access_count,
           COALESCE(SUM(manifest_token_total - token_estimate), 0) AS tokens_saved
    FROM usage_events
    WHERE event_type = 'get_unit'
      AND unit_id IS NOT NULL
      AND timestamp >= ?${projectClause}
    GROUP BY unit_id
    ORDER BY access_count DESC
    LIMIT 10
  `).all(...baseParams) as { unit_id: string; access_count: number; tokens_saved: number }[];

  const topQueries = db.prepare(`
    SELECT query, COUNT(*) AS count
    FROM usage_events
    WHERE event_type = 'search'
      AND query IS NOT NULL
      AND timestamp >= ?${projectClause}
    GROUP BY query
    ORDER BY count DESC
    LIMIT 10
  `).all(...baseParams) as { query: string; count: number }[];

  const projects = (db.prepare(`
    SELECT DISTINCT project FROM usage_events
    WHERE timestamp >= ?${projectClause} ORDER BY project
  `).all(...baseParams) as { project: string }[]).map((r) => r.project);

  db.close();

  if (options.json) {
    process.stdout.write(
      JSON.stringify({
        period_days: options.days,
        total_searches: counts.total_searches,
        total_gets: counts.total_gets,
        total_events: counts.total_events,
        tokens_saved: savings.tokens_saved,
        top_units: topUnits,
        top_queries: topQueries,
        projects,
      }, null, 2) + "\n"
    );
    return;
  }

  const label = `last ${options.days} day${options.days === 1 ? "" : "s"}`;
  process.stdout.write(`\n${bold("KCP Usage Statistics")} ${dim(`(${label})`)}\n\n`);

  if (counts.total_events === 0) {
    process.stdout.write(dim("  No usage events in this period.\n\n"));
    return;
  }

  process.stdout.write(`  Queries served:  ${bold(String(counts.total_searches))}\n`);
  process.stdout.write(`  Units fetched:   ${bold(String(counts.total_gets))}\n`);

  if (savings.tokens_saved > 0) {
    process.stdout.write(`  Tokens saved:    ${green(fmt(savings.tokens_saved))}\n`);
  } else {
    process.stdout.write(`  Tokens saved:    ${dim("N/A (add hints.token_estimate to knowledge.yaml)")}\n`);
  }

  if (projects.length > 1) {
    process.stdout.write(`  Projects:        ${projects.join(", ")}\n`);
  }

  if (topUnits.length > 0) {
    process.stdout.write(`\n${bold("Top Units")}\n`);
    for (const u of topUnits) {
      const label = cyan(u.unit_id.padEnd(32));
      const hits  = `${String(u.access_count).padStart(4)} fetches`;
      const saved = u.tokens_saved > 0 ? dim(` (${fmt(u.tokens_saved)} tokens saved)`) : "";
      process.stdout.write(`  ${label}${hits}${saved}\n`);
    }
  }

  if (topQueries.length > 0) {
    process.stdout.write(`\n${bold("Top Queries")}\n`);
    for (const q of topQueries) {
      process.stdout.write(`  ${dim(String(q.count).padStart(4) + "x")} "${q.query}"\n`);
    }
  }

  process.stdout.write("\n");
}
