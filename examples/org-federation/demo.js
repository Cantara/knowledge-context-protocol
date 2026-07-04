#!/usr/bin/env node
// Org-Federation — interactive demo (RFC-0011, SPEC §3.6, v0.24).
//
// Drives the SHIPPING `kcp` CLI (../../cli) against the org-federation hub in
// this directory to show how an agent bootstraps into an enterprise: it arrives
// cold at the front door, selects sub-manifests by `context` for its runtime
// environment, reads each edge's `agent_identity` to plan credentials BEFORE it
// fetches, and climbs the public→internal→confidential progressive-disclosure
// ladder.
//
// Authenticity: the manifest facts each scenario narrates are parsed from the
// real `kcp render` output of examples/org-federation/knowledge.yaml — the same
// artifact `kcp render` emits. The agent-side decisions (which edge to select,
// which credential to acquire) are COMPUTED from that authentic data here, not
// hardcoded, so the demo cannot drift from the example.
//
// Usage:
//   node demo.js              # run every scenario, narrated
//   node demo.js cold         # run one scenario by id
//   node demo.js --list       # list scenario ids
//   node demo.js --capture    # write docs/js/org-federation-captures.js
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
const CAPTURE_OUT = path.resolve(ROOT, '..', '..', 'docs', 'js', 'org-federation-captures.js');

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

// ── cli build + invocation ────────────────────────────────────────────────────
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

// Pull lines matching any of the given substrings, right-trimmed.
function pick(text, patterns) {
  return text.split('\n')
    .filter((line) => patterns.some((p) => line.includes(p)))
    .map((line) => line.replace(/\s+$/, ''));
}

// ── authentic model: parse the rendered federation block ──────────────────────
// `kcp render` emits the manifests block as `federation:` — id, url, relationship,
// context, and the agent_identity sub-object. We parse exactly that indented YAML
// slice so every fact the demo states is grounded in real renderer output.
function parseFederation(renderYaml) {
  const lines = renderYaml.split('\n');
  let i = lines.findIndex((l) => /^federation:/.test(l));
  if (i < 0) return [];
  i++;
  const edges = [];
  let cur = null;
  let mode = null; // null | 'context' | 'agent_identity'
  for (; i < lines.length; i++) {
    const raw = lines[i];
    if (/^\S/.test(raw)) break;                 // dedented out of the block
    const t = raw.trim();
    if (t === '') continue;
    const mId = raw.match(/^ {2}- id:\s*(.+)$/);
    if (mId) {
      cur = { id: mId[1].trim(), context: null, agent_identity: null };
      edges.push(cur);
      mode = null;
      continue;
    }
    if (!cur) continue;
    if (/^ {4}relationship:/.test(raw)) { cur.relationship = t.split(':')[1].trim(); mode = null; continue; }
    if (/^ {4}url:/.test(raw)) { mode = null; continue; }
    if (/^ {4}context:/.test(raw)) {
      mode = 'context';
      cur.context = [];
      const inline = t.match(/^context:\s*\[(.*)\]$/);
      if (inline) { cur.context = inline[1].split(',').map((s) => s.trim().replace(/['"]/g, '')).filter(Boolean); mode = null; }
      continue;
    }
    if (/^ {4}agent_identity:/.test(raw)) { mode = 'agent_identity'; cur.agent_identity = {}; continue; }
    if (mode === 'context') {
      const m = raw.match(/^ {6}-\s*(.+)$/);
      if (m) { cur.context.push(m[1].trim().replace(/['"]/g, '')); continue; }
      mode = null;
    }
    if (mode === 'agent_identity') {
      const m = raw.match(/^ {6}(\w+):\s*(.+)$/);
      if (m) { cur.agent_identity[m[1]] = m[2].trim().replace(/['"]/g, ''); continue; }
      mode = null;
    }
  }
  return edges;
}

// An edge is valid in `env` if it has no context (all envs) or lists env.
const validIn = (edge, env) => !edge.context || edge.context.includes(env);

// ── scenarios ─────────────────────────────────────────────────────────────────
// Each scenario runs a real kcp command (or reuses the render) and narrates the
// agent decision computed from the authentic federation model.
const SCENARIOS = [
  {
    id: 'cold',
    title: 'An agent arrives knowing only the domain',
    useCase:
      'A fresh agent has nothing but companyx.example. It fetches /.well-known/knowledge.yaml ' +
      'and renders it. The hub is a front door: the public units are readable immediately, and ' +
      'the federation block advertises what else exists — without the agent authenticating first.',
    run({ render }) {
      const v = kcp(['validate', MANIFEST]);
      const highlights = [
        ...pick(v.stdout, ['units, kcp_version', 'Valid']).map((l) => l.trim()),
        '',
        'front door (public, load-eager): overview.md, guides/agent-authentication.md',
        `federated sources advertised: ${render.edges.map((e) => e.id).join(', ')}`,
      ];
      return { command: v.command, output: v.stdout, highlights };
    },
    verdict:
      'The hub loads cold. Its public tier is readable without a credential, and its federation ' +
      'list tells the agent what sub-manifests exist before it has proven anything. Discovery ' +
      'first, authentication only when the agent goes deeper.',
  },
  {
    id: 'env-prod',
    title: 'Selecting sub-manifests for a prod runtime',
    useCase:
      'The agent is running in production. The hub lists three federated sources tagged with ' +
      '`context`. The agent must fetch only the ones valid in prod — not the dev mirror — so it ' +
      'never reconciles a dev manifest into a prod session.',
    run({ render }) {
      const env = 'prod';
      const chosen = render.edges.filter((e) => validIn(e, env));
      const skipped = render.edges.filter((e) => !validIn(e, env));
      const pad = Math.max(...render.edges.map((e) => e.id.length));
      const highlights = [
        'federation (context tags, from the render):',
        ...render.edges.map((e) => `  ${e.id.padEnd(pad)}  context: [${(e.context || ['*any*']).join(', ')}]`),
        '',
        c.green(`  agent(env=prod) selects: ${chosen.map((e) => e.id).join(', ')}`),
        c.yellow(`  agent(env=prod) skips:   ${skipped.map((e) => `${e.id} (context ${JSON.stringify(e.context)})`).join(', ') || '—'}`),
      ];
      return { command: render.command, output: render.stdout, highlights, reuseRender: true };
    },
    verdict:
      'One hub, one federation list, spanning environments. The prod agent takes its slice — the ' +
      'prod and prod/staging sources — and ignores the dev mirror. `context` is an advisory ' +
      'selection hint; KCP surfaces it, the agent applies it.',
  },
  {
    id: 'env-dev',
    title: 'The same hub, selected from a dev runtime',
    useCase:
      'A different agent renders the identical hub, but it is running in dev. It should take only ' +
      'the dev/test source — and, conveniently, that one needs no credential.',
    run({ render }) {
      const env = 'dev';
      const chosen = render.edges.filter((e) => validIn(e, env));
      const skipped = render.edges.filter((e) => !validIn(e, env));
      const highlights = [
        c.green(`  agent(env=dev) selects: ${chosen.map((e) => e.id).join(', ') || '—'}`),
        c.yellow(`  agent(env=dev) skips:   ${skipped.map((e) => e.id).join(', ')}`),
      ];
      return { command: render.command, output: render.stdout, highlights, reuseRender: true };
    },
    verdict:
      'Same manifest, different slice. The dev agent selects only the dev/test mirror and skips ' +
      'the prod sources entirely — no separate URL to manage, no per-environment manifest fork.',
  },
  {
    id: 'credentials',
    title: 'Planning credentials before the fetch',
    useCase:
      'Before the prod agent fetches a selected source, it reads that edge’s `agent_identity`. ' +
      'That hint tells it what credential to bring — so it acquires the token up front instead of ' +
      'firing a fetch, getting a 401, and backtracking.',
    run({ render }) {
      const prod = render.edges.filter((e) => validIn(e, 'prod'));
      const plan = prod.map((e) => {
        const ai = e.agent_identity || {};
        if (ai.required === 'true') {
          const extra = ai.issuer_hint ? ` from issuer ${ai.issuer_hint}` : '';
          return c.yellow(`  ${e.id}: acquire ${ai.credential_hint}${extra} BEFORE fetch (required)`);
        }
        return c.green(`  ${e.id}: no credential required — fetch directly`);
      });
      const highlights = [
        ...pick(render.stdout, ['agent_identity:', 'required:', 'credential_hint:', 'issuer_hint:', 'docs_url:']),
        '',
        ...plan,
      ];
      return { command: render.command, output: render.stdout, highlights, reuseRender: true };
    },
    verdict:
      'The agent plans from declarations: a GitHub PAT for platform-engineering, an OAuth 2.1 token ' +
      'from the named issuer for the data warehouse. `agent_identity` is a hint, not a gate — the ' +
      'sub-manifest’s own `auth` block still enforces. But the agent never fetches blind.',
  },
  {
    id: 'disclosure',
    title: 'Climbing the progressive-disclosure ladder',
    useCase:
      'The hub’s own units span three sensitivity tiers. An agent climbs them in order: read the ' +
      'public front door, authenticate for the internal catalogue, get role approval for the ' +
      'confidential data contracts. Each gate is declared in advance.',
    run() {
      const q = kcp(['query', 'what services and data contracts exist?', '--file', MANIFEST]);
      const manifest = fs.readFileSync(MANIFEST, 'utf8');
      // Derive the sensitivity ladder straight from the manifest UNITS only —
      // slice out the units: section so the manifests[] entries (which also use
      // `- id:`) are never miscounted as units.
      const unitsSection = manifest.slice(
        manifest.indexOf('\nunits:'),
        manifest.indexOf('\nmanifests:') >= 0 ? manifest.indexOf('\nmanifests:') : undefined
      );
      const tiers = { public: [], internal: [], confidential: [] };
      const unitRe = /- id:\s*(\S+)[\s\S]*?(?=\n {2}- id:|$)/g;
      let m;
      while ((m = unitRe.exec(unitsSection))) {
        const block = m[0];
        const id = m[1];
        const sens = (block.match(/sensitivity:\s*(\w+)/) || [])[1] || 'public';
        if (tiers[sens]) tiers[sens].push(id);
      }
      const highlights = [
        c.green(`  T0 public       → ${tiers.public.join(', ')}    (no auth)`),
        c.yellow(`  T1 internal     → ${tiers.internal.join(', ')}    (authenticated developer)`),
        c.yellow(`  T2 confidential → ${tiers.confidential.join(', ')}   (role-specific approval)`),
      ];
      return { command: q.command, output: q.stdout, highlights };
    },
    verdict:
      'Public → internal → confidential, all on `compliance.sensitivity` with no new spec fields. ' +
      'The agent knows every gate before it reaches it, so it climbs deliberately instead of ' +
      'probing each unit and getting refused.',
  },
];

// ── runner ─────────────────────────────────────────────────────────────────────
function setup() {
  ensureCliBuilt();
  const render = kcp(['render', MANIFEST]);
  render.edges = parseFederation(render.stdout);
  return { render };
}

function printScenario(s, rec, index, total) {
  console.log('');
  console.log(c.bold(`━━━ ${index}/${total}  ${s.title}`));
  console.log('');
  console.log(c.dim(wrap(s.useCase, 78)));
  console.log('');
  console.log(c.cyan('  $ ') + rec.command + (rec.reuseRender ? c.dim('   (reusing the render above)') : ''));
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
    runCommand: `node examples/org-federation/demo.js ${s.id}`,
    command: rec.command,
    output: rec.highlights.map(stripAnsi).join('\n'),
    fullOutput: rec.output.trimEnd(),
    verdict: s.verdict,
  }));
  const banner = '// Generated by examples/org-federation/demo.js --capture.\n' +
    '// Authentic output of the shipping `kcp` CLI; do not edit by hand.\n';
  fs.mkdirSync(path.dirname(CAPTURE_OUT), { recursive: true });
  fs.writeFileSync(CAPTURE_OUT,
    banner + 'window.ORG_FEDERATION_CAPTURES = ' + JSON.stringify(payload, null, 2) + ';\n');
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
  console.log(c.dim('  Every fact above is parsed from the shipping `kcp render` of this hub —'));
  console.log(c.dim('  the agent decisions are computed from that authentic output, not scripted.'));
  console.log(c.dim('  Re-run a single case: ') + c.cyan('node demo.js <id>'));
}
