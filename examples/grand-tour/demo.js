#!/usr/bin/env node
// KCP Grand Tour — the whole stack, end to end, driven by the shipping `kcp` CLI.
//
// One narrated walk from a five-line manifest to enterprise federation, each
// stop a REAL `kcp` command run against a REAL example in this repo. Nothing is
// mocked: the output you read is the output the CLI prints. The tour is the
// answer to "show me KCP in all its glory" — adoption, navigation, time-travel,
// the trusted render pipeline, agent attestation, and org-federation, in the
// order a project actually grows into them.
//
// Usage:
//   node demo.js              # the full tour, narrated
//   node demo.js navigate     # one stop by id
//   node demo.js --list       # list stop ids
//   node demo.js --capture    # write docs/js/grand-tour-captures.js for the browser
//   node demo.js --no-color   # plain output (implied by --capture)
//
// Zero runtime dependencies — Node stdlib only.

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const EXAMPLES = path.resolve(ROOT, '..');
const REPO = path.resolve(ROOT, '..', '..');
const CLI_DIR = path.join(REPO, 'cli');
const CLI = path.join(CLI_DIR, 'dist', 'cli.js');
const CAPTURE_OUT = path.join(REPO, 'docs', 'js', 'grand-tour-captures.js');
const ex = (p) => path.join(EXAMPLES, p);

// ── tiny ANSI helpers ────────────────────────────────────────────────────────
let COLOR = process.stdout.isTTY === true;
const c = {
  dim:   (s) => (COLOR ? `\x1b[2m${s}\x1b[0m` : s),
  bold:  (s) => (COLOR ? `\x1b[1m${s}\x1b[0m` : s),
  green: (s) => (COLOR ? `\x1b[32m${s}\x1b[0m` : s),
  yellow:(s) => (COLOR ? `\x1b[33m${s}\x1b[0m` : s),
  cyan:  (s) => (COLOR ? `\x1b[36m${s}\x1b[0m` : s),
  mag:   (s) => (COLOR ? `\x1b[35m${s}\x1b[0m` : s),
};
const stripAnsi = (s) => s.replace(/\x1b\[[0-9;]*m/g, '');

function ensureCliBuilt() {
  if (fs.existsSync(CLI)) return;
  if (!fs.existsSync(path.join(CLI_DIR, 'node_modules'))) {
    console.error(`The kcp CLI is not built and cli/node_modules is missing.\n` +
      `Run once:  (cd ${CLI_DIR} && npm install && npm run build)`);
    process.exit(1);
  }
  console.error(c.dim('Building kcp CLI (dist missing)…'));
  execFileSync('npm', ['run', 'build'], { cwd: CLI_DIR, stdio: 'inherit' });
}

// Run a kcp command; return authentic stdout/stderr and a clean display command.
function kcp(args) {
  const r = spawnSync('node', [CLI, ...args], { encoding: 'utf8', cwd: REPO });
  let command = `kcp ${args.join(' ')}`;
  for (const a of args) if (a.startsWith('/') || a.includes(REPO)) {
    command = command.replace(a, path.relative(REPO, a));
  }
  return {
    stdout: stripAnsi((r.stdout || '').toString()),
    stderr: stripAnsi((r.stderr || '').toString()),
    exit: r.status ?? 0,
    command,
  };
}

const pick = (text, patterns, limit = 12) => text.split('\n')
  .filter((line) => patterns.some((p) => line.includes(p)))
  .map((line) => line.replace(/\s+$/, ''))
  .slice(0, limit);

// ── the tour ───────────────────────────────────────────────────────────────────
const STOPS = [
  {
    id: 'adopt',
    badge: 'Adopt · v0.3',
    title: 'The smallest thing that works',
    blurb:
      'KCP adoption starts at five lines: id, path, intent, scope, audience. A solo project ' +
      'declares one unit and an agent already knows what the repo is for. Everything else in this ' +
      'tour is optional depth layered on top of exactly this.',
    run() {
      const manifest = fs.readFileSync(ex('minimal/knowledge.yaml'), 'utf8').trimEnd();
      const v = kcp(['validate', ex('minimal/knowledge.yaml')]);
      return {
        command: v.command,
        output: v.stdout,
        highlights: [
          ...manifest.split('\n').map((l) => c.dim('  │ ') + l),
          '',
          ...pick(v.stdout, ['unit,', 'units,', 'Valid']).map((l) => l.trim()),
        ],
      };
    },
    verdict: 'One unit, parsed and validated. That is a conformant KCP manifest — no ceremony required.',
  },
  {
    id: 'navigate',
    badge: 'Navigate · v0.14',
    title: 'Find the right unit without reading the tree',
    blurb:
      'Point a question at a real multi-section knowledge base. KCP scores every unit by intent, ' +
      'triggers, id, and path and returns a ranked route — the agent loads the top hit instead of ' +
      'grepping a repo it has never seen.',
    run() {
      const q = kcp(['query', 'how is the IAM architecture organized?', '--file', ex('open-source-wiki/knowledge.yaml')]);
      return {
        command: q.command,
        output: q.stdout,
        highlights: pick(q.stdout, ['result(s)', 'score:', '1.', '2.', '3.'], 9),
      };
    },
    verdict:
      'Ranked routing, not full-text guessing. The agent spends one lookup to find the unit and ' +
      'skips the other fifteen — the 53–80% tool-call reduction KCP exists to deliver.',
  },
  {
    id: 'time-travel',
    badge: 'Time-travel · v0.19–0.20',
    title: 'Reconstruct what was true on a past date',
    blurb:
      'The bi-temporal model lets a manifest version itself. The same query, run `--as-of` two ' +
      'different dates, returns two different policies — because a future-dated rollout supersedes ' +
      'the legacy one when its date arrives.',
    run() {
      const past = kcp(['query', 'MFA policy', '--file', ex('temporal-validity/knowledge.yaml'), '--as-of', '2025-06-01']);
      const future = kcp(['query', 'MFA policy', '--file', ex('temporal-validity/knowledge.yaml'), '--as-of', '2027-01-01']);
      const top = (r) => (pick(r.stdout, ['1.'], 1)[0] || '').trim();
      return {
        command: `${past.command}\n  $ ${future.command}`,
        output: `# --as-of 2025-06-01\n${past.stdout}\n\n# --as-of 2027-01-01\n${future.stdout}`,
        highlights: [
          c.cyan('  as-of 2025-06-01  ') + '→ top hit: ' + c.bold(top(past)),
          c.cyan('  as-of 2027-01-01  ') + '→ top hit: ' + c.bold(top(future)),
        ],
      };
    },
    verdict:
      'Point-in-time reconstruction for audit and future-dated policy: the 2025 query sees the ' +
      'legacy policy, the 2027 query sees its successor. One manifest, a time axis.',
  },
  {
    id: 'render',
    badge: 'Trust · v0.16–0.18',
    title: 'The trusted render pipeline — read, never obey',
    blurb:
      'Before an agent treats a manifest as standing context, it renders it. The renderer is ' +
      'deterministic, LLM-free, and fail-closed: unsigned content from a source you have not ' +
      'allowlisted is rendered as *data you can read* — never as instructions, never auto-trusted.',
    run() {
      const r = kcp(['render', ex('dependency-graph/knowledge.yaml')]);
      return {
        command: r.command,
        output: r.stdout,
        highlights: pick(r.stdout, ['tier:', 'renderer:', 'lint_rules:', 'load_eligible:'], 6),
      };
    },
    verdict:
      'tier: unsigned — the manifest is legible but not load-eligible into standing context. ' +
      '“A manifest may influence what an agent knows, never what it does.” (Full threat-model walk: ' +
      'examples/trusted-render-demo/.)',
  },
  {
    id: 'attest',
    badge: 'Attestation · v0.22',
    title: 'Knowledge that asks the agent to prove itself',
    blurb:
      'The producer-integrity story has a consumer-identity mirror. A restricted unit can require ' +
      'the agent to attest who it is before it is served. The renderer surfaces the requirement as ' +
      'data — and, like everything here, never performs the auth.',
    run() {
      const r = kcp(['render', ex('attestation/knowledge.yaml')]);
      return {
        command: r.command,
        output: r.stdout,
        highlights: pick(r.stdout, ['require_attestation:', 'requires_attestation:', 'trusted_providers:', 'publisher_did:'], 8),
      };
    },
    verdict:
      'require_attestation and requires_attestation are surfaced; attestation_url is never called. ' +
      'KCP declares the requirement; the agent attests. The bridge enforces the gate on every ' +
      'retrieval path (C19/C20).',
  },
  {
    id: 'federate',
    badge: 'Federate · v0.24',
    title: 'One hub, a whole organisation',
    blurb:
      'The newest layer: an enterprise hub an agent reaches knowing only a domain. Each federated ' +
      'source is tagged with the environment it is valid for (`context`) and the credential to ' +
      'bring before fetching (`agent_identity`) — so the agent plans its traversal instead of ' +
      'probing blind.',
    run() {
      const r = kcp(['render', ex('org-federation/knowledge.yaml')]);
      return {
        command: r.command,
        output: r.stdout,
        highlights: pick(r.stdout, ['federation:', '- id:', 'context:', '- prod', '- dev', 'credential_hint:', 'required:'], 12),
      };
    },
    verdict:
      'context and agent_identity surface per federated edge. The prod agent takes the prod ' +
      'sources and plans a GitHub PAT / OAuth token up front. Full walk: examples/org-federation/demo.js.',
  },
  {
    id: 'monetize',
    badge: 'Monetize · v0.25',
    title: 'What access costs, before the first request',
    blurb:
      'The economic layer: a manifest declares what each unit costs and how much an agent may ' +
      'consume. A free index, an x402-metered price feed, and a subscription corpus — the agent ' +
      'reads the prices and rate limits from the render and budgets before it spends a cent.',
    run() {
      const r = kcp(['render', ex('paid-knowledge-api/knowledge.yaml')]);
      return {
        command: r.command,
        output: r.stdout,
        highlights: pick(r.stdout, ['default_tier:', 'type: x402', 'price_per_request:', 'requests_per_minute:', 'requests_per_day:', 'backoff:'], 12),
      };
    },
    verdict:
      'payment (tiers, x402 prices) and rate_limits (per-tier budgets) surface as data — never ' +
      'dereferenced. The agent plans cost and throttle up front. Full walk: examples/paid-knowledge-api/demo.js.',
  },
];

function printStop(s, rec, i, total) {
  console.log('');
  console.log(c.mag(`━━━ Stop ${i}/${total}  ·  ${s.badge}`));
  console.log(c.bold('    ' + s.title));
  console.log('');
  console.log(c.dim(wrap(s.blurb, 78, '  ')));
  console.log('');
  console.log(c.cyan('  $ ') + rec.command);
  console.log('');
  for (const line of rec.highlights) console.log('  ' + line);
  console.log('');
  console.log(`  ${c.green('→')} ${wrap(s.verdict, 76, '    ')}`);
}

function wrap(text, width, indent = '') {
  const words = stripAnsi(text).split(/\s+/);
  const lines = [];
  let line = '';
  for (const w of words) {
    if ((line + ' ' + w).trim().length > width) { lines.push(line); line = w; }
    else line = (line + ' ' + w).trim();
  }
  if (line) lines.push(line);
  return indent + lines.join('\n' + indent);
}

function writeCaptures(records) {
  const payload = records.map(({ s, rec }) => ({
    id: s.id,
    badge: s.badge,
    title: s.title,
    blurb: s.blurb,
    runCommand: `node examples/grand-tour/demo.js ${s.id}`,
    command: rec.command,
    output: rec.highlights.map(stripAnsi).join('\n'),
    fullOutput: rec.output.trimEnd(),
    verdict: s.verdict,
  }));
  const banner = '// Generated by examples/grand-tour/demo.js --capture.\n' +
    '// Authentic output of the shipping `kcp` CLI; do not edit by hand.\n';
  fs.mkdirSync(path.dirname(CAPTURE_OUT), { recursive: true });
  fs.writeFileSync(CAPTURE_OUT,
    banner + 'window.GRAND_TOUR_CAPTURES = ' + JSON.stringify(payload, null, 2) + ';\n');
  console.log(`Wrote ${records.length} captures → ${path.relative(process.cwd(), CAPTURE_OUT)}`);
}

// ── main ─────────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);
if (argv.includes('--no-color')) COLOR = false;
if (argv.includes('--list')) {
  for (const s of STOPS) console.log(`${s.id}\t${s.badge}\t${s.title}`);
  process.exit(0);
}
const capture = argv.includes('--capture');
if (capture) COLOR = false;

const selected = argv.find((a) => !a.startsWith('-'));
const toRun = selected ? STOPS.filter((s) => s.id === selected) : STOPS;
if (selected && toRun.length === 0) {
  console.error(`Unknown stop "${selected}". Try --list.`);
  process.exit(1);
}

ensureCliBuilt();
if (!capture) {
  console.log('');
  console.log(c.bold('  KCP Grand Tour') + c.dim(' — the whole stack, driven by the shipping `kcp` CLI'));
}
const records = [];
toRun.forEach((s, i) => {
  const rec = s.run();
  records.push({ s, rec });
  if (!capture) printStop(s, rec, i + 1, toRun.length);
});

if (capture) {
  writeCaptures(records);
} else {
  console.log('');
  console.log(c.dim('  Every command above is real. Re-run one stop: ') + c.cyan('node demo.js <id>'));
  console.log(c.dim('  Browser replay: docs/showcase.html'));
}
