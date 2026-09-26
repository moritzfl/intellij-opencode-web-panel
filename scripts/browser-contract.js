async (page, { origin, serverKey, workspace, authorization, snippets }) => {
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
  const session = v2 ? json.data : json;
  await page.addInitScript(snippets.seed);
  await page.setViewportSize({ width: 1500, height: 1000 });
  await page.goto(`${origin}/server/${serverKey}/session/${session.id}`);
  await page.locator('main [contenteditable=true]').waitFor();
  await page.evaluate(() => { window.__fileCalls = []; window.__diffCalls = []; window.__chunkCalls = []; });
  await page.evaluate(snippets.diffs);
  await page.evaluate(snippets.files);

  if (v2) {
    // Exercise real file-tree rows and Markdown rendered from a real workspace file.
    await page.getByRole('button', { name: 'Toggle review', exact: true }).click();
    await page.locator('[data-slot="session-review-v2-file-name"]').waitFor();
    await page.locator('[data-slot="session-review-v2-file-name"]').click({ modifiers: ['Alt'] });
    assert(await page.evaluate(() => window.__diffCalls.at(-1)?.vcsMode === 'working'), 'Preview header did not open working-tree diff');
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
}
