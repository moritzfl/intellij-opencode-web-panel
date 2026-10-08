import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const script = fileURLToPath(new URL('./classify-persist-keys.mjs', import.meta.url));
function classify(bundle) {
  const base = path.join(tmpdir(), 'opencode');
  mkdirSync(base, { recursive: true });
  const root = mkdtempSync(path.join(base, 'persist-contract-'));
  try {
    const input = path.join(root, 'bundle.js');
    const count = path.join(root, 'count');
    writeFileSync(input, bundle);
    const result = spawnSync(process.execPath, [script, input, count], { encoding: 'utf8', timeout: 5000 });
    assert.ifError(result.error);
    assert.equal(result.stderr, '');
    return { status: result.status, output: result.stdout, count: readFileSync(count, 'utf8').trim() };
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

test('deduplicates declarations across minifier quote styles and dollar identifiers', () => {
  assert.deepEqual(classify('$.global("home.servers"); $a.global(`home.servers`); a$.global(\'language\'); x.window("tabs")'),
    { status: 0, output: '', count: '3 0' });
});

test('rejects unknown keys in every supported scope', () => {
  const result = classify('p.global("home.servers"); p.global("future"); p.window(`future`); p.workspace(\'future\'); p.server("future"); p.global("future")');
  assert.equal(result.status, 1);
  assert.equal(result.count, '5 4');
  assert.deepEqual(result.output.trim().split('\n'), ['global', 'window', 'workspace', 'server']
    .map(scope => `UNCLASSIFIED PERSIST KEY: ${scope}:future`));
});

test('rejects missing home.servers even when the other declarations are classified', () => {
  assert.deepEqual(classify('p.global("language")'),
    { status: 1, output: 'MISSING: Persist.global(home.servers)\n', count: '1 1' });
});

test('classifies 2.0.25 origin-owned notices and scoped layout state', () => {
  const keys = ['home.servers', 'chatgpt-plan-welcome.v1', 'chatgpt-usage-limit', 'extension.servers', 'layout.scoped'];
  assert.deepEqual(classify(keys.map(key => `p.global(${JSON.stringify(key)})`).join(';')),
    { status: 0, output: '', count: '5 0' });
});

test('handles large embedded data without quadratic identifier retries', () => {
  assert.deepEqual(classify(`const asset="${'a'.repeat(2 * 1024 * 1024)}";p.global("home.servers")`),
    { status: 0, output: '', count: '1 0' });
});
