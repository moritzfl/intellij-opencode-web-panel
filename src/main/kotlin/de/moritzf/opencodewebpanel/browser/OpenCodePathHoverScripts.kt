package de.moritzf.opencodewebpanel.browser

import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserScriptSupport.DECODE_ROUTE_DIRECTORY_JS
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserScriptSupport.OPENCODE_TAB_POPOVER_OPEN_DELAY_MILLIS
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserScriptSupport.PATH_HOVER_PREVIEW_DELAY_MILLIS
import org.intellij.lang.annotations.Language

internal object OpenCodePathHoverScripts {
    fun buildPathHoverPreviewScript(enabled: Boolean): String? {
        if (!enabled) return null
        val tabDelay = OPENCODE_TAB_POPOVER_OPEN_DELAY_MILLIS
        val previewDelay = PATH_HOVER_PREVIEW_DELAY_MILLIS
        @Language("JavaScript")
        val script =
            """
            (() => {
              if (window.__opencodeIntellijPathHoverPreviewInstalled) return;
              if (typeof window.fetch !== 'function' || typeof window.setTimeout !== 'function') return;
              window.__opencodeIntellijPathHoverPreviewInstalled = true;
              const nativeFetch = window.fetch;
              const previousFetch = nativeFetch.bind(window);
              const sessions = new Map();
              const projects = new Map();
              let refreshSessionHover = () => {};
              const requestUrl = (input) => {
                if (typeof input === 'string') return input;
                if (input && typeof input.url === 'string') return input.url;
                if (input && typeof input.href === 'string') return input.href;
                return '';
              };
              const pathnameOf = (input) => {
                try { return new URL(requestUrl(input), location.href).pathname; }
                catch (_) { return ''; }
              };
              const samePath = (left, right) => {
                if (typeof left !== 'string' || typeof right !== 'string' || !left || !right) return false;
                const norm = (value) => {
                  let next = value.replace(/\\/g, '/').replace(/\/+$/g, '');
                  if (/^[A-Za-z]:\//.test(next) || next.startsWith('//')) next = next.toLowerCase();
                  return next;
                };
                return norm(left) === norm(right);
              };
              const sessionDirectoryOf = (raw) => {
                if (!raw || typeof raw !== 'object') return '';
                const location = raw.location;
                if (location && typeof location.directory === 'string' && location.directory) return location.directory;
                return typeof raw.directory === 'string' ? raw.directory : '';
              };
              const rememberSession = (raw) => {
                if (!raw || typeof raw !== 'object') return;
                const id = raw.id;
                if (typeof id !== 'string' || id.indexOf('ses_') !== 0) return;
                const directory = sessionDirectoryOf(raw);
                if (!directory) return;
                const time = raw.time && typeof raw.time === 'object' ? raw.time : {};
                sessions.set(id, {
                  id: id,
                  title: typeof raw.title === 'string' ? raw.title : '',
                  directory: directory,
                  updated: typeof time.updated === 'number' ? time.updated : (typeof time.created === 'number' ? time.created : 0),
                  parentID: typeof raw.parentID === 'string' ? raw.parentID : '',
                  archived: typeof time.archived === 'number',
                  projectID: typeof raw.projectID === 'string' ? raw.projectID : '',
                });
              };
              const rememberProject = (raw) => {
                if (!raw || typeof raw !== 'object') return;
                const worktree = typeof raw.worktree === 'string' && raw.worktree
                  ? raw.worktree
                  : (typeof raw.canonical === 'string' ? raw.canonical : '');
                if (!worktree) return;
                const id = typeof raw.id === 'string' && raw.id ? raw.id : worktree;
                const sandboxes = [];
                const pushDir = (value) => {
                  if (typeof value !== 'string' || !value || samePath(value, worktree)) return;
                  if (sandboxes.some((item) => samePath(item, value))) return;
                  sandboxes.push(value);
                };
                if (Array.isArray(raw.sandboxes)) raw.sandboxes.forEach(pushDir);
                if (Array.isArray(raw.worktrees)) {
                  raw.worktrees.forEach((item) => {
                    if (!item || typeof item !== 'object' || item.strategy === undefined) return;
                    pushDir(item.directory);
                  });
                }
                const previous = projects.get(id);
                projects.set(id, {
                  id: id,
                  worktree: worktree,
                  sandboxes: sandboxes.length ? sandboxes : ((previous && previous.sandboxes) || []),
                });
              };
              const eachRecord = (json, visit) => {
                if (Array.isArray(json)) {
                  json.forEach(visit);
                  return;
                }
                if (!json || typeof json !== 'object') return;
                if (Array.isArray(json.data)) {
                  json.data.forEach(visit);
                  return;
                }
                visit(json.data && typeof json.data === 'object' ? json.data : json);
              };
              const isSessionList = (path) => path === '/api/session' || path === '/session';
              const isSessionItem = (path) => {
                const marker = '/ses_';
                const index = path.lastIndexOf(marker);
                if (index < 0) return false;
                const tail = path.slice(index + marker.length);
                if (!tail || tail.indexOf('/') !== -1) return false;
                return path === '/api/session/ses_' + tail || path === '/session/ses_' + tail;
              };
              const isProjectList = (path) => (
                path === '/api/project' || path === '/project' ||
                path === '/api/project/current' || path === '/project/current'
              );
              const ingestSessionPayload = (path, json) => {
                if (isSessionList(path) || isSessionItem(path)) {
                  eachRecord(json, rememberSession);
                  return;
                }
                if (isProjectList(path)) eachRecord(json, rememberProject);
              };
              const observeSessionFetch = (input, pending) => {
                const path = pathnameOf(input);
                if (!isSessionList(path) && !isSessionItem(path) && !isProjectList(path)) return;
                Promise.resolve(pending).then((response) => {
                  if (!response || !response.ok || typeof response.clone !== 'function') return;
                  const type = response.headers && response.headers.get && response.headers.get('content-type');
                  if (type && type.indexOf('text/event-stream') !== -1) return;
                  response.clone().json().then((json) => {
                    ingestSessionPayload(path, json);
                    refreshSessionHover();
                  }).catch(() => {});
                }).catch(() => {});
              };
              window.fetch = function(input, init) {
                const pending = previousFetch.apply(window, arguments);
                try { observeSessionFetch(input, pending); } catch (_) {}
                return pending;
              };
              if (typeof globalThis === 'object' && globalThis.fetch === nativeFetch) {
                globalThis.fetch = window.fetch;
              }
              const TAB_DELAY = $tabDelay;
              const PREVIEW_DELAY = $previewDelay;
              const TAB_TRIGGER = '[data-component="session-tab-popover-trigger"]';
              const PROJECT_ROW = '[data-component="home-project-row"]';
              const SESSION_ROW = '[data-component="home-session-row"]';
              const SEARCH_ROW = '[data-component="home-session-search-row"]';
              // CLI 2.x compact titlebar: the "Tabs" drawer trigger shows the current session's
              // title but, unlike the drawer rows (full tab items with Kobalte popover), has no
              // hover preview of its own.
              const MOBILE_TABS_TRIGGER = '[data-slot="mobile-tabs-trigger"]';
              // CLI 2.x mobile drawer rows are full tab items, but their Kobalte popover is
              // suppressed while the drawer is open (verified live on 2.0.18); the row's tab
              // link href still carries the session id.
              const MOBILE_DRAWER_TAB = '[data-slot="mobile-drawer-content"] [data-slot="titlebar-tab-item"]';
              const HOVER_ROW = PROJECT_ROW + ', ' + SESSION_ROW + ', ' + SEARCH_ROW + ', ' + MOBILE_TABS_TRIGGER + ', ' + MOBILE_DRAWER_TAB;
              const nativeSetTimeout = window.setTimeout.bind(window);
              const nativeClearTimeout = window.clearTimeout.bind(window);
              // Kobalte schedules its hover open-delay synchronously inside the trigger's
              // pointerenter handler; clamping any 2000ms timer while a trigger happens to be
              // hovered would also clamp unrelated page timers (copy-state reset, typewriter
              // cursor). Only timers scheduled within this window of a trigger pointerenter
              // are Kobalte's open-delay.
              const ENTER_CLAMP_WINDOW_MILLIS = 100;
              let tabTriggerEnteredAt = -1;
              document.addEventListener('pointerenter', (event) => {
                const target = event.target;
                if (target && target.closest && target.closest(TAB_TRIGGER)) {
                  tabTriggerEnteredAt = Date.now();
                }
              }, true);
              if (typeof window.setTimeout === 'function') {
                window.setTimeout = function(handler, timeout) {
                  let delay = timeout;
                  if (delay === TAB_DELAY && arguments.length === 2) {
                    try {
                      if (tabTriggerEnteredAt >= 0 &&
                          Date.now() - tabTriggerEnteredAt <= ENTER_CLAMP_WINDOW_MILLIS &&
                          document.querySelector(TAB_TRIGGER + ':hover')) {
                        delay = PREVIEW_DELAY;
                      }
                    } catch (_) {}
                  }
                  const rest = [handler, delay];
                  for (let index = 2; index < arguments.length; index += 1) rest.push(arguments[index]);
                  return nativeSetTimeout.apply(window, rest);
                };
              }
              $DECODE_ROUTE_DIRECTORY_JS
              const prettyPath = (worktree) => {
                if (typeof worktree !== 'string' || !worktree) return '';
                let next = worktree.replace(/\\/g, '/');
                next = next.replace(/^[A-Za-z]:\/Users\/[^/]+/i, '~');
                next = next.replace(/^\/Users\/[^/]+/, '~');
                next = next.replace(/^\/home\/[^/]+/, '~');
                return next;
              };
              const projectNameFromRow = (row) => {
                const title = row.querySelector('[data-component="home-session-title"]');
                if (title && title.textContent) return title.textContent.trim();
                const label = row.querySelector('span');
                const text = ((label && label.textContent) || row.textContent || '').trim();
                return text;
              };
              const storedWorktrees = () => {
                try {
                  const parsed = JSON.parse(window.localStorage.getItem('opencode.global.dat:server') || '{}');
                  const projects = parsed && parsed.projects;
                  if (!projects || typeof projects !== 'object') return [];
                  const all = [];
                  Object.keys(projects).forEach((key) => {
                    const items = projects[key];
                    if (!Array.isArray(items)) return;
                    items.forEach((item) => {
                      const tree = item && item.worktree;
                      if (typeof tree === 'string' && tree) all.push(tree);
                    });
                  });
                  return all;
                } catch (_) {
                  return [];
                }
              };
              const worktreeBasename = (worktree) => {
                const norm = String(worktree || '').replace(/\\/g, '/').replace(/\/+$/g, '');
                const parts = norm.split('/').filter(Boolean);
                return parts.length ? parts[parts.length - 1] : '';
              };
              const worktreesMatchingRowCount = (rowCount) => {
                try {
                  const parsed = JSON.parse(window.localStorage.getItem('opencode.global.dat:server') || '{}');
                  const projects = parsed && parsed.projects;
                  if (!projects || typeof projects !== 'object') return [];
                  const arrays = [];
                  if (Array.isArray(projects.local)) arrays.push(projects.local);
                  Object.keys(projects).forEach((key) => {
                    if (key === 'local') return;
                    if (Array.isArray(projects[key])) arrays.push(projects[key]);
                  });
                  const treesOf = (items) => items.map((item) => item && item.worktree).filter((value) => typeof value === 'string' && value);
                  for (let index = 0; index < arrays.length; index += 1) {
                    const trees = treesOf(arrays[index]);
                    if (trees.length === rowCount) return trees;
                  }
                  const all = [];
                  arrays.forEach((items) => { treesOf(items).forEach((value) => all.push(value)); });
                  if (all.length === rowCount) return all;
                } catch (_) {}
                return [];
              };
              const pathForProjectRow = (row) => {
                const encoded = row.getAttribute('data-project');
                if (encoded) {
                  const decoded = decodeRouteDirectory(encoded);
                  if (decoded) return decoded;
                }
                const nameEl = row.querySelector('[data-component="home-session-project-name"]');
                const projectName = ((nameEl && nameEl.textContent) || '').trim();
                if (projectName) {
                  const matches = storedWorktrees().filter((tree) => worktreeBasename(tree) === projectName);
                  if (matches.length === 1) return matches[0];
                }
                if (!row.matches(PROJECT_ROW)) return '';
                const rows = document.querySelectorAll(PROJECT_ROW);
                const trees = worktreesMatchingRowCount(rows.length);
                if (!trees.length) return '';
                const index = Array.prototype.indexOf.call(rows, row);
                if (index < 0 || index >= trees.length) return '';
                return trees[index];
              };
              const sessionIdFromRow = (row) => {
                const marked = (row.closest && row.closest('[data-session-id]')) || row;
                const id = marked.getAttribute && marked.getAttribute('data-session-id');
                if (typeof id === 'string' && id.indexOf('ses_') === 0) return id;
                const key = row.getAttribute && row.getAttribute('data-key');
                if (typeof key !== 'string') return '';
                const marker = ':ses_';
                const index = key.lastIndexOf(marker);
                return index < 0 ? '' : key.slice(index + 1);
              };
              const directoryFromSearchKey = (row) => {
                const key = row.getAttribute && row.getAttribute('data-key');
                if (typeof key !== 'string') return '';
                const marker = ':ses_';
                const index = key.lastIndexOf(marker);
                return index <= 0 ? '' : key.slice(0, index);
              };
              const displaySessionTitle = (session) => {
                const title = session.title || '';
                const prefixes = ['New session - ', 'Child session - '];
                for (let index = 0; index < prefixes.length; index += 1) {
                  if (title.indexOf(prefixes[index]) !== 0) continue;
                  const rest = title.slice(prefixes[index].length);
                  if (rest.length === 24 && rest.charAt(10) === 'T' && rest.charAt(23) === 'Z') {
                    return prefixes[index].slice(0, prefixes[index].length - 3);
                  }
                }
                return title || session.id;
              };
              const sessionTitleFromRow = (row) => {
                const titled = row.querySelector('[data-component="home-session-title"]');
                if (titled && titled.textContent) return titled.textContent.replace(/\s+/g, ' ').trim();
                const spans = row.querySelectorAll(':scope > span, :scope [data-slot="home-session-labels"] > span');
                for (let index = 0; index < spans.length; index += 1) {
                  if (spans[index].getAttribute('data-component') === 'home-session-project-name') continue;
                  const text = (spans[index].textContent || '').replace(/\s+/g, ' ').trim();
                  if (text) return text;
                }
                return '';
              };
              const selectedProjectWorktree = () => {
                const selected = document.querySelector(
                  PROJECT_ROW + '[aria-current="page"], ' + PROJECT_ROW + '[data-selected]',
                );
                if (selected) {
                  const path = pathForProjectRow(selected);
                  if (path) return path;
                }
                if (document.querySelector(PROJECT_ROW)) return '';
                try {
                  const marker = window.localStorage.getItem('opencode-intellij-project');
                  if (typeof marker === 'string' && marker) return marker;
                } catch (_) {}
                return '';
              };
              const scopeDirectories = () => {
                const selected = selectedProjectWorktree();
                const roots = selected ? [selected] : storedWorktrees();
                if (!roots.length) return null;
                const dirs = roots.slice();
                const add = (value) => {
                  if (typeof value !== 'string' || !value) return;
                  if (dirs.some((dir) => samePath(dir, value))) return;
                  dirs.push(value);
                };
                projects.forEach((project) => {
                  if (!roots.some((root) => samePath(root, project.worktree))) return;
                  (project.sandboxes || []).forEach(add);
                });
                return dirs;
              };
              const orderedScopeSessions = () => {
                const dirs = scopeDirectories();
                const list = [];
                sessions.forEach((session) => {
                  if (session.parentID || session.archived) return;
                  if (dirs && !dirs.some((dir) => samePath(dir, session.directory))) return;
                  list.push(session);
                });
                list.sort((left, right) => {
                  const delta = (right.updated || 0) - (left.updated || 0);
                  if (delta !== 0) return delta;
                  if (left.id < right.id) return -1;
                  if (left.id > right.id) return 1;
                  return 0;
                });
                return list;
              };
              const pathForSessionRow = (row) => {
                const id = sessionIdFromRow(row);
                if (id && sessions.has(id)) return sessions.get(id).directory;
                const fromKey = directoryFromSearchKey(row);
                if (fromKey) return fromKey;
                if (!row.matches(SESSION_ROW)) {
                  const title = sessionTitleFromRow(row);
                  if (!title) return '';
                  const matches = orderedScopeSessions().filter((session) => displaySessionTitle(session) === title);
                  return matches.length === 1 ? matches[0].directory : '';
                }
                const visible = Array.prototype.slice.call(document.querySelectorAll(SESSION_ROW));
                const index = visible.indexOf(row);
                if (index < 0) return '';
                const titles = visible.map(sessionTitleFromRow);
                const candidates = orderedScopeSessions();
                if (
                  candidates.length === titles.length &&
                  titles.every((title, at) => displaySessionTitle(candidates[at]) === title)
                ) {
                  return candidates[index].directory;
                }
                const title = titles[index];
                if (!title) return '';
                const matches = candidates.filter((session) => displaySessionTitle(session) === title);
                if (matches.length === 1 && titles.filter((item) => item === title).length === 1) {
                  return matches[0].directory;
                }
                return '';
              };
              const owningProjectRoot = (directory, projectID) => {
                let exact = '';
                let sandboxOwner = '';
                let byID = '';
                projects.forEach((project) => {
                  if (!exact && samePath(project.worktree, directory)) exact = project.worktree;
                  if (!sandboxOwner && (project.sandboxes || []).some((sandbox) => samePath(sandbox, directory))) {
                    sandboxOwner = project.worktree;
                  }
                  if (projectID && !byID && project.id === projectID) byID = project.worktree;
                });
                if (exact) return exact;
                if (sandboxOwner) return sandboxOwner;
                if (byID) return byID;
                const selected = selectedProjectWorktree();
                return selected && samePath(selected, directory) ? selected : '';
              };
              const currentRouteSession = () => {
                const match = location.pathname.match(/\/session\/(ses_[^/]+)\/?$/);
                if (!match) return null;
                return sessions.get(match[1]) || null;
              };
              const sessionIdFromHref = (href) => {
                const match = typeof href === 'string' ? href.match(/\/session\/(ses_[^/?#]+)/) : null;
                return match ? match[1] : '';
              };
              const mobileDrawerTabPreview = (row) => {
                const link = row.querySelector('[data-slot="tab-link"][href]');
                const id = sessionIdFromHref(link ? link.getAttribute('href') : '');
                const session = id ? sessions.get(id) : null;
                const directory = session ? session.directory : '';
                const path = prettyPath(directory);
                if (!path) return { title: '', path: '', context: '' };
                const titleEl = row.querySelector('[data-slot="tab-title"]');
                const sessionTitle = (((titleEl && titleEl.textContent) || '').replace(/\s+/g, ' ').trim())
                  || (session ? displaySessionTitle(session) : '');
                const root = owningProjectRoot(directory, session && session.projectID);
                const label = worktreeBasename(root || directory);
                return { title: sessionTitle || label, path: path, context: sessionTitle ? label : '' };
              };
              const mobileTriggerPreview = (trigger) => {
                const session = currentRouteSession();
                const directory = session ? session.directory : '';
                const path = prettyPath(directory);
                if (!path) return { title: '', path: '', context: '' };
                const titleEl = trigger.querySelector('[data-slot="mobile-tab-title"]');
                const sessionTitle = (((titleEl && titleEl.textContent) || '').replace(/\s+/g, ' ').trim())
                  || (session ? displaySessionTitle(session) : '');
                const root = owningProjectRoot(directory, session && session.projectID);
                const label = worktreeBasename(root || directory);
                return { title: sessionTitle || label, path: path, context: sessionTitle ? label : '' };
              };
              const sessionPreview = (row) => {
                const directory = pathForSessionRow(row);
                const path = prettyPath(directory);
                if (!path) return { title: '', path: '', context: '' };
                const cached = sessions.get(sessionIdFromRow(row));
                // Search rows keep the title inside a wrapper, not in a row-level span; the
                // cached session title (timestamp defaults stripped) is the fallback.
                const sessionTitle = sessionTitleFromRow(row) || (cached ? displaySessionTitle(cached) : '');
                const root = owningProjectRoot(directory, cached && cached.projectID);
                const label = worktreeBasename(root || directory);
                return { title: sessionTitle || label, path: path, context: sessionTitle ? label : '' };
              };
              let overlay = null;
              const hidePopover = () => {
                if (!overlay) return;
                if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
                overlay = null;
              };
              const showPopover = (anchor, title, path, context) => {
                hidePopover();
                if (!path) return;
                const pop = document.createElement('div');
                pop.setAttribute('data-component', 'session-tab-popover');
                pop.setAttribute('data-opencode-intellij-path-preview', '');
                const themeRoot = anchor.closest('[data-theme]') || document.documentElement;
                const theme = themeRoot && themeRoot.getAttribute && themeRoot.getAttribute('data-theme');
                if (theme) pop.setAttribute('data-theme', theme);
                // Drawer rows live in the corvu drawer's own stacking context (transform on
                // mobile-drawer-content): a body-level card with z-index 50 renders *behind*
                // the open sheet. Keep the card at body level but never let it cross the
                // drawer's top edge — place it fully above the sheet instead.
                const drawer = anchor.closest && anchor.closest('[data-slot="mobile-drawer-content"]');
                pop.style.position = 'fixed';
                pop.style.zIndex = '50';
                pop.style.pointerEvents = 'none';
                const header = document.createElement('div');
                header.setAttribute('data-slot', 'header');
                if (context) {
                  const contextEl = document.createElement('span');
                  contextEl.setAttribute('data-slot', 'project');
                  contextEl.textContent = context;
                  header.appendChild(contextEl);
                }
                if (title) {
                  const titleEl = document.createElement('span');
                  titleEl.setAttribute('data-slot', 'title');
                  titleEl.textContent = title;
                  header.appendChild(titleEl);
                }
                pop.appendChild(header);
                const row = document.createElement('div');
                row.setAttribute('data-slot', 'row');
                const detail = document.createElement('span');
                detail.setAttribute('data-slot', 'detail');
                detail.textContent = path;
                row.appendChild(detail);
                pop.appendChild(row);
                document.body.appendChild(pop);
                overlay = pop;
                const rect = anchor.getBoundingClientRect();
                const size = pop.getBoundingClientRect();
                let left = rect.left;
                let top = rect.bottom + 6;
                if (top + size.height > window.innerHeight - 8) top = Math.max(8, rect.top - size.height - 6);
                if (left + size.width > window.innerWidth - 8) left = Math.max(8, window.innerWidth - size.width - 8);
                if (left < 8) left = 8;
                if (drawer) {
                  const drawerTop = drawer.getBoundingClientRect().top;
                  if (top + size.height > drawerTop - 6) top = Math.max(8, drawerTop - size.height - 6);
                }
                pop.style.left = left + 'px';
                pop.style.top = top + 'px';
              };
              const projectRowFrom = (node) => {
                if (!node || !node.closest) return null;
                return node.closest(HOVER_ROW);
              };
              let hoverTimer = 0;
              let hoverRow = null;
              const cancelHover = (row) => {
                if (row && hoverRow !== row) return;
                hoverRow = null;
                if (hoverTimer) {
                  nativeClearTimeout(hoverTimer);
                  hoverTimer = 0;
                }
                hidePopover();
              };
              const showRowPreview = (row) => {
                if (row.matches(SESSION_ROW) || row.matches(SEARCH_ROW)) {
                  const preview = sessionPreview(row);
                  showPopover(row, preview.title, preview.path, preview.context);
                  return;
                }
                if (row.matches(MOBILE_TABS_TRIGGER)) {
                  const preview = mobileTriggerPreview(row);
                  showPopover(row, preview.title, preview.path, preview.context);
                  return;
                }
                if (row.matches('[data-slot="titlebar-tab-item"]') && row.closest('[data-slot="mobile-drawer-content"]')) {
                  // The drawer's Kobalte popover is suppressed today; if a future OpenCode
                  // enables it again, the trigger reports data-open/data-expanded and our
                  // synthetic card must yield or both would stack at the same delay.
                  const nativeTrigger = row.closest(TAB_TRIGGER);
                  if (nativeTrigger && (nativeTrigger.hasAttribute('data-open') || nativeTrigger.hasAttribute('data-expanded'))) return;
                  const preview = mobileDrawerTabPreview(row);
                  showPopover(row, preview.title, preview.path, preview.context);
                  return;
                }
                showPopover(row, projectNameFromRow(row), prettyPath(pathForProjectRow(row)));
              };
              refreshSessionHover = () => {
                if (!hoverRow || hoverTimer) return;
                if (!hoverRow.matches(SESSION_ROW) && !hoverRow.matches(SEARCH_ROW) && !hoverRow.matches(MOBILE_TABS_TRIGGER) && !hoverRow.matches('[data-slot="titlebar-tab-item"]')) return;
                showRowPreview(hoverRow);
              };
              const scheduleHover = (row) => {
                if (hoverRow === row) return;
                cancelHover();
                hoverRow = row;
                hoverTimer = nativeSetTimeout(() => {
                  hoverTimer = 0;
                  if (hoverRow !== row) return;
                  showRowPreview(row);
                }, PREVIEW_DELAY);
              };
              document.addEventListener('pointerover', (event) => {
                const row = projectRowFrom(event.target);
                if (row) scheduleHover(row);
              }, true);
              document.addEventListener('pointerout', (event) => {
                const row = projectRowFrom(event.target);
                if (!row) return;
                if (projectRowFrom(event.relatedTarget) === row) return;
                cancelHover(row);
              }, true);
              document.addEventListener('pointerdown', () => cancelHover(), true);
              document.addEventListener('scroll', () => cancelHover(), true);
              document.addEventListener('keydown', (event) => {
                if (event.key === 'Escape') cancelHover();
              }, true);
            })();
        """
        return script.trimIndent()
    }
}
