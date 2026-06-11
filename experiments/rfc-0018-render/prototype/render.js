#!/usr/bin/env node
// Prototype renderer for RFC-0018 (draft-02). Experimental — exists to
// validate the RFC's rules against a corpus, not to be the reference
// implementation.
//
// Usage:
//   node render.js <knowledge.yaml> --keys <trusted-keys.yaml> \
//        --origin <origin-string> --out <output.yaml>
//
// Exit codes: 0 = rendered; 2 = failed tier (nothing emitted); 1 = error.
//
// Signature stand-in: a detached `<manifest>.sig` JSON file
// { key_id, algorithm: "EdDSA", public_key: <base64 raw ed25519 spki>,
//   signature: <base64> } over the exact manifest bytes. This models the
// detached-JWS profile of §4.2 without pulling in a JOSE library.

import fs from 'node:fs';
import crypto from 'node:crypto';
import yaml from 'js-yaml';
import { lintFreeText, LINT_RULES_VERSION } from './lint.js';

const RENDERER = 'kcp-render-prototype 0.1.0 (rfc-0018-draft-02 experiment)';
const RENDER_SCHEMA = 'kcp-render-schema-0.1';

const KNOWN_KINDS = ['knowledge', 'schema', 'policy', 'service', 'executable'];
const NEVER_LOAD_KINDS = ['service', 'executable'];

// Render-schema whitelist (§6.1): identifiers, paths, enums, dates,
// bounded-semantics fields only.
const TOP_SCALAR_FIELDS = ['project', 'version', 'updated', 'language', 'license'];
const UNIT_FIELDS = [
  'id', 'kind', 'path', 'intent', 'format', 'content_type', 'language',
  'scope', 'audience', 'license', 'validated', 'update_frequency',
  'triggers', 'not_for',
];
const UNIT_FREE_TEXT_FIELDS = ['intent', 'description', 'label'];
const RELATIONSHIP_FIELDS = ['from', 'to', 'type'];
const FEDERATION_FIELDS = ['id', 'url', 'relationship'];
const PROVENANCE_FIELDS = ['publisher', 'publisher_url', 'contact'];
const TIER_CONFIDENCE = { trusted: 0.7, known: 0.6, unsigned: 0.5 }; // §5.1 bounds

function parseArgs(argv) {
  const args = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) args[argv[i].slice(2)] = argv[++i];
    else args._.push(argv[i]);
  }
  return args;
}

function sha256(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
}

// §9: exact per-path-segment matching — no bare prefix match.
function scopeCovers(domain, origin) {
  return origin === domain || origin.startsWith(domain + '/');
}

function loadAllowlist(keysPath) {
  if (!keysPath || !fs.existsSync(keysPath)) return { keys: [] };
  return yaml.load(fs.readFileSync(keysPath, 'utf8')) || { keys: [] };
}

function originIsPinned(allowlist, origin) {
  return (allowlist.keys || []).some((k) =>
    (k.scope?.domains || []).some((d) => scopeCovers(d, origin)));
}

function verifyDetachedSig(manifestBytes, sig) {
  try {
    const pub = crypto.createPublicKey({
      key: Buffer.from(sig.public_key, 'base64'),
      format: 'der',
      type: 'spki',
    });
    return crypto.verify(null, manifestBytes, pub, Buffer.from(sig.signature, 'base64'));
  } catch {
    return false;
  }
}

// §4 + §4.1 tier computation.
function computeTier(manifestBytes, sigPath, allowlist, origin) {
  const pinned = originIsPinned(allowlist, origin);
  if (!fs.existsSync(sigPath)) {
    if (pinned) {
      return { tier: 'failed', pinned, status: 'absent',
               reason: 'unsigned manifest from pinned origin (§4.1)' };
    }
    return { tier: 'unsigned', pinned, status: 'absent' };
  }
  const sig = JSON.parse(fs.readFileSync(sigPath, 'utf8'));
  if (!verifyDetachedSig(manifestBytes, sig)) {
    return { tier: 'failed', pinned, status: 'invalid',
             reason: 'signature verification failed' };
  }
  const entry = (allowlist.keys || []).find(
    (k) => k.key_id === sig.key_id && k.public_key === sig.public_key);
  if (!entry) {
    // T4: valid signature, unknown key — gate, don't endorse.
    return { tier: 'known', pinned, status: 'unknown-key', keyId: sig.key_id };
  }
  const domains = entry.scope?.domains;
  if (domains && !domains.some((d) => scopeCovers(d, origin))) {
    // Allowlisted key used outside its declared scope (§9): the key may
    // not verify this origin, so it confers no allowlist standing here.
    return { tier: 'known', pinned, status: 'unknown-key', keyId: sig.key_id,
             note: 'key valid but scoped to other origins' };
  }
  return { tier: 'trusted', pinned, status: 'valid', keyId: sig.key_id,
           keySource: 'allowlist' };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const manifestPath = args._[0];
  if (!manifestPath) {
    console.error('usage: render.js <knowledge.yaml> --keys K --origin O --out OUT');
    process.exit(1);
  }
  const manifestBytes = fs.readFileSync(manifestPath);
  const allowlist = loadAllowlist(args.keys);
  const origin = args.origin || 'unknown';

  const trust = computeTier(manifestBytes, manifestPath + '.sig', allowlist, origin);
  if (trust.tier === 'failed') {
    // Fail-closed (§3.1, R4, C2): emit nothing.
    console.error(`render refused: tier=failed (${trust.reason})`);
    process.exit(2);
  }

  const doc = yaml.load(manifestBytes.toString('utf8')) || {};
  const dropped = [];
  const quarantined = [];
  let fieldsIn = 0;
  let fieldsRendered = 0;
  // Leaf-based counters so the R-block identity holds:
  // fields_in = fields_rendered + fields_dropped + fields_quarantined.
  // (The RFC's stats semantics are entry-vs-leaf ambiguous; see RESULTS.md.)
  let fieldsDropped = 0;
  let fieldsQuarantined = 0;

  // Every input leaf is counted exactly once as rendered, dropped,
  // quarantined, or consumed-by-renderer (signing/provenance metadata).
  const countLeaves = (v) => {
    if (Array.isArray(v)) return v.every((x) => typeof x !== 'object') ? 1
      : v.reduce((n, x) => n + countLeaves(x), 0);
    if (v && typeof v === 'object')
      return Object.values(v).reduce((n, x) => n + countLeaves(x), 0);
    return 1;
  };

  const take = (src, allowed, basePath, out, freeTextFields = []) => {
    for (const [k, v] of Object.entries(src)) {
      const leafCount = countLeaves(v);
      fieldsIn += leafCount;
      if (!allowed.includes(k)) {
        dropped.push({ path: `${basePath}${k}`, reason: 'not_in_schema' });
        fieldsDropped += leafCount;
        continue;
      }
      if (freeTextFields.includes(k)) {
        const verdict = lintFreeText(v);
        if (verdict.flagged) {
          fieldsQuarantined += leafCount;
          quarantined.push({
            path: `${basePath}${k}`,
            reason: 'imperative_mood',
            rule: verdict.rule,
            original_sha256: sha256(Buffer.from(String(v), 'utf8')),
            action: 'held_for_review',
          });
          continue;
        }
      }
      out[k] = v;
      fieldsRendered += leafCount;
    }
  };

  // --- project block -------------------------------------------------
  const project = {};
  const topScalars = {};
  for (const f of TOP_SCALAR_FIELDS) if (doc[f] !== undefined) topScalars[f] = doc[f];
  take(topScalars, TOP_SCALAR_FIELDS, '', project);
  if (project.project !== undefined) {
    project.name = project.project;
    delete project.project;
  }

  // --- units ----------------------------------------------------------
  const units = [];
  (doc.units || []).forEach((unit, i) => {
    const out = {};
    const base = `units[${i}].`;
    // kind is enum-checked before the generic whitelist pass
    let kind = unit.kind === undefined ? 'knowledge' : unit.kind;
    let unknownKind = false;
    if (!KNOWN_KINDS.includes(kind)) {
      // §6.3: unknown kinds fail closed in the renderer (diverges from
      // SPEC.md §4.3a parser leniency, deliberately).
      fieldsIn += 1;
      fieldsDropped += 1;
      dropped.push({ path: `${base}kind`, reason: 'unknown_kind' });
      unknownKind = true;
    }
    const rest = { ...unit };
    if (unknownKind) delete rest.kind;
    take(rest, UNIT_FIELDS, base, out, UNIT_FREE_TEXT_FIELDS);
    // §6.3 load eligibility
    if (unknownKind || NEVER_LOAD_KINDS.includes(kind)) {
      out.load_eligible = false;
      out.invocation = 'explicit';
    } else {
      out.load_eligible = trust.tier === 'trusted';
    }
    units.push(out);
  });

  // --- relationships ---------------------------------------------------
  const relationships = [];
  (doc.relationships || []).forEach((rel, i) => {
    const out = {};
    take(rel, RELATIONSHIP_FIELDS, `relationships[${i}].`, out);
    relationships.push(out);
  });

  // --- federation (§7) --------------------------------------------------
  const federation = [];
  (doc.manifests || []).forEach((edge, i) => {
    const out = {};
    take(edge, FEDERATION_FIELDS, `manifests[${i}].`, out);
    out.target_tier = 'unrendered'; // trust never inherited
    federation.push(out);
  });

  // --- trust passthrough -------------------------------------------------
  let provenance;
  if (doc.trust) {
    for (const [k, v] of Object.entries(doc.trust)) {
      const leafCount = countLeaves(v);
      fieldsIn += leafCount;
      if (k === 'provenance') {
        provenance = {};
        for (const [pk, pv] of Object.entries(v)) {
          if (PROVENANCE_FIELDS.includes(pk)) { provenance[pk] = pv; fieldsRendered += countLeaves(pv); }
          else { dropped.push({ path: `trust.provenance.${pk}`, reason: 'not_in_schema' }); fieldsDropped += countLeaves(pv); }
        }
      } else if (k === 'content_integrity') {
        // consumed by tier evaluation, never re-emitted
        dropped.push({ path: `trust.${k}`, reason: 'consumed_by_renderer' });
        fieldsDropped += leafCount;
      } else {
        dropped.push({ path: `trust.${k}`, reason: 'not_in_schema' });
        fieldsDropped += leafCount;
      }
    }
  }

  // --- remaining top-level blocks ----------------------------------------
  const handled = new Set([...TOP_SCALAR_FIELDS, 'kcp_version', 'units',
    'relationships', 'manifests', 'trust']);
  for (const [k, v] of Object.entries(doc)) {
    if (handled.has(k)) continue;
    fieldsIn += countLeaves(v);
    fieldsDropped += countLeaves(v);
    dropped.push({ path: k, reason: 'not_in_schema' });
  }

  // --- assemble (deterministic order; no timestamp by default, C1) -------
  const output = {
    render: {
      kcp_version: String(doc.kcp_version ?? 'unspecified'),
      renderer: RENDERER,
      lint_rules: LINT_RULES_VERSION,
      source: { path: manifestPath.split('/').pop(), sha256: sha256(manifestBytes) },
    },
    trust: {
      tier: trust.tier,
      origin,
      pinned: trust.pinned,
      signature: {
        method: 'jws',
        algorithm: 'EdDSA',
        ...(trust.keyId ? { key_id: trust.keyId } : {}),
        ...(trust.keySource ? { key_source: trust.keySource } : {}),
        status: trust.status,
      },
      ...(provenance && Object.keys(provenance).length ? { provenance } : {}),
    },
    discovery: {
      verification_status: 'declared',
      source: 'manifest-self-description',
      confidence: TIER_CONFIDENCE[trust.tier],
    },
    project,
    units,
    ...(relationships.length ? { relationships } : {}),
    ...(federation.length ? { federation } : {}),
    sanitization: {
      schema: RENDER_SCHEMA,
      dropped,
      quarantined,
      stats: {
        fields_in: fieldsIn,
        fields_rendered: fieldsRendered,
        fields_dropped: fieldsDropped,
        fields_quarantined: fieldsQuarantined,
      },
    },
  };
  if (args.timestamp) output.render.rendered_at = new Date().toISOString();

  const text = yaml.dump(output, { lineWidth: -1, noRefs: true });
  if (args.out) fs.writeFileSync(args.out, text);
  else process.stdout.write(text);
}

main();
