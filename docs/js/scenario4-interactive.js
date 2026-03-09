/* ==========================================================================
   KCP Scenario 4 — Interactive Rate-Limit Simulator (Phase 2)
   Ports the Java simulator to browser JS so users can edit knowledge.yaml
   and re-run in real time.  Zero build step — pure ES2022, uses jsyaml CDN.
   ========================================================================== */

// ---------------------------------------------------------------------------
// Default knowledge.yaml (embedded from examples/scenario4-rate-limit-aware)
// ---------------------------------------------------------------------------
const DEFAULT_YAML = `# Rate-Limit-Aware Agent — KCP manifest
# Edit rate_limits values below, then hit Run.

kcp_version: "0.8"
project: rate-limit-demo
version: "1.0.0"
updated: "2026-03-09"
language: en
license: Apache-2.0

trust:
  provenance:
    publisher: "Example Corp Documentation"
    publisher_url: "https://docs.example.com"
    contact: "docs-team@example.com"
  audit:
    agent_must_log: true
    require_trace_context: true

rate_limits:
  default:
    requests_per_minute: 30
    requests_per_day: 1000

auth:
  methods:
    - type: oauth2
      issuer: "https://auth.example.com"
      scopes: ["docs:read", "internal:read", "compliance:read"]
    - type: none

units:

  - id: public-docs
    path: docs/public/index.md
    intent: "Public documentation overview"
    access: public
    rate_limits:
      default:
        requests_per_minute: 120
        requests_per_day: 10000

  - id: api-reference
    path: docs/api/reference.md
    intent: "API endpoints and parameters"
    access: authenticated
    rate_limits:
      default:
        requests_per_minute: 60
        requests_per_day: 5000

  - id: internal-guide
    path: docs/internal/guide.md
    intent: "Internal architecture and runbooks"
    access: restricted
    rate_limits:
      default:
        requests_per_minute: 10
        requests_per_day: 200

  - id: compliance-data
    path: docs/compliance/audit-records.md
    intent: "Compliance audit records"
    access: restricted
    rate_limits:
      default:
        requests_per_minute: 2
        requests_per_day: 20
`;

// ---------------------------------------------------------------------------
// ManifestParser — extract units with effective rate limits from parsed YAML
// ---------------------------------------------------------------------------
function parseManifest(yamlText) {
  const data = jsyaml.load(yamlText);
  if (!data || typeof data !== 'object') throw new Error('YAML did not produce an object');

  const rootRL = extractRateLimit(data.rate_limits);
  const rawUnits = data.units ?? [];

  const units = rawUnits.map(raw => {
    const unitRL = raw.rate_limits ? extractRateLimit(raw.rate_limits) : null;
    const effective = unitRL ?? rootRL;
    return {
      id:     raw.id ?? 'unknown',
      path:   raw.path ?? '',
      intent: raw.intent ?? '',
      access: raw.access ?? 'public',
      rateLimit: effective,
    };
  });

  return { rootRateLimit: rootRL, units };
}

function extractRateLimit(block) {
  if (!block) return { requestsPerMinute: null, requestsPerDay: null };
  const def = block.default ?? block;
  const rpm = typeof def.requests_per_minute === 'number' ? def.requests_per_minute : null;
  const rpd = typeof def.requests_per_day === 'number' ? def.requests_per_day : null;
  return { requestsPerMinute: rpm, requestsPerDay: rpd };
}

// ---------------------------------------------------------------------------
// RequestBudget — per-unit budget tracker (simulated time, no real clock)
// ---------------------------------------------------------------------------
class RequestBudget {
  #limits;          // Map<unitId, {requestsPerMinute, requestsPerDay}>
  #minuteCounts;    // Map<unitId, number>
  #dayCounts;       // Map<unitId, number>
  #minuteStarts;    // Map<unitId, number> (simulated ms)
  #dayStarts;       // Map<unitId, number>
  #now;             // current simulated time in ms

  constructor(limits, startMs = 0) {
    this.#limits = new Map(Object.entries(limits));
    this.#minuteCounts = new Map();
    this.#dayCounts = new Map();
    this.#minuteStarts = new Map();
    this.#dayStarts = new Map();
    this.#now = startMs;
  }

  setTime(ms) { this.#now = ms; }

  isWithinLimit(unitId) {
    const limit = this.#limits.get(unitId) ?? { requestsPerMinute: null, requestsPerDay: null };
    if (limit.requestsPerMinute === null && limit.requestsPerDay === null) return true;

    this.#resetIfNeeded(unitId);

    if (limit.requestsPerMinute !== null) {
      if ((this.#minuteCounts.get(unitId) ?? 0) >= limit.requestsPerMinute) return false;
    }
    if (limit.requestsPerDay !== null) {
      if ((this.#dayCounts.get(unitId) ?? 0) >= limit.requestsPerDay) return false;
    }
    return true;
  }

  recordRequest(unitId) {
    this.#resetIfNeeded(unitId);
    this.#minuteCounts.set(unitId, (this.#minuteCounts.get(unitId) ?? 0) + 1);
    this.#dayCounts.set(unitId, (this.#dayCounts.get(unitId) ?? 0) + 1);
  }

  getMinuteUsed(unitId) { return this.#minuteCounts.get(unitId) ?? 0; }
  getDayUsed(unitId) { return this.#dayCounts.get(unitId) ?? 0; }

  getLimit(unitId) {
    return this.#limits.get(unitId) ?? { requestsPerMinute: null, requestsPerDay: null };
  }

  getSecondsUntilMinuteReset(unitId) {
    const limit = this.getLimit(unitId);
    if (limit.requestsPerMinute === null) return 0;
    const start = this.#minuteStarts.get(unitId);
    if (start === undefined) return 0;
    const elapsed = this.#now - start;
    if (elapsed >= 60_000) return 0;
    return Math.ceil((60_000 - elapsed) / 1000);
  }

  #resetIfNeeded(unitId) {
    const now = this.#now;
    // Minute window
    const minStart = this.#minuteStarts.get(unitId);
    if (minStart === undefined || (now - minStart) >= 60_000) {
      this.#minuteStarts.set(unitId, now);
      this.#minuteCounts.set(unitId, 0);
    }
    // Day window
    const dayStart = this.#dayStarts.get(unitId);
    if (dayStart === undefined || (now - dayStart) >= 86_400_000) {
      this.#dayStarts.set(unitId, now);
      this.#dayCounts.set(unitId, 0);
    }
  }
}

// ---------------------------------------------------------------------------
// PoliteAgent — respects advisory rate limits, throttles when exceeded
// ---------------------------------------------------------------------------
function runPoliteAgent(units, requestsPerUnit, budget) {
  const log = [];
  let time = 0;
  let throttleCount = 0;
  let requestCount = 0;

  for (const unit of units) {
    for (let i = 0; i < requestsPerUnit; i++) {
      budget.setTime(time);

      if (!budget.isWithinLimit(unit.id)) {
        let waitSec = budget.getSecondsUntilMinuteReset(unit.id);
        if (waitSec <= 0) waitSec = 60;
        throttleCount++;

        const limit = budget.getLimit(unit.id);
        log.push({
          type: 'throttle',
          unit: unit.id,
          text: `Throttling: unit=${unit.id}, used=${budget.getMinuteUsed(unit.id)}/min, limit=${limit.requestsPerMinute}/min \u2014 waiting ${waitSec}s`,
        });

        time += waitSec * 1000;
        budget.setTime(time);
      }

      budget.recordRequest(unit.id);
      requestCount++;
      log.push({
        type: 'request',
        unit: unit.id,
        text: `Request: unit=${unit.id}, access=${unit.access}, request #${i + 1}`,
      });
      time += 100;
    }
  }

  log.push({
    type: 'summary',
    text: `\u2500\u2500 Summary: ${requestCount} requests, ${throttleCount} throttled, 0 violations`,
  });

  return { log, throttleCount, requestCount, violations: 0 };
}

// ---------------------------------------------------------------------------
// GreedyAgent — ignores advisory rate limits, logs violations
// ---------------------------------------------------------------------------
function runGreedyAgent(units, requestsPerUnit, budget) {
  const log = [];
  let time = 0;
  let violationCount = 0;
  let requestCount = 0;

  for (const unit of units) {
    for (let i = 0; i < requestsPerUnit; i++) {
      budget.setTime(time);
      const withinLimit = budget.isWithinLimit(unit.id);
      budget.recordRequest(unit.id);
      requestCount++;

      if (!withinLimit) {
        violationCount++;
        const limit = budget.getLimit(unit.id);
        const rpm = limit.requestsPerMinute ?? '\u221e';
        log.push({
          type: 'violation',
          unit: unit.id,
          text: `ADVISORY VIOLATION: unit=${unit.id}, used=${budget.getMinuteUsed(unit.id)}/min, limit=${rpm}/min`,
        });
      } else {
        log.push({
          type: 'request',
          unit: unit.id,
          text: `Request: unit=${unit.id}, access=${unit.access}, request #${i + 1}`,
        });
      }
      time += 10;
    }
  }

  log.push({
    type: 'summary',
    text: `\u2500\u2500 Summary: ${requestCount} requests, ${violationCount} ADVISORY VIOLATIONS`,
  });

  return { log, throttleCount: 0, requestCount, violations: violationCount };
}

// ---------------------------------------------------------------------------
// Run both agents and return results
// ---------------------------------------------------------------------------
function runSimulation(yamlText, requestsPerUnit) {
  const manifest = parseManifest(yamlText);

  // Build limit maps for each budget instance
  const limitMap = {};
  for (const u of manifest.units) {
    limitMap[u.id] = u.rateLimit;
  }

  const politeBudget = new RequestBudget(limitMap, 0);
  const greedyBudget = new RequestBudget(limitMap, 0);

  const polite = runPoliteAgent(manifest.units, requestsPerUnit, politeBudget);
  const greedy = runGreedyAgent(manifest.units, requestsPerUnit, greedyBudget);

  return { polite, greedy, units: manifest.units };
}

// ---------------------------------------------------------------------------
// DOM rendering helpers
// ---------------------------------------------------------------------------
function lineClass(entry) {
  switch (entry.type) {
    case 'throttle':  return 'sim-line--throttle';
    case 'violation': return 'sim-line--violation';
    case 'summary':   return 'sim-line--summary';
    default:          return 'sim-line--request';
  }
}

function renderLog(entries, container, summaryEl) {
  container.innerHTML = '';
  summaryEl.textContent = '';

  let idx = 0;
  const total = entries.length;

  function renderNext() {
    if (idx >= total) return;
    const entry = entries[idx];
    idx++;

    if (entry.type === 'summary') {
      summaryEl.textContent = entry.text;
      return;
    }

    const div = document.createElement('div');
    div.className = `sim-line ${lineClass(entry)}`;
    div.textContent = entry.text;
    container.appendChild(div);
    container.scrollTop = container.scrollHeight;

    requestAnimationFrame(() => setTimeout(renderNext, 30));
  }

  renderNext();
}

function renderError(msg, politeOut, greedyOut, politeSummary, greedySummary) {
  const errHtml = `<div class="sim-line sim-line--violation">Invalid YAML: ${escapeHtml(msg)}</div>`;
  politeOut.innerHTML = errHtml;
  greedyOut.innerHTML = errHtml;
  politeSummary.textContent = '';
  greedySummary.textContent = '';
}

function escapeHtml(str) {
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// ---------------------------------------------------------------------------
// Initialization — wire up DOM once page is ready
// ---------------------------------------------------------------------------
function initInteractiveSimulator() {
  const editor       = document.getElementById('sim-yaml-editor');
  const resetBtn     = document.getElementById('sim-reset-yaml');
  const runBtn       = document.getElementById('sim-run-btn');
  const reqSlider    = document.getElementById('sim-req-count');
  const reqCountVal  = document.getElementById('sim-req-count-val');
  const politeOut    = document.getElementById('sim-polite-output');
  const greedyOut    = document.getElementById('sim-greedy-output');
  const politeSummary = document.getElementById('sim-polite-summary');
  const greedySummary = document.getElementById('sim-greedy-summary');

  if (!editor) return; // guard: element not yet on page

  // Populate editor with default YAML
  editor.value = DEFAULT_YAML;

  // Slider value display
  reqSlider.addEventListener('input', () => {
    reqCountVal.textContent = reqSlider.value;
  });

  // Reset button
  resetBtn.addEventListener('click', () => {
    editor.value = DEFAULT_YAML;
    reqSlider.value = 8;
    reqCountVal.textContent = '8';
    executeRun();
  });

  // Run button
  runBtn.addEventListener('click', executeRun);

  // Ctrl+Enter / Cmd+Enter to run
  editor.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault();
      executeRun();
    }
  });

  function executeRun() {
    const yamlText = editor.value;
    const reqCount = parseInt(reqSlider.value, 10);

    try {
      const result = runSimulation(yamlText, reqCount);
      renderLog(result.polite.log, politeOut, politeSummary);
      renderLog(result.greedy.log, greedyOut, greedySummary);
    } catch (err) {
      renderError(err.message, politeOut, greedyOut, politeSummary, greedySummary);
    }
  }

  // Auto-run on load so output is visible immediately
  executeRun();
}

// Run when DOM is ready
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initInteractiveSimulator);
} else {
  initInteractiveSimulator();
}
