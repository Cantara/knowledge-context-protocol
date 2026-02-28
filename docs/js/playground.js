/**
 * KCP — Playground: in-browser knowledge.yaml validator
 * Depends on: Ajv 6 (global Ajv), js-yaml 4 (global jsyaml)
 */
(function () {
  'use strict';

  // ── Inlined JSON Schema (mirrors schema/knowledge-schema.json) ──────────────
  var KCP_SCHEMA = {"$schema":"http://json-schema.org/draft-07/schema#","$id":"https://cantara.github.io/knowledge-context-protocol/schema/knowledge-schema.json","title":"Knowledge Context Protocol Manifest","type":"object","required":["project","units"],"additionalProperties":true,"properties":{"kcp_version":{"type":"string","enum":["0.1","0.2","0.3"]},"project":{"type":"string","minLength":1},"version":{"type":"string","pattern":"^[0-9]"},"updated":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"},"language":{"type":"string","minLength":2,"maxLength":35},"license":{"oneOf":[{"type":"string","minLength":1},{"$ref":"#/definitions/license_object"}]},"indexing":{"oneOf":[{"type":"string","enum":["open","read-only","no-train","none"]},{"$ref":"#/definitions/indexing_object"}]},"units":{"type":"array","minItems":1,"items":{"$ref":"#/definitions/unit"}},"relationships":{"type":"array","items":{"$ref":"#/definitions/relationship"}}},"definitions":{"license_object":{"type":"object","additionalProperties":true,"properties":{"spdx":{"type":"string","minLength":1},"url":{"type":"string"},"attribution_required":{"type":"boolean"}}},"indexing_object":{"type":"object","additionalProperties":true,"properties":{"allow":{"type":"array","items":{"type":"string"}},"deny":{"type":"array","items":{"type":"string"}},"attribution_required":{"type":"boolean"}}},"unit":{"type":"object","required":["id","path","intent","scope","audience"],"additionalProperties":true,"properties":{"id":{"type":"string","pattern":"^[a-z0-9.\\-]+$","minLength":1},"path":{"type":"string","minLength":1,"not":{"pattern":"^/"}},"kind":{"type":"string","enum":["knowledge","schema","service","policy","executable"]},"intent":{"type":"string","minLength":1},"format":{"type":"string"},"content_type":{"type":"string"},"language":{"type":"string","minLength":2,"maxLength":35},"scope":{"type":"string","enum":["global","project","module"]},"audience":{"type":"array","items":{"type":"string"},"minItems":0},"license":{"oneOf":[{"type":"string","minLength":1},{"$ref":"#/definitions/license_object"}]},"validated":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"},"update_frequency":{"type":"string","enum":["hourly","daily","weekly","monthly","rarely","never"]},"indexing":{"oneOf":[{"type":"string","enum":["open","read-only","no-train","none"]},{"$ref":"#/definitions/indexing_object"}]},"depends_on":{"type":"array","items":{"type":"string","pattern":"^[a-z0-9.\\-]+"}},"supersedes":{"type":"string","pattern":"^[a-z0-9.\\-]+$"},"triggers":{"type":"array","items":{"type":"string","maxLength":60},"maxItems":20}}},"relationship":{"type":"object","required":["from","to","type"],"additionalProperties":true,"properties":{"from":{"type":"string","pattern":"^[a-z0-9.\\-]+$"},"to":{"type":"string","pattern":"^[a-z0-9.\\-]+$"},"type":{"type":"string"}}}}};

  // ── Example manifests ────────────────────────────────────────────────────────
  var EXAMPLES = {
    level1: [
      'kcp_version: "0.3"',
      'project: my-project',
      'version: 1.0.0',
      '',
      'units:',
      '  - id: overview',
      '    path: README.md',
      '    intent: "What is this project and how do I get started?"',
      '    scope: global',
      '    audience: [human, agent]'
    ].join('\n'),

    level2: [
      'kcp_version: "0.3"',
      'project: my-project',
      'version: 1.0.0',
      'updated: "2026-02-28"',
      '',
      'units:',
      '  - id: overview',
      '    path: README.md',
      '    intent: "What is this project and how do I get started?"',
      '    scope: global',
      '    audience: [human, agent]',
      '    validated: "2026-02-15"',
      '',
      '  - id: api-guide',
      '    path: docs/api.md',
      '    intent: "How do I authenticate and call the API?"',
      '    scope: module',
      '    audience: [developer, agent]',
      '    kind: knowledge',
      '    format: markdown',
      '    language: en',
      '    depends_on: [overview]',
      '    validated: "2026-02-20"'
    ].join('\n'),

    level3: [
      'kcp_version: "0.3"',
      'project: enterprise-wiki',
      'version: 2.0.0',
      'updated: "2026-02-28"',
      'language: en',
      'license: "Apache-2.0"',
      'indexing: open',
      '',
      'units:',
      '  - id: overview',
      '    path: README.md',
      '    intent: "What is this project and how do I get started?"',
      '    scope: global',
      '    audience: [human, agent]',
      '    validated: "2026-02-15"',
      '    update_frequency: monthly',
      '',
      '  - id: api-reference',
      '    path: docs/api.md',
      '    intent: "What endpoints does the API expose?"',
      '    scope: module',
      '    audience: [developer, agent]',
      '    kind: schema',
      '    format: openapi',
      '    depends_on: [overview]',
      '    validated: "2026-02-20"',
      '    triggers: [api, endpoints, authentication, oauth2]',
      '',
      'relationships:',
      '  - from: overview',
      '    to: api-reference',
      '    type: enables'
    ].join('\n'),

    invalid: [
      'kcp_version: "0.3"',
      'project: broken-manifest',
      '',
      'units:',
      '  - id: INVALID ID WITH SPACES',
      '    path: /absolute/path/not/allowed.md',
      '    intent: "This manifest has intentional errors."',
      '    scope: not-a-valid-scope',
      '    audience: [human]'
    ].join('\n')
  };

  // ── DOM refs ─────────────────────────────────────────────────────────────────
  var textarea   = document.getElementById('yaml-input');
  var output     = document.getElementById('playground-output');
  var validateBtn = document.getElementById('validate-btn');
  var exampleSel  = document.getElementById('example-select');

  if (!textarea || !output) return;

  // ── Conformance level detection ──────────────────────────────────────────────
  function detectLevel(data) {
    if (!data.units || !data.units.length) return 1;
    var units = data.units;
    var hasL2 = units.some(function (u) {
      return u.validated || u.depends_on || u.kind || u.format || u.language;
    });
    var hasL3 = (data.relationships && data.relationships.length > 0) ||
      units.some(function (u) {
        return u.triggers && u.triggers.length > 0;
      });
    if (hasL3) return 3;
    if (hasL2) return 2;
    return 1;
  }

  // ── Validation ───────────────────────────────────────────────────────────────
  function run() {
    var raw = textarea.value.trim();
    if (!raw) {
      setOutput('<div class="pg-note">Paste a <code>knowledge.yaml</code> above and click Validate.</div>');
      return;
    }

    var data;
    try {
      data = jsyaml.load(raw);
    } catch (e) {
      setOutput(errorBlock('YAML parse error', [e.message]));
      return;
    }

    if (!data || typeof data !== 'object' || Array.isArray(data)) {
      setOutput(errorBlock('Invalid structure', ['A knowledge.yaml must be a YAML mapping (key: value), not a scalar or list.']));
      return;
    }

    var ajv = new Ajv({ allErrors: true });
    var validate = ajv.compile(KCP_SCHEMA);
    var valid = validate(data);

    if (valid) {
      var level = detectLevel(data);
      var unitCount = (data.units || []).length;
      var relCount  = (data.relationships || []).length;
      setOutput(successBlock(data.project, level, unitCount, relCount));
    } else {
      var msgs = (validate.errors || []).map(function (e) {
        var path = e.dataPath || '';
        return path ? path + ': ' + e.message : e.message;
      });
      setOutput(errorBlock(msgs.length + ' validation error' + (msgs.length !== 1 ? 's' : ''), msgs));
    }
  }

  // ── Output helpers ───────────────────────────────────────────────────────────
  function setOutput(html) {
    output.innerHTML = html;
  }

  function escHtml(s) {
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  }

  function successBlock(project, level, units, rels) {
    var levelLabel = ['', 'Minimal (L1)', 'Standard (L2)', 'Full (L3)'][level] || 'L' + level;
    return [
      '<div class="pg-result pg-result--ok">',
      '  <div class="pg-result__icon">',
      '    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" width="22" height="22"><polyline points="20 6 9 17 4 12"/></svg>',
      '  </div>',
      '  <div class="pg-result__body">',
      '    <p class="pg-result__title">Valid</p>',
      '    <p class="pg-result__project">' + escHtml(project || 'unnamed') + '</p>',
      '    <ul class="pg-result__meta">',
      '      <li>Conformance: <strong>' + levelLabel + '</strong></li>',
      '      <li>' + units + ' unit' + (units !== 1 ? 's' : '') + '</li>',
      (rels > 0 ? '      <li>' + rels + ' relationship' + (rels !== 1 ? 's' : '') + '</li>' : ''),
      '    </ul>',
      '  </div>',
      '</div>'
    ].join('\n');
  }

  function errorBlock(title, messages) {
    var items = messages.map(function (m) {
      return '<li>' + escHtml(m) + '</li>';
    }).join('');
    return [
      '<div class="pg-result pg-result--error">',
      '  <div class="pg-result__icon">',
      '    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" width="22" height="22"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>',
      '  </div>',
      '  <div class="pg-result__body">',
      '    <p class="pg-result__title">' + escHtml(title) + '</p>',
      '    <ul class="pg-result__errors">' + items + '</ul>',
      '  </div>',
      '</div>'
    ].join('\n');
  }

  // ── Wire up ──────────────────────────────────────────────────────────────────
  if (validateBtn) {
    validateBtn.addEventListener('click', run);
  }

  textarea.addEventListener('keydown', function (e) {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') run();
  });

  // Auto-validate with debounce (400ms after typing stops)
  var debounceTimer;
  textarea.addEventListener('input', function () {
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(run, 400);
  });

  if (exampleSel) {
    exampleSel.addEventListener('change', function () {
      var key = this.value;
      if (key && EXAMPLES[key]) {
        textarea.value = EXAMPLES[key];
        setOutput('<div class="pg-note">Click <strong>Validate</strong> or press <kbd>Ctrl+Enter</kbd> to check this example.</div>');
      }
    });
  }

  // Pre-load Level 1 example
  if (textarea.value.trim() === '') {
    textarea.value = EXAMPLES.level1;
  }

})();
