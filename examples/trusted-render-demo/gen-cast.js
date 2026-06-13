#!/usr/bin/env node
// Generate an animated SVG "terminal cast" of the trusted-render demo.
//
// Reads the captures produced by `demo.js --capture` (docs/js/render-captures.js)
// and emits docs/render-cast.svg — a pure-CSS animated terminal that cycles
// through every scenario's real `kcp render` output. No external tools, no JS in
// the SVG (GitHub strips <script> but animates CSS keyframes in img-embedded
// SVG), and nothing reimplemented: the frames are the authentic renderer output.
//
// Usage:  node gen-cast.js   (run `node demo.js --capture` first)

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const CAPTURES = path.resolve(ROOT, '..', '..', 'docs', 'js', 'render-captures.js');
const OUT = path.resolve(ROOT, '..', '..', 'docs', 'render-cast.svg');

if (!fs.existsSync(CAPTURES)) {
  console.error('Captures missing. Run:  node demo.js --capture');
  process.exit(1);
}
// The captures file is our own generated `window.RENDER_CAPTURES = [...]`.
const sandbox = {};
new Function('window', fs.readFileSync(CAPTURES, 'utf8'))(sandbox);
const caps = sandbox.RENDER_CAPTURES || [];

// Terse, punchy verdict per scenario for the cast (the README/page carry the long form).
const CAST_VERDICT = {
  trusted:     ['✓ tier: trusted', 'signed + allowlisted + hashes match → load-eligible'],
  unsigned:    ['✓ tier: unsigned', 'readable data, never auto-loaded'],
  relocation:  ['✓ T9 relocation blocked', 'derived-evidence cap + hash mismatch → not load-eligible'],
  stripping:   ['✓ T7 stripping blocked', 'pinned + unsigned → failed, nothing emitted'],
  injection:   ['✓ T1 injection blocked', 'imperative intent quarantined, payload dropped'],
  composition: ['✓ T10 substitution blocked', 'unverified include not load-eligible (C17)'],
};

// ── layout ───────────────────────────────────────────────────────────────────
const W = 800, PAD = 22, BAR = 38;
const FONT = 14.5, LH = 21, COLS = 80;
const BODY_LINES = 11;                 // fixed text rows reserved in the body
const H = BAR + PAD * 2 + BODY_LINES * LH + 8;
const SLOT = 3.0;                      // seconds per scenario
const TOTAL = caps.length * SLOT;

const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const clip = (s) => (s.length > COLS ? s.slice(0, COLS - 1) + '…' : s);

// Colour a single output line by its content.
function lineColor(line) {
  if (/tier:\s*trusted/.test(line)) return '#3fb950';
  if (/tier:\s*failed|mismatch|refused/.test(line)) return '#f85149';
  if (/tier:\s*(known|unsigned)/.test(line)) return '#d29922';
  if (/load_eligible:\s*true|content_verified:\s*true/.test(line)) return '#3fb950';
  if (/load_eligible:\s*false|quarantined|content_verified:\s*mismatch/.test(line)) return '#f85149';
  return '#adbac7';
}

// Build the text rows for one scenario: command, output, blank, verdict.
function rowsFor(cap) {
  const rows = [];
  rows.push({ t: '$ ' + clip(cap.renderCommand), cls: 'cmd' });
  rows.push({ t: '', cls: 'out' });
  const outLines = cap.output.split('\n').filter((l) => l.trim()).slice(0, 6);
  for (const l of outLines) rows.push({ t: clip(l), cls: 'out', color: lineColor(l) });
  return rows;
}

// ── animation: each scenario group is opaque only during its slot ────────────
function groupKeyframes(i) {
  const s = (i * SLOT) / TOTAL * 100;
  const e = ((i + 1) * SLOT) / TOTAL * 100;
  const f = 1.2;                       // fade width in %
  const p = (n) => n.toFixed(2);
  // hidden everywhere except a fade-in/hold/fade-out inside the slot
  return `@keyframes cast${i}{`
    + `0%,${p(Math.max(0, s))}%{opacity:0}`
    + `${p(s + f)}%{opacity:1}`
    + `${p(e - f)}%{opacity:1}`
    + `${p(e)}%,100%{opacity:0}}`;
}

// progress bar segments (one per scenario) lighting up in sequence
function progressKeyframes(i) {
  const s = (i * SLOT) / TOTAL * 100;
  const e = ((i + 1) * SLOT) / TOTAL * 100;
  return `@keyframes seg${i}{`
    + `0%,${(Math.max(0, s)).toFixed(2)}%{opacity:.25}`
    + `${(s + 0.5).toFixed(2)}%{opacity:1}`
    + `${(e).toFixed(2)}%,100%{opacity:.25}}`;
}

let styles = `
  .win{font-family:'JetBrains Mono','SFMono-Regular',Menlo,Consolas,monospace}
  text{dominant-baseline:hanging}
  .cmd{fill:#e6edf3;font-weight:600}
  .out{fill:#adbac7}
  .prompt{fill:#3fb950;font-weight:700}
  .vtag{font-weight:700}
  .vsub{fill:#768390}
  .cursor{fill:#e6edf3;animation:blink 1s steps(2) infinite}
  @keyframes blink{50%{opacity:0}}
`;
for (let i = 0; i < caps.length; i++) { styles += groupKeyframes(i) + '\n' + progressKeyframes(i) + '\n'; }

// ── render groups ────────────────────────────────────────────────────────────
const bodyTop = BAR + PAD;
let groups = '';
caps.forEach((cap, i) => {
  const rows = rowsFor(cap);
  let lines = '';
  rows.forEach((r, j) => {
    const y = bodyTop + j * LH;
    if (r.cls === 'cmd') {
      const cmd = esc(r.t.slice(2));
      lines += `<text x="${PAD}" y="${y}"><tspan class="prompt">$ </tspan><tspan class="cmd">${cmd}</tspan>`
        + `<tspan class="cursor" dx="2">▋</tspan></text>`;
    } else if (r.t) {
      lines += `<text x="${PAD}" y="${y}" class="out" fill="${r.color || '#adbac7'}">${esc(r.t)}</text>`;
    }
  });
  // verdict band near the bottom of the body
  const [vtag, vsub] = CAST_VERDICT[cap.id] || ['✓', ''];
  const vy = bodyTop + (BODY_LINES - 2) * LH;
  const vColor = cap.threat ? '#3fb950' : '#58a6ff';
  lines += `<text x="${PAD}" y="${vy}" class="vtag" fill="${vColor}">${esc(vtag)}</text>`;
  lines += `<text x="${PAD}" y="${vy + LH}" class="vsub">${esc(clip(vsub))}</text>`;

  groups += `<g style="animation:cast${i} ${TOTAL}s infinite">${lines}</g>\n`;
});

// progress segments along the bottom
const segW = (W - PAD * 2 - (caps.length - 1) * 6) / caps.length;
let segs = '';
caps.forEach((_, i) => {
  const x = PAD + i * (segW + 6);
  segs += `<rect x="${x.toFixed(1)}" y="${H - 14}" width="${segW.toFixed(1)}" height="4" rx="2" `
    + `fill="#58a6ff" style="animation:seg${i} ${TOTAL}s infinite"/>`;
});

const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" font-size="${FONT}" role="img" aria-label="Animated terminal cast of kcp render across six trusted-render-pipeline scenarios">
<style>${styles}</style>
<rect width="${W}" height="${H}" rx="10" fill="#0d1117"/>
<rect width="${W}" height="${BAR}" rx="10" fill="#161b22"/>
<rect y="${BAR - 10}" width="${W}" height="10" fill="#161b22"/>
<circle cx="20" cy="${BAR / 2}" r="6" fill="#ff5f57"/>
<circle cx="40" cy="${BAR / 2}" r="6" fill="#febc2e"/>
<circle cx="60" cy="${BAR / 2}" r="6" fill="#28c840"/>
<text x="${W / 2}" y="${BAR / 2 - 7}" text-anchor="middle" class="win" fill="#768390" font-size="13" font-weight="600">kcp render — trusted render pipeline</text>
<g class="win">${groups}</g>
${segs}
</svg>
`;

fs.writeFileSync(OUT, svg);
console.log(`Wrote ${path.relative(process.cwd(), OUT)} — ${caps.length} scenarios, ${TOTAL}s loop, ${(svg.length / 1024).toFixed(1)} KB`);
