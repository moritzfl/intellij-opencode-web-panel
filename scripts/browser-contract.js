async (page, { origin, serverKey, workspace, authorization, snippets, stallOrigin }) => {
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  await page.setExtraHTTPHeaders({ Authorization: authorization });
  const identity = await page.request.get(`${origin}/api/info`, { headers: { Authorization: authorization } });
  const v2 = (identity.headers()['content-type'] || '').includes('application/json') && identity.ok();
  const response = await page.request.post(v2 ? `${origin}/api/session` : `${origin}/session?directory=${encodeURIComponent(workspace)}`, {
    headers: { Authorization: authorization },
    data: v2 ? { title: 'Browser contract', location: { directory: workspace } } : { title: 'Browser contract' },
  });
  assert(response.ok(), `Session creation failed: ${response.status()}`);
  const json = await response.json();
  let session = v2 ? json.data : json;
  if (v2) {
    // Import a projected transcript: real tool DOM, without executing a tool/provider turn.
    const now = Date.now();
    const imported = await page.request.post(`${origin}/api/experimental/session/import`, {
      headers: { Authorization: authorization },
      data: {
        info: { ...session, id: session.id + 'fixture' },
        messages: [
          { id: 'msg_contract_user', type: 'user', text: 'Fixture edit', time: { created: now } },
          {
            id: 'msg_contract_assistant', type: 'assistant', agent: 'build',
            model: { providerID: 'test', id: 'test' }, finish: 'stop', time: { created: now + 1, completed: now + 2 },
            content: [{
              type: 'tool', id: 'call-contract-edit', name: 'edit', time: { created: now + 1, completed: now + 2 },
              state: {
                status: 'completed', input: { path: 'src/Main.kt' }, content: [{ type: 'text', text: 'Updated.' }],
                metadata: { files: [{ file: 'src/Main.kt', additions: 1, deletions: 1, status: 'modified',
                  patch: '--- a/src/Main.kt\n+++ b/src/Main.kt\n@@ -1 +1 @@\n-fun main() = Unit\n+fun main() = println("fixture")\n' }] },
              },
            }, { type: 'text', text: 'Fixture complete.' }],
          },
          { id: 'msg_contract_idle', type: 'idle', outcome: 'succeeded', time: { created: now + 3 } },
        ],
      },
    });
    assert(imported.ok(), `Transcript import failed: ${await imported.text()}`);
    session = (await imported.json()).data;
  }
  await page.addInitScript(snippets.seed);
  await page.setViewportSize({ width: 1500, height: 1000 });
  await page.goto(`${origin}/server/${serverKey}/session/${session.id}`);
  await page.locator('main [contenteditable=true]').waitFor();
  const composer = page.locator('main [contenteditable=true]').first();
  await composer.evaluate(input => {
    input.focus(); document.execCommand('insertText', false, 'before OLD after');
    const range = document.createRange(); range.setStart(input.firstChild, 7); range.setEnd(input.firstChild, 10);
    getSelection().removeAllRanges(); getSelection().addRange(range);
  });
  await page.evaluate(snippets.capturePaste);
  await page.evaluate(snippets.paste);
  assert(await page.evaluate(() => window.__pasteResult) === 'accepted', 'Clipboard paste was not acknowledged');
  assert((await composer.textContent()).replace(/\u200b/g, '') === 'before NEW after', 'Clipboard paste lost its selection');
  await page.evaluate(snippets.capturePaste);
  await composer.evaluate(input => input.blur());
  await page.evaluate(snippets.nativePaste);
  assert(await page.evaluate(() => window.__pasteResult) === 'stale', 'Unavailable clipboard replayed into a changed destination');
  await composer.focus();
  await page.evaluate(snippets.capturePaste);
  await page.evaluate(snippets.nativePaste);
  assert(await page.evaluate(() => window.__pasteResult) === 'native', 'Unavailable clipboard did not request native fallback');
  if (v2) {
    const home = await page.context().newPage();
    await home.setExtraHTTPHeaders({ Authorization: authorization });
    await home.goto(origin);
    const search = home.locator('input[aria-controls="home-session-search-results"]');
    await search.fill('before OLD after');
    await search.evaluate(input => input.setSelectionRange(7, 10));
    await home.evaluate(snippets.capturePaste);
    await home.evaluate(snippets.paste);
    assert(await search.inputValue() === 'before NEW after', 'Plain input swallowed synthetic paste');
    await home.evaluate(() => document.execCommand('undo'));
    assert(await search.inputValue() === 'before OLD after', 'Plain input paste lost native undo');
    await home.close();
  }
  await page.evaluate(() => { window.__fileCalls = []; window.__diffCalls = []; window.__chunkCalls = []; });
  await page.evaluate(snippets.diffs);
  await page.evaluate(snippets.files);
  await page.evaluate(snippets.chunks);

  if (v2) {
    const group = page.locator('[data-component="context-tool-group-trigger"]').first();
    if (await group.count()) await group.click();
    const icon = page.locator('[data-slot="opencode-intellij-open-file"]').first();
    await icon.click();
    assert(await page.evaluate(() => window.__fileCalls.at(-1)?.href === 'src/Main.kt' &&
      window.__fileCalls.at(-1)?.partID === 'call-contract-edit'), 'Dynamically rendered tool icon lost its file/part');
    await page.evaluate(() => { window.__fileCalls = []; });
    // Exercise real file-tree rows and Markdown rendered from a real workspace file.
    await page.getByRole('button', { name: 'Toggle review', exact: true }).click();
    await page.locator('[data-slot="session-review-v2-file-name"]').waitFor();
    await page.locator('[data-slot="session-review-v2-file-name"]').click({ modifiers: ['Alt'] });
    assert(await page.evaluate(() => window.__diffCalls.at(-1)?.vcsMode === 'working'), 'Preview header did not open working-tree diff');
    const mode = page.locator('[data-slot="session-review-v2-sidebar-header"] [data-component="select-v2"]');
    await mode.click();
    await page.locator('[data-slot="select-v2-listbox"] [data-key="branch"]').click();
    await page.waitForFunction(() => document.querySelector('[data-opencode-intellij-review-mode="branch"]'))
      .catch(() => { throw new Error('Review mode did not follow mouse selection'); });
    await page.locator('[data-slot="session-review-v2-file-name"]').click({ modifiers: ['Alt'] });
    assert(await page.evaluate(() => window.__diffCalls.at(-1)?.vcsMode === 'branch'), 'Selected branch mode was lost');
    await mode.press('Enter');
    // Kobalte transfers focus after opening; sending Home before that loses the key.
    await page.waitForFunction(() => document.querySelector('[data-slot="select-v2-listbox"]')?.contains(document.activeElement));
    await page.keyboard.press('Home');
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => document.querySelector('[data-opencode-intellij-review-mode="git"]'))
      .catch(() => { throw new Error('Review mode did not follow keyboard selection'); });
    // Another popup with the same option keys must not change this review panel's mode.
    await page.evaluate(async () => {
      const popup = document.createElement('div');
      popup.setAttribute('data-slot', 'select-v2-listbox');
      popup.innerHTML = '<div data-key="branch" data-selected></div>';
      document.body.append(popup);
      await new Promise(resolve => setTimeout(resolve, 0));
      popup.remove();
    });
    assert(await mode.getAttribute('data-opencode-intellij-review-mode') === 'git', 'Unrelated dropdown overwrote review mode');
    await page.getByRole('button', { name: 'Open file', exact: true }).click();
    const folder = page.locator('[data-slot="file-tree-v2-row"][data-path="src"]');
    await folder.click();
    await page.locator('[data-slot="file-tree-v2-row"][data-path="src/Main.kt"]').waitFor();
    assert(await page.evaluate(() => window.__fileCalls.length === 0), 'Plain directory click was intercepted');
    await page.locator('[data-slot="file-tree-v2-row"][data-path="README.md"]').click();
    await page.locator('[data-component="markdown"] a[data-local-link="src/Main.kt"]').waitFor();
    assert(await page.evaluate(() => window.__fileCalls.length === 0), 'Plain file preview was intercepted');
    await page.locator('[data-component="markdown"] a[data-local-link="src/Main.kt"]').click();
    assert(await page.evaluate(() => window.__fileCalls.at(-1)?.href === 'src/Main.kt'), 'Semantic Markdown file link was ignored');
    const absolute = page.locator('[data-component="markdown"] a[data-local-link="/src/Main.kt"]');
    await absolute.focus();
    await absolute.press('Enter');
    assert(await page.evaluate(() => window.__fileCalls.at(-1)?.href === '/src/Main.kt'), 'Keyboard/root-relative Markdown link was ignored');
    await page.locator('[data-slot="file-tree-v2-row"][data-path="src/Main.kt"]').click({ modifiers: ['Alt'] });
    assert(await page.evaluate(() => window.__fileCalls.at(-1)?.href === 'src/Main.kt'), 'Modified file-tree click was ignored');
  }

  // Compatibility canaries on the real page: older Markdown hrefs and future SPA routes.
  // These supplement the native V2 DOM checks, rather than substituting a synthetic page.
  const links = await page.evaluate(() => {
    const markdown = document.createElement('div');
    markdown.setAttribute('data-component', 'markdown');
    document.body.append(markdown);
    const outcomes = {};
    for (const href of ['/src/Main.kt', '/src/Main.kt:42', 'src/Main.kt', '/future-page', '/server/key/session/ses_test', 'custom:thing']) {
      const anchor = document.createElement('a');
      anchor.href = href;
      anchor.target = '_blank';
      markdown.append(anchor);
      const event = new MouseEvent('click', { bubbles: true, cancelable: true });
      // Prevent browser navigation only after the plugin has had its capture-phase opportunity.
      anchor.addEventListener('click', event => { event.preventDefault(); event.stopPropagation(); });
      const before = window.__fileCalls.length;
      anchor.dispatchEvent(event);
      outcomes[href] = window.__fileCalls.length > before;
    }
    markdown.remove();
    return outcomes;
  });
  for (const href of ['/src/Main.kt', '/src/Main.kt:42', 'src/Main.kt']) assert(links[href], `Legacy file href rejected: ${href}`);
  for (const href of ['/future-page', '/server/key/session/ses_test', 'custom:thing']) assert(!links[href], `Non-file link intercepted: ${href}`);

  // Pasted engine text and a removed error field must not reload a healthy session.
  const falseAlarm = await page.evaluate(async () => {
    const editable = document.createElement('textarea');
    editable.value = 'Failed to fetch dynamically imported module';
    const removed = document.createElement('textarea');
    removed.readOnly = true;
    document.body.append(editable, removed);
    // Let the observer queue its retries, then detach before the timers fire.
    await new Promise(resolve => queueMicrotask(resolve));
    removed.remove();
    removed.value = editable.value;
    await new Promise(resolve => setTimeout(resolve, 1100));
    editable.remove();
    return window.__chunkCalls.length;
  });
  assert(falseAlarm === 0, 'Chunk recovery fired for an editable or detached field');

  // Streaming unrelated text must not rescan every tool header, dropdown, and input in the page.
  const scans = await page.evaluate(async () => {
    const original = document.querySelectorAll;
    const scans = [];
    document.querySelectorAll = function(selector) {
      if (/edit-trigger|select-v2-listbox|sidebar-header|input-input/.test(selector)) scans.push(selector);
      return original.call(this, selector);
    };
    const text = document.createTextNode('');
    const unrelated = document.createElement('div');
    unrelated.append(text);
    document.body.append(unrelated);
    try {
      for (let i = 0; i < 20; i++) {
        text.data += ' streaming';
        unrelated.append(document.createElement('span'));
        await new Promise(resolve => setTimeout(resolve, 20));
      }
      return scans;
    } finally {
      document.querySelectorAll = original;
      unrelated.remove();
    }
  });
  assert(scans.length === 0, `Unrelated mutations triggered document scans: ${scans.join(', ')}`);

  // Exercise Solid's actual error boundary, hiding DOM error events to require field detection.
  // A fresh context avoids the app's service worker serving an already cached route chunk.
  const brokenContext = await page.context().browser().newContext({
    serviceWorkers: 'block', extraHTTPHeaders: { Authorization: authorization },
  });
  const broken = await brokenContext.newPage();
  const blockedScripts = [];
  await broken.addInitScript(`
    window.__chunkCalls = [];
    window.addEventListener('error', event => event.stopImmediatePropagation(), true);
    window.addEventListener('unhandledrejection', event => event.stopImmediatePropagation(), true);
    ${snippets.seed}
    ${snippets.chunks}
  `);
  await broken.route(/\/(?:_?assets)\/(?:new-session|route)-[^/]*\.js/, route => {
    blockedScripts.push(route.request().url());
    return route.abort();
  });
  await broken.goto(v2 ? `${origin}/server/${serverKey}/session/${session.id}` : origin);
  if (!v2) await broken.getByRole('button', { name: 'New session', exact: true }).first().click();
  await broken.waitForFunction(() => window.__chunkCalls.length === 1)
    .catch(async () => { throw new Error('Chunk error boundary was not detected; blocked: ' + blockedScripts.join(', ') + '\n' + await broken.locator('body').innerText()); });
  assert(blockedScripts.length > 0, 'Chunk-failure scenario did not block a lazy route');
  assert(await broken.locator('textarea[readonly], input[readonly], [data-slot="input-input"][readonly]').count() > 0,
    'Chunk recovery did not come from the real error details field');
  await brokenContext.close();

  // Exercise the real SPA's reconnect loop; merely finding watchdog text in a bundle is insufficient.
  const stalled = await page.context().newPage();
  await stalled.setExtraHTTPHeaders({ Authorization: authorization });
  await stalled.addInitScript(() => { window.__originalFetch = window.fetch; });
  const watchdog = v2 ? snippets.nativeWatchdog : snippets.watchdog;
  if (watchdog) await stalled.addInitScript(watchdog);
  await stalled.goto(stallOrigin);
  assert(await stalled.evaluate(() => window.fetch === window.__originalFetch) === v2, 'Wrong protocol owns the fetch watchdog');
  await stalled.waitForFunction(async () => {
    const streams = await fetch('/__contract/streams').then(response => response.json());
    return streams.some((stream, index) => stream.closed && stream.firstByte &&
      stream.closed - stream.firstByte >= 40_000 && streams[index + 1]?.firstByte);
  }, null, { timeout: 65_000, polling: 1000 });
  await stalled.close();
}
