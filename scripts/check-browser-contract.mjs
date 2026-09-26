#!/usr/bin/env node
// Provider-free, isolated real-server browser regression gate. Requires Node and playwright-cli.
// Usage: node scripts/check-browser-contract.mjs [/path/to/opencode]
import { execFile, spawn, spawnSync } from 'node:child_process';
import { createServer, request } from 'node:http';
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { promisify } from 'node:util';

const repo = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const base = path.join(tmpdir(), 'opencode');
await mkdir(base, { recursive: true });
const root = await mkdtemp(path.join(base, 'browser-contract-'));
const workspace = path.join(root, 'workspace');
const password = 'contract-test-only';
const authorization = `Basic ${Buffer.from(`opencode:${password}`).toString('base64')}`;
const session = `ocwp-contract-${process.pid}`;
let server;
let browserOpened = false;
let serverLog = '';
let stallProxy;
const execFileAsync = promisify(execFile);
const browser = args => execFileAsync('playwright-cli', ['--session=' + session, ...args], {
  cwd: root, timeout: 180_000, maxBuffer: 2 * 1024 * 1024,
});

async function stallingProxy(origin) {
  const target = new URL(origin);
  const streams = [];
  stallProxy = createServer((incoming, outgoing) => {
    if (incoming.url === '/__contract/streams') {
      outgoing.setHeader('Content-Type', 'application/json');
      outgoing.end(JSON.stringify(streams));
      return;
    }
    const event = ['/global/event', '/api/event'].includes(new URL(incoming.url, origin).pathname);
    const stream = event ? { started: Date.now(), firstByte: null, closed: null } : null;
    if (stream) streams.push(stream);
    const upstream = request({ hostname: target.hostname, port: target.port, path: incoming.url, method: incoming.method,
      headers: { ...incoming.headers, host: target.host } }, response => {
      outgoing.writeHead(response.statusCode, response.headers);
      if (!stream) { response.pipe(outgoing); return; }
      response.on('data', chunk => {
        if (stream.firstByte !== null) return;
        stream.firstByte = Date.now();
        outgoing.write(chunk);
      });
      // Intentionally keep the client socket open but silent after server.connected.
    });
    upstream.on('error', () => outgoing.destroy());
    outgoing.on('close', () => {
      if (stream) stream.closed = Date.now();
      upstream.destroy();
    });
    incoming.pipe(upstream);
  });
  await new Promise(resolve => stallProxy.listen(0, '127.0.0.1', resolve));
  return `http://127.0.0.1:${stallProxy.address().port}`;
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { cwd: repo, encoding: 'utf8', timeout: 180_000, ...options });
  if (result.error || result.status !== 0) {
    throw new Error(`${command} ${args.join(' ')} failed: ${result.error ?? result.status}\n${result.stdout}\n${result.stderr}`);
  }
  return result.stdout;
}

try {
  for (const dir of ['workspace/src', 'home', 'config/opencode', 'data', 'cache', 'state']) {
    await mkdir(path.join(root, dir), { recursive: true });
  }
  await writeFile(path.join(root, 'config/opencode/opencode.json'), '{}');
  await writeFile(path.join(workspace, 'README.md'), '# Browser contract\n\nOriginal.\n');
  await writeFile(path.join(workspace, 'src/Main.kt'), 'fun main() = Unit\n');
  run('git', ['init', '-q', '-b', 'main'], { cwd: workspace });
  run('git', ['add', '.'], { cwd: workspace });
  run('git', ['-c', 'user.name=Contract test', '-c', 'user.email=contract@example.invalid', 'commit', '-qm', 'Fixture'], { cwd: workspace });
  run('git', ['checkout', '-qb', 'fixture-branch'], { cwd: workspace });
  await writeFile(path.join(workspace, 'README.md'), '# Browser contract\n\nCommitted change.\n');
  run('git', ['-c', 'user.name=Contract test', '-c', 'user.email=contract@example.invalid', 'commit', '-qam', 'Branch fixture'], { cwd: workspace });
  await writeFile(path.join(workspace, 'README.md'), '# Browser contract\n\nChanged.\n\n[Relative file](src/Main.kt)\n\n[Absolute file](/src/Main.kt)\n');
  server = spawn(process.argv[2] || 'opencode', ['serve', '--hostname', '127.0.0.1', '--port', '0', '--print-logs'], {
    cwd: workspace, stdio: ['ignore', 'pipe', 'pipe'],
    env: {
      PATH: process.env.PATH, HOME: path.join(root, 'home'), OPENCODE_TEST_HOME: path.join(root, 'home'),
      XDG_CONFIG_HOME: path.join(root, 'config'), XDG_DATA_HOME: path.join(root, 'data'),
      XDG_CACHE_HOME: path.join(root, 'cache'), XDG_STATE_HOME: path.join(root, 'state'),
      OPENCODE_DISABLE_PROJECT_CONFIG: '1', OPENCODE_DISABLE_MODELS_FETCH: '1',
      OPENCODE_DISABLE_AUTOUPDATE: '1', OPENCODE_SERVER_PASSWORD: password,
    },
  });
  server.stdout.on('data', data => { serverLog += data; });
  server.stderr.on('data', data => { serverLog += data; });
  let spawnError;
  server.on('error', error => { spawnError = error; });
  const deadline = Date.now() + 60_000;
  let origin;
  while (!(origin = serverLog.match(/listening on (http:\/\/127\.0\.0\.1:\d+)/)?.[1])) {
    if (spawnError || server.exitCode !== null || Date.now() > deadline) throw new Error(`Server startup failed: ${spawnError ?? serverLog}`);
    await delay(100);
  }
  const env = { ...process.env, OPENCODE_SERVER_PASSWORD: password };
  process.stdout.write(run('bash', ['scripts/check-dom-contract.sh', origin], { env }));
  process.stdout.write(run('bash', ['scripts/check-wire-contract.sh', origin, workspace], { env }));
  run('./gradlew', ['exportBrowserContract', `-PbrowserContractDirectory=${workspace}`, `-PbrowserContractOrigin=${origin}`, '--console=plain']);
  const snippets = JSON.parse(await readFile(path.join(repo, 'build/browser-contract/snippets.json'), 'utf8'));
  const scenario = await readFile(path.join(repo, 'scripts/browser-contract.js'), 'utf8');
  const stallOrigin = await stallingProxy(origin);
  const serverKey = Buffer.from(origin).toString('base64url');
  const code = `async (page) => { await (${scenario})(page, ${JSON.stringify({ origin, serverKey, workspace, authorization, snippets, stallOrigin })}); return 'OCWP_BROWSER_CONTRACT_OK'; }`;
  const filename = path.join(root, 'scenario.js');
  await writeFile(filename, code);
  await browser(['open']);
  browserOpened = true;
  const { stdout: output } = await browser(['--raw', 'run-code', '--filename', filename]);
  // The CLI can return exit 0 for a failed browser command. Only a completed scenario emits this.
  if (!output.includes('OCWP_BROWSER_CONTRACT_OK')) throw new Error(output || 'Browser scenario did not complete');
  console.log('OK: real-page navigation and browser behavior contracts');
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
} finally {
  if (browserOpened) await browser(['close']).catch(() => {});
  if (stallProxy) {
    stallProxy.closeAllConnections();
    await new Promise(resolve => stallProxy.close(resolve));
  }
  if (server?.pid && server.exitCode === null) {
    const exited = new Promise(resolve => server.once('exit', resolve));
    server.kill('SIGTERM');
    const kill = setTimeout(() => server.kill('SIGKILL'), 5_000);
    await exited;
    clearTimeout(kill);
  }
  await rm(root, { recursive: true, force: true });
}
