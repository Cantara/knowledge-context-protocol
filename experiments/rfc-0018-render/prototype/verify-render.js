#!/usr/bin/env node
// §3.4 consumer-side check (C10): a rendered artifact is only consumable
// if its recorded source hash matches the live manifest bytes. Models the
// runtime rule "never ingest a render you did not produce or verify
// in-session".
//
// Usage: node verify-render.js <rendered.yaml> <knowledge.yaml>
// Exit codes: 0 = verified; 3 = rejected.

import fs from 'node:fs';
import crypto from 'node:crypto';
import yaml from 'js-yaml';

const [renderedPath, manifestPath] = process.argv.slice(2);
if (!renderedPath || !manifestPath) {
  console.error('usage: verify-render.js <rendered.yaml> <knowledge.yaml>');
  process.exit(1);
}

const rendered = yaml.load(fs.readFileSync(renderedPath, 'utf8'));
const recorded = rendered?.render?.source?.sha256;
const actual = crypto.createHash('sha256')
  .update(fs.readFileSync(manifestPath)).digest('hex');

if (!recorded) {
  console.error('reject: artifact records no render.source.sha256');
  process.exit(3);
}
if (recorded !== actual) {
  console.error(`reject: source hash mismatch (recorded ${recorded.slice(0, 12)}…, live ${actual.slice(0, 12)}…)`);
  process.exit(3);
}
console.log('verified: source hash matches live manifest');
process.exit(0);
