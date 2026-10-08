#!/usr/bin/env node
// Classify directly declared Persist.global/window/workspace/server keys in a SPA bundle.
// Print unclassified/missing keys, write "<count> <failed>", and fail closed on either.
import { readFileSync, writeFileSync } from 'node:fs';

const classified = new Set([
  // Origin-owned onboarding/account notices and extension/layout state, never mirrored.
  'global:chatgpt-plan-welcome.v1',
  'global:chatgpt-usage-limit',
  'global:command.catalog.v1',
  'global:extension.servers',
  'global:go-upsell',
  'global:home.servers',
  'global:language',
  'global:layout',
  'global:layout.scoped',
  'global:model',
  'global:new-session.provider-tip',
  'global:new-session.workspace-tip',
  'global:open.app',
  'global:prompt-history',
  'global:prompt-history-shell',
  'global:review-panel-v2',
  'global:server',
  'global:workspace-onboarding',
  'window:tabs',
  'window:tabs.closed',
  'window:tabs.info',
  'window:tabs.panes',
  'window:tabs.recent',
]);

const [bundlePath, countPath] = process.argv.slice(2);
if (!bundlePath || !countPath) {
  console.error('Usage: node classify-persist-keys.mjs <bundle.js> <count-file>');
  process.exit(1);
}
const text = readFileSync(bundlePath, 'utf8');
// The identifier boundary avoids retrying at every byte of long embedded base64 literals.
const pattern = /(?<![\w$])[A-Za-z_$][\w$]*\.(global|window|workspace|server)\((?:"([^"]+)"|'([^']+)'|`([^`]+)`)/g;
const keys = new Set();
for (const match of text.matchAll(pattern)) keys.add(`${match[1]}:${match[2] ?? match[3] ?? match[4]}`);

let failed = 0;
if (!keys.has('global:home.servers')) {
  console.log('MISSING: Persist.global(home.servers)');
  failed++;
}
for (const key of keys) {
  if (classified.has(key)) continue;
  console.log(`UNCLASSIFIED PERSIST KEY: ${key}`);
  failed++;
}
writeFileSync(countPath, `${keys.size} ${failed}\n`);
process.exitCode = failed ? 1 : 0;
