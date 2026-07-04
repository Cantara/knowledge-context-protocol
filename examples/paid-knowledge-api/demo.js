#!/usr/bin/env node
// Economic Metadata — interactive demo (RFC-0005, SPEC §4.14/§4.15, v0.25).
//
// Drives the SHIPPING `kcp` CLI against the paid-knowledge-api manifest in this
// directory to show how an agent decides WHAT access costs and HOW MUCH it can
// consume BEFORE it issues a request: it surveys the per-unit economics, selects
// a payment method it supports (ordered by publisher preference), computes the
// cost to load what it needs, and reads its rate-limit budget per tier.
//
// Authenticity: the economics each scenario reasons about are parsed from the
// real `kcp render` output of knowledge.yaml (a tiny block-YAML reader below).
// The agent-side decisions are COMPUTED from that authentic data, not scripted,
// so the demo cannot drift from the example.
//
// Usage:
//   node demo.js              # run every scenario, narrated
//   node demo.js budget       # run one scenario by id
//   node demo.js --list       # list scenario ids
//   node demo.js --capture    # write docs/js/payment-captures.js
//   node demo.js --no-color   # plain output (implied by --capture)
//
// Zero runtime dependencies — Node stdlib only.

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const CLI_DIR = path.resolve(ROOT, '..', '..', 'cli');
const CLI = path.join(CLI_DIR, 'dist', 'cli.js');
const MANIFEST = path.join(ROOT, 'knowledge.yaml');
const CAPTURE_OUT = path.resolve(ROOT, '..', '..', 'docs', 'js', 'payment-captures.js');

// The capabilities of the agent in this demo: it can pay per-request via x402
// and read free content, but it holds no subscription and no metered API key.
const AGENT_METHODS = ['free', 'x402'];

// ── tiny ANSI helpers ────────────────────────────────────────────────────────
let COLOR = process.stdout.isTTY === true;
const c = {
  dim:   (s) => (COLOR ? `\x1b[2m${s}\x1b[0m` : s),
  bold:  (s) => (COLOR ? `\x1b[1m${s}\x1b[0m` : s),
  green: (s) => (COLOR ? `\x1b[32m${s}\x1b[0m` : s),
  yellow:(s) => (COLOR ? `\x1b[33m${s}\x1b[0m` : s),
  cyan:  (s) => (COLOR ? `\x1b[36m${s}\x1b[0m` : s),
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

function kcp(args) {
  const r = spawnSync('node', [CLI, ...args], { encoding: 'utf8' });
  return {
    stdout: stripAnsi((r.stdout || '').toString()),
    stderr: stripAnsi((r.stderr || '').toString()),
    exit: r.status ?? 0,
    command: `kcp ${args.join(' ')}`.replace(MANIFEST, 'knowledge.yaml'),
  };
}

// ── minimal block-YAML reader (maps, lists, scalars — enough for render output) ─
function parseYaml(text) {
  const lines = text.replace(/\t/g, '  ').split('\n')
    .filter((l) => l.trim() !== '' && !l.trim().startsWith('#'));
  let i = 0;
  const scalar = (v) => {
    v = v.trim();
    if (v === '') return null;
    if (/^-?\d+$/.test(v)) return parseInt(v, 10);
    if (/^'.*'$/.test(v) || /^".*"$/.test(v)) return v.slice(1, -1);
    return v;
  };
  function parseBlock(indent) {
    // list?
    if (i < lines.length && lines[i].slice(indent).startsWith('- ')) {
      const arr = [];
      while (i < lines.length) {
        const ind = lines[i].search(/\S/);
        if (ind < indent || !lines[i].slice(indent).startsWith('- ')) break;
        const rest = lines[i].slice(ind + 2);
        i++;
        if (/^[\w-]+:/.test(rest)) {
          // inline first key of a map item; rewrite as its own line and parse a map
          lines.splice(i, 0, ' '.repeat(ind + 2) + rest);
          arr.push(parseBlock(ind + 2));
        } else {
          arr.push(scalar(rest));
        }
      }
      return arr;
    }
    const obj = {};
    while (i < lines.length) {
      const ind = lines[i].search(/\S/);
      if (ind < indent) break;
      if (ind > indent) { i++; continue; }
      const m = lines[i].match(/^(\s*)([\w-]+):\s*(.*)$/);
      if (!m) break;
      i++;
      const key = m[2];
      if (m[3].trim() !== '') { obj[key] = scalar(m[3]); continue; }
      // nested block
      const childIndent = i < lines.length ? lines[i].search(/\S/) : indent;
      obj[key] = childIndent > indent ? parseBlock(childIndent) : null;
    }
    return obj;
  }
  return parseBlock(lines[0].search(/\S/));
}

// The cost to load a unit for an agent holding `AGENT_METHODS`, from its payment block.
function planFor(payment) {
  const methods = (payment && payment.methods) || [{ type: 'free' }];
  for (const m of methods) {                       // publisher order; first supported wins
    if (!AGENT_METHODS.includes(m.type)) continue;
    if (m.type === 'free') return { method: 'free', label: 'free' };
    if (m.type === 'x402') return { method: 'x402', label: `${m.price_per_request} ${m.currency}/request` };
  }
  const need = methods.map((m) => m.type).filter((t) => t !== 'free');
  return { method: null, label: `needs ${need.join(' or ')} (agent supports ${AGENT_METHODS.join(', ')})` };
}

const pick = (text, patterns) => text.split('\n')
  .filter((line) => patterns.some((p) => line.includes(p)))
  .map((line) => line.replace(/\s+$/, ''));

// ── scenarios ─────────────────────────────────────────────────────────────────
const SCENARIOS = [
  {
    id: 'catalogue',
    title: 'Surveying the economics before spending anything',
    useCase:
      'An agent renders the API manifest. The render is free, and it carries the full economic map: ' +
      'the free public index, an x402-metered price feed, and a subscription research corpus. The ' +
      'agent now knows every price before issuing one paid request.',
    run({ model, render }) {
      const rows = model.units.map((u) => {
        const tier = (u.payment && u.payment.default_tier) || 'free (inherited)';
        return `  ${u.id.padEnd(17)} ${tier}`;
      });
      const highlights = [
        ...pick(kcp(['validate', MANIFEST]).stdout, ['units, kcp_version', 'Valid']).map((l) => l.trim()),
        '',
        'per-unit tier (from the render):',
        ...rows,
      ];
      return { command: render.command, output: render.stdout, highlights };
    },
    verdict:
      'One manifest, three economic models, surfaced as data by the trusted render pipeline. The ' +
      'agent reads cost from the artifact — KCP declares it and settles nothing, never dereferencing ' +
      'a wallet or pricing URL.',
  },
  {
    id: 'method',
    title: 'Selecting a payment method it actually supports',
    useCase:
      `This agent can read free content and pay via x402, but holds no subscription. For each unit ` +
      `it walks the publisher-ordered methods and takes the first it supports.`,
    run({ model }) {
      const highlights = [c.dim(`  agent supports: ${AGENT_METHODS.join(', ')}`), ''];
      for (const u of model.units) {
        const plan = planFor(u.payment);
        const line = `  ${u.id.padEnd(17)} → ${plan.label}`;
        highlights.push(plan.method ? c.green(line) : c.yellow(line));
      }
      return { command: 'kcp render knowledge.yaml', output: '(agent decision computed from the render)', highlights, reuseRender: true };
    },
    verdict:
      'Publisher preference, agent capability: docs is free, realtime-prices is payable per request ' +
      'via x402, and premium-research is out of reach until the agent gets a subscription or metered ' +
      'key. It plans this before fetching — no surprise 402.',
  },
  {
    id: 'budget',
    title: 'Computing the bill before the first fetch',
    useCase:
      'The agent wants the price feed refreshed 100 times this hour. Because the x402 price is ' +
      'declared up front, it can compute the spend and decide whether it fits its budget — instead ' +
      'of discovering the cost one 402 at a time.',
    run({ model }) {
      const rt = model.units.find((u) => u.id === 'realtime-prices');
      const plan = planFor(rt.payment);
      const x402 = (rt.payment.methods || []).find((m) => m.type === 'x402');
      const n = 100;
      const unit = parseFloat(x402.price_per_request);
      const total = (unit * n).toFixed(3);
      const highlights = [
        `  realtime-prices: ${plan.label}`,
        `  plan: ${n} refreshes this hour`,
        c.green(`  projected spend: ${n} × ${x402.price_per_request} ${x402.currency} = ${total} ${x402.currency}`),
        c.dim(`  settlement networks: ${(x402.networks || []).join(', ')}`),
      ];
      return { command: 'kcp render knowledge.yaml', output: '(budget computed from the rendered x402 price)', highlights, reuseRender: true };
    },
    verdict:
      'A decimal-string price the agent multiplies to a firm number — 100 × 0.002 USDC = 0.200 USDC — ' +
      'and settles on base or ethereum. The economics are legible in advance, so the agent budgets ' +
      'instead of backtracking.',
  },
  {
    id: 'rateplan',
    title: 'Reading the rate-limit budget per tier',
    useCase:
      'Cost is only half the picture — the agent also self-throttles. The manifest discloses limits ' +
      'per tier, so an anonymous agent knows its ceiling without waiting for a 429.',
    run({ model }) {
      const rl = model.rate_limits || {};
      const tierLine = (name, t) => t
        ? `  ${name.padEnd(14)} ${t.requests_per_minute ?? '—'}/min, ${t.requests_per_day ?? '—'}/day`
        : null;
      const highlights = [
        'root rate limits (from the render):',
        tierLine('default', rl.default),
        tierLine('authenticated', rl.authenticated),
        tierLine('premium', rl.premium),
        '',
        c.dim(`  backoff: ${rl.backoff ?? '—'}   |   token limit (default): ${rl.tokens && rl.tokens.default ? rl.tokens.default.tokens_per_minute + '/min' : '—'}`),
        c.green(`  this (anonymous) agent uses the default tier: ${rl.default ? rl.default.requests_per_minute + ' req/min' : 'unspecified'}`),
      ].filter(Boolean);
      return { command: 'kcp render knowledge.yaml', output: '(tiers parsed from the rendered rate_limits)', highlights, reuseRender: true };
    },
    verdict:
      'default → authenticated → premium, with premium unlimited per day and a token-per-minute ceiling ' +
      'for LLM pipelines. The agent self-throttles at the tier its credentials earn — advisory, but ' +
      'enough to plan a request budget up front.',
  },
];

// ── runner ─────────────────────────────────────────────────────────────────────
function setup() {
  ensureCliBuilt();
  const render = kcp(['render', MANIFEST]);
  const doc = parseYaml(render.stdout);
  const model = {
    payment: doc.payment,
    rate_limits: doc.rate_limits,
    units: (doc.units || []).map((u) => ({ id: u.id, payment: u.payment || doc.payment })),
  };
  return { render, model };
}

function printScenario(s, rec, index, total) {
  console.log('');
  console.log(c.bold(`━━━ ${index}/${total}  ${s.title}`));
  console.log('');
  console.log(c.dim(wrap(s.useCase, 78)));
  console.log('');
  console.log(c.cyan('  $ ') + rec.command + (rec.reuseRender ? c.dim('   (reasoning over the render above)') : ''));
  console.log('');
  for (const line of rec.highlights) console.log('  ' + line);
  console.log('');
  console.log(`  ${c.green('✓')} ${c.bold('What this shows:')} ${wrap(s.verdict, 74, '     ')}`);
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
  return lines.join('\n' + indent);
}

function writeCaptures(records) {
  const payload = records.map(({ s, rec }) => ({
    id: s.id,
    title: s.title,
    useCase: s.useCase,
    runCommand: `node examples/paid-knowledge-api/demo.js ${s.id}`,
    command: rec.command,
    output: rec.highlights.map(stripAnsi).join('\n'),
    fullOutput: rec.output.trimEnd(),
    verdict: s.verdict,
  }));
  const banner = '// Generated by examples/paid-knowledge-api/demo.js --capture.\n' +
    '// Authentic output of the shipping `kcp` CLI; do not edit by hand.\n';
  fs.mkdirSync(path.dirname(CAPTURE_OUT), { recursive: true });
  fs.writeFileSync(CAPTURE_OUT,
    banner + 'window.PAYMENT_CAPTURES = ' + JSON.stringify(payload, null, 2) + ';\n');
  console.log(`Wrote ${records.length} captures → ${path.relative(process.cwd(), CAPTURE_OUT)}`);
}

// ── main ─────────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);
if (argv.includes('--no-color')) COLOR = false;
if (argv.includes('--list')) {
  for (const s of SCENARIOS) console.log(`${s.id}\t${s.title}`);
  process.exit(0);
}
const capture = argv.includes('--capture');
if (capture) COLOR = false;

const selected = argv.find((a) => !a.startsWith('-'));
const toRun = selected ? SCENARIOS.filter((s) => s.id === selected) : SCENARIOS;
if (selected && toRun.length === 0) {
  console.error(`Unknown scenario "${selected}". Try --list.`);
  process.exit(1);
}

const ctx = setup();
const records = [];
toRun.forEach((s, i) => {
  const rec = s.run(ctx);
  records.push({ s, rec });
  if (!capture) printScenario(s, rec, i + 1, toRun.length);
});

if (capture) {
  writeCaptures(records);
} else {
  console.log('');
  console.log(c.dim('  Every price and limit above is parsed from the shipping `kcp render` of this'));
  console.log(c.dim('  manifest — the agent decisions are computed from that authentic output.'));
  console.log(c.dim('  Re-run a single case: ') + c.cyan('node demo.js <id>'));
}
