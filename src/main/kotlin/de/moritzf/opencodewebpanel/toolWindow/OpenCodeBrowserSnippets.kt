package de.moritzf.opencodewebpanel.toolWindow

import org.intellij.lang.annotations.Language

internal object OpenCodeBrowserSnippets {

    fun buildOpenProjectScript(
        projectBasePath: String,
        projectPath: String,
        expectedOrigin: String?,
        openMostRecentConversation: Boolean,
    ): String {
        val directory = escapeJavaScript(projectBasePath)
        val escapedProjectPath = escapeJavaScript(projectPath)
        val originGuard = expectedOrigin
            ?.let(::escapeJavaScript)
            ?.let {
                @Language("JavaScript")
                val guard = "if (window.location.origin !== '$it') return;"
                guard
            }
            .orEmpty()
        val openMostRecentConversationLiteral = openMostRecentConversation.toString()
        @Language("JavaScript")
        val script = """
            (() => {
              const directory = '$directory';
              const projectPath = '$escapedProjectPath';
              let path = projectPath;
              const storageKey = 'opencode.global.dat:server';
              const layoutStorageKey = 'opencode.global.dat:layout.page';
              const scope = 'local';
              const openMostRecentConversation = $openMostRecentConversationLiteral;
              let foundRecentSession = false;
              const routeDirectory = (value) => {
                const bytes = new TextEncoder().encode(value);
                let binary = '';
                bytes.forEach((byte) => binary += String.fromCharCode(byte));
                return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+${'$'}/g, '');
              };
              const decodeRouteDirectory = (value) => {
                try {
                  const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
                  const padded = base64 + '='.repeat((4 - base64.length % 4) % 4);
                  const binary = atob(padded);
                  const bytes = new Uint8Array(binary.length);
                  for (let index = 0; index < binary.length; index += 1) {
                    bytes[index] = binary.charCodeAt(index);
                  }
                  return new TextDecoder().decode(bytes);
                } catch (_) {
                  return '';
                }
              };
              const comparableDirectory = (value) => {
                const normalized = String(value || '').trim().replace(/\\/g, '/').replace(/\/+${'$'}/, '');
                return /^[A-Za-z]:\//.test(normalized) ? normalized[0].toLowerCase() + normalized.slice(1) : normalized;
              };
              const currentRouteDirectory = () => {
                const match = /^\/([^/?#]+)\/session(?:[/?#]|${'$'})/.exec(window.location.pathname);
                return match ? decodeRouteDirectory(match[1]) : '';
              };
              const onSameProjectRoute = () => comparableDirectory(currentRouteDirectory()) === comparableDirectory(directory);
              if (openMostRecentConversation) {
                try {
                  const rawLayout = window.localStorage.getItem(layoutStorageKey);
                  const layout = rawLayout ? JSON.parse(rawLayout) : undefined;
                  const session = layout && layout.lastProjectSession && layout.lastProjectSession[directory];
                  if (session && typeof session.directory === 'string' && typeof session.id === 'string') {
                    foundRecentSession = true;
                    path = '/' + routeDirectory(session.directory) + '/session/' + encodeURIComponent(session.id);
                  }
                } catch (_) {}
              }
              const navigationKey = 'opencode.intellij.project.opened:' + directory;
              const navigationPendingUntilKey = navigationKey + ':pending-until';
              const getNavigationState = () => {
                try {
                  return window.sessionStorage.getItem(navigationKey);
                } catch (_) {
                  return undefined;
                }
              };
              const setNavigationState = (value) => {
                try {
                  window.sessionStorage.setItem(navigationKey, value);
                } catch (_) {}
              };
              const shouldKeepWaitingForRecentSession = () => {
                if (!openMostRecentConversation || foundRecentSession) return false;
                try {
                  let pendingUntil = Number(window.sessionStorage.getItem(navigationPendingUntilKey) || '0');
                  const now = Date.now();
                  if (!Number.isFinite(pendingUntil) || pendingUntil <= 0) {
                    pendingUntil = now + 10000;
                    window.sessionStorage.setItem(navigationPendingUntilKey, String(pendingUntil));
                  }
                  return now < pendingUntil;
                } catch (_) {
                  return false;
                }
              };
              $originGuard
              try {
                const raw = window.localStorage.getItem(storageKey);
                const state = raw ? JSON.parse(raw) : { list: [], projects: {}, lastProject: {} };
                state.list = Array.isArray(state.list) ? state.list : [];
                state.projects = state.projects && typeof state.projects === 'object' ? state.projects : {};
                state.lastProject = state.lastProject && typeof state.lastProject === 'object' ? state.lastProject : {};
                const projects = Array.isArray(state.projects[scope]) ? state.projects[scope] : [];
                state.projects[scope] = [
                  { worktree: directory, expanded: true },
                  ...projects.filter((project) => project && project.worktree !== directory),
                ];
                state.lastProject[scope] = directory;
                window.localStorage.setItem(storageKey, JSON.stringify(state));
              } catch (error) {
                if (window.console && window.console.warn) {
                  window.console.warn('Failed to seed OpenCode project state', error);
                }
              }
              const projectSessionPrefix = projectPath + '/';
              const onProjectSessionRoute = window.location.pathname === projectPath || window.location.pathname.startsWith(projectSessionPrefix);
              const keepWaitingForRecentSession = shouldKeepWaitingForRecentSession();
              if (getNavigationState() === path && !keepWaitingForRecentSession) return;
              if (onSameProjectRoute() && !keepWaitingForRecentSession && !foundRecentSession) {
                setNavigationState(window.location.pathname);
                return;
              }
              if (window.location.pathname === path) {
                if (!keepWaitingForRecentSession) setNavigationState(path);
                return;
              }
              if (window.location.pathname !== projectPath && onProjectSessionRoute && !foundRecentSession) {
                setNavigationState(window.location.pathname);
                return;
              }
              if (window.location.pathname !== path) {
                if (!keepWaitingForRecentSession) setNavigationState(path);
                window.location.assign(path);
                return;
              }
              if (!keepWaitingForRecentSession) setNavigationState(path);
            })();
        """
        return script.trimIndent()
    }

    fun buildRestoreOpenCodeLocalStorageScript(snapshot: String?): String? {
        val text = snapshot?.trim().orEmpty()
        if (text.isBlank() || text == "{}") return null
        val payload = escapeJavaScript(text)
        @Language("JavaScript")
        val script = """
            (() => {
              const raw = '$payload';
              const exactKeys = new Set([
                '${OpenCodeServerProtocol.OPEN_CODE_THEME_ID_STORAGE_KEY}',
                '${OpenCodeServerProtocol.OPEN_CODE_COLOR_SCHEME_STORAGE_KEY}',
                'opencode-theme-css-light',
                'opencode-theme-css-dark',
                'settings.v3',
              ]);
              const globalKeys = /^opencode\.global\.dat:(language|model|layout|layout\.page|permission|notification|tabs|open\.app|go-upsell)${'$'}/;
              const workspaceKeys = /^opencode\.workspace\.[^:]+:workspace:(model-selection|terminal|project|icon|vcs)${'$'}/;
              const windowKeys = /^opencode\.window\.browser\.dat:tabs(\.recent)?${'$'}/;
              const shouldPersistKey = (key) => typeof key === 'string' && (exactKeys.has(key) || globalKeys.test(key) || workspaceKeys.test(key) || windowKeys.test(key));
              try {
                const snapshot = JSON.parse(raw);
                if (!snapshot || typeof snapshot !== 'object' || Array.isArray(snapshot)) return;
                for (const [key, value] of Object.entries(snapshot)) {
                  if (!shouldPersistKey(key) || typeof value !== 'string') continue;
                  if (window.localStorage.getItem(key) === null) {
                    window.localStorage.setItem(key, value);
                  }
                }
              } catch (error) {
                if (window.console && window.console.warn) {
                  window.console.warn('Failed to restore OpenCode localStorage snapshot', error);
                }
              }
            })();
        """
        return script.trimIndent()
    }

    fun buildSyncOpenCodeLocalStorageScript(openStorageCallback: String?): String? {
        if (openStorageCallback == null) return null
        @Language("JavaScript")
        val script = """
            (() => {
              const exactKeys = new Set([
                '${OpenCodeServerProtocol.OPEN_CODE_THEME_ID_STORAGE_KEY}',
                '${OpenCodeServerProtocol.OPEN_CODE_COLOR_SCHEME_STORAGE_KEY}',
                'opencode-theme-css-light',
                'opencode-theme-css-dark',
                'settings.v3',
              ]);
              const globalKeys = /^opencode\.global\.dat:(language|model|layout|layout\.page|permission|notification|tabs|open\.app|go-upsell)${'$'}/;
              const workspaceKeys = /^opencode\.workspace\.[^:]+:workspace:(model-selection|terminal|project|icon|vcs)${'$'}/;
              const windowKeys = /^opencode\.window\.browser\.dat:tabs(\.recent)?${'$'}/;
              const shouldPersistKey = (key) => typeof key === 'string' && (exactKeys.has(key) || globalKeys.test(key) || workspaceKeys.test(key) || windowKeys.test(key));
              // Bound each mirrored value so a single oversized entry (e.g. a huge cached theme
              // CSS) cannot bloat the IDE-side settings store.
              const MAX_VALUE_CHARS = 131072;
              const snapshot = () => {
                const entries = {};
                for (let index = 0; index < window.localStorage.length; index += 1) {
                  const key = window.localStorage.key(index);
                  if (!shouldPersistKey(key)) continue;
                  const value = window.localStorage.getItem(key);
                  if (typeof value === 'string' && value.length <= MAX_VALUE_CHARS) entries[key] = value;
                }
                return entries;
              };
              const send = () => {
                let payload = '{}';
                try {
                  payload = JSON.stringify(snapshot());
                } catch (_) {
                  return;
                }
                $openStorageCallback;
              };
              if (window.__opencodeIntellijLocalStorageSyncInstalled) {
                send();
                return;
              }
              window.__opencodeIntellijLocalStorageSyncInstalled = true;
              let pending = undefined;
              const queueSend = () => {
                if (pending !== undefined) window.clearTimeout(pending);
                pending = window.setTimeout(() => {
                  pending = undefined;
                  send();
                }, 100);
              };
              const originalSetItem = Storage.prototype.setItem;
              const originalRemoveItem = Storage.prototype.removeItem;
              const originalClear = Storage.prototype.clear;
              Storage.prototype.setItem = function(key, value) {
                const result = originalSetItem.apply(this, arguments);
                if (this === window.localStorage && shouldPersistKey(String(key))) queueSend();
                return result;
              };
              Storage.prototype.removeItem = function(key) {
                const result = originalRemoveItem.apply(this, arguments);
                if (this === window.localStorage && shouldPersistKey(String(key))) queueSend();
                return result;
              };
              Storage.prototype.clear = function() {
                const result = originalClear.apply(this, arguments);
                if (this === window.localStorage) queueSend();
                return result;
              };
              document.addEventListener('visibilitychange', () => {
                if (document.visibilityState === 'hidden') send();
              });
              window.addEventListener('pagehide', send);
              window.addEventListener('beforeunload', send);
              send();
            })();
        """
        return script.trimIndent()
    }

    fun buildExternalLinkHandlerScript(enabled: Boolean, openExternalCallback: String?): String? {
        if (!enabled || openExternalCallback == null) return null
        @Language("JavaScript")
        val script = """
            (() => {
              if (window.__opencodeIntellijExternalLinksInstalled) return;
              window.__opencodeIntellijExternalLinksInstalled = true;
              const externalHttpUrl = (rawHref, baseHref) => {
                if (!rawHref || rawHref.trim().startsWith('#')) return '';
                let url;
                try {
                  url = new URL(rawHref, baseHref || window.location.href);
                } catch (_) {
                  return '';
                }
                if (url.protocol !== 'http:' && url.protocol !== 'https:') return '';
                if (url.origin === window.location.origin) return '';
                return url.href;
              };
              const openExternal = (href) => {
                try {
                  $openExternalCallback;
                } catch (error) {
                  if (window.console && window.console.warn) {
                    window.console.warn('Failed to forward external link to IntelliJ', error);
                  }
                }
              };
              const nativeWindowOpen = window.open;
              window.__opencodeIntellijNativeWindowOpen = nativeWindowOpen;
              window.open = function(url, target, features) {
                const href = externalHttpUrl(typeof url === 'string' ? url : String(url == null ? '' : url));
                if (href) {
                  openExternal(href);
                  return null;
                }
                return nativeWindowOpen ? nativeWindowOpen.apply(window, arguments) : null;
              };
              document.addEventListener('click', (event) => {
                if (event.defaultPrevented) return;
                const link = event.target && event.target.closest ? event.target.closest('a') : null;
                if (!link) return;
                const href = externalHttpUrl(link.getAttribute('href'), link.href);
                if (!href) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                openExternal(href);
              }, true);
            })();
        """
        return script.trimIndent()
    }

    /**
     * Shared single-connection reader for the OpenCode `/global/event` stream.
     *
     * Chromium allows at most six concurrent HTTP/1.1 connections per host, shared across
     * every JCEF browser in the IDE, and each embedded OpenCode SPA already holds one
     * `/global/event` stream of its own. When the notification and agent-status bridges each
     * opened their own reader, two open projects were enough to exhaust the pool and stall
     * every further request (model lists, session data) in all panels.
     *
     * The hub keeps the plugin at a single stream per IDE application: pages elect a leader
     * via the Web Locks API, the leader owns the only `fetch('/global/event')` reader and
     * re-broadcasts parsed events to all pages through a BroadcastChannel. When the leader
     * page goes away, the lock is released and the next page takes over. If Web Locks or
     * BroadcastChannel are unavailable, each page falls back to one local reader, which is
     * still only half of the previous footprint.
     *
     * Both bridge scripts embed this bootstrap; the first one to run installs the hub.
     */
    @Language("JavaScript")
    private val EVENT_HUB_BOOTSTRAP = """
        (() => {
          if (window.__opencodeIntellijEventHub) return;
          const hub = {
            listeners: new Set(),
            connected: false,
            started: false,
            subscribe(listener) {
              this.listeners.add(listener);
              if (this.connected && listener.onConnect) {
                try { listener.onConnect(); } catch (_) {}
              }
              start();
            },
          };
          const controller = new AbortController();
          const sleep = (ms) => new Promise((resolve) => window.setTimeout(resolve, ms));
          const dispatch = (event) => {
            for (const listener of hub.listeners) {
              try {
                const result = listener.onEvent && listener.onEvent(event);
                if (result && typeof result.catch === 'function') result.catch(() => {});
              } catch (_) {}
            }
          };
          const notifyConnect = () => {
            hub.connected = true;
            for (const listener of hub.listeners) {
              if (!listener.onConnect) continue;
              try { listener.onConnect(); } catch (_) {}
            }
          };
          let channel = null;
          let leader = false;
          try {
            channel = new BroadcastChannel('opencode-intellij-event-hub');
            channel.onmessage = (message) => {
              const data = message && message.data;
              if (!data) return;
              if (data.kind === 'event') dispatch(data.event);
              else if (data.kind === 'connected') notifyConnect();
              else if (data.kind === 'hello' && leader && hub.connected) channel.postMessage({ kind: 'connected' });
            };
          } catch (_) {}
          const broadcast = (message) => {
            if (!channel) return;
            try { channel.postMessage(message); } catch (_) {}
          };
          const processSseBlock = (block) => {
            const data = block
              .split('\n')
              .filter((line) => line.startsWith('data:'))
              .map((line) => line.slice(5).trimStart())
              .join('\n')
              .trim();
            if (!data) return;
            try {
              const event = JSON.parse(data);
              dispatch(event);
              broadcast({ kind: 'event', event });
            } catch (error) {
              if (window.console && window.console.warn) {
                window.console.warn('Failed to parse OpenCode event', error);
              }
            }
          };
          const listen = async () => {
            leader = true;
            while (!controller.signal.aborted) {
              try {
                const response = await fetch('/global/event', {
                  headers: { Accept: 'text/event-stream' },
                  credentials: 'same-origin',
                  signal: controller.signal,
                });
                if (!response.ok || !response.body) throw new Error('OpenCode event stream unavailable');
                notifyConnect();
                broadcast({ kind: 'connected' });
                const reader = response.body.getReader();
                const decoder = new TextDecoder();
                let buffer = '';
                while (!controller.signal.aborted) {
                  const result = await reader.read();
                  if (result.done) break;
                  buffer += decoder.decode(result.value, { stream: true });
                  let separator = buffer.indexOf('\n\n');
                  while (separator >= 0) {
                    processSseBlock(buffer.slice(0, separator));
                    buffer = buffer.slice(separator + 2);
                    separator = buffer.indexOf('\n\n');
                  }
                }
                hub.connected = false;
              } catch (error) {
                hub.connected = false;
                if (controller.signal.aborted) return;
                if (window.console && window.console.warn) {
                  window.console.warn('OpenCode event stream disconnected', error);
                }
                await sleep(2000);
              }
            }
          };
          const start = () => {
            if (hub.started) return;
            hub.started = true;
            window.addEventListener('pagehide', () => controller.abort(), { once: true });
            if (channel && window.navigator && window.navigator.locks) {
              broadcast({ kind: 'hello' });
              window.navigator.locks.request('opencode-intellij-event-hub', listen).catch(() => { listen(); });
            } else {
              listen();
            }
          };
          window.__opencodeIntellijEventHub = hub;
        })();
    """.trimIndent()

    fun buildSystemNotificationBridgeScript(enabled: Boolean, notificationCallback: String?): String? {
        if (!enabled || notificationCallback == null) return null
        @Language("JavaScript")
        val script = """
            (() => {
              if (window.__opencodeIntellijNotificationBridgeInstalled) return;
              window.__opencodeIntellijNotificationBridgeInstalled = true;
              const encode = (value) => encodeURIComponent(String(value == null ? '' : value));
              const seen = new Set();
              const recentIdle = new Map();
              const focused = () => document.visibilityState === 'visible' && document.hasFocus();
              const projectName = (directory) => String(directory || '')
                .replace(/[\\/]+${'$'}/, '')
                .split(/[\\/]/)
                .pop() || String(directory || '');
              const encodeDirectory = (directory) => {
                const bytes = new TextEncoder().encode(String(directory));
                let binary = '';
                for (const byte of bytes) binary += String.fromCharCode(byte);
                return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+${'$'}/, '');
              };
              const routeFor = (directory, sessionID) => {
                const root = '/' + encodeDirectory(directory);
                return sessionID ? root + '/session/' + encodeURIComponent(sessionID) : root;
              };
              const sessionCache = new Map();
              const fetchSession = async (directory, sessionID) => {
                if (!sessionID) return null;
                const cacheKey = directory + '\n' + sessionID;
                if (sessionCache.has(cacheKey)) return sessionCache.get(cacheKey);
                try {
                  const response = await fetch(
                    '/session/' + encodeURIComponent(sessionID) + '?directory=' + encodeURIComponent(directory),
                    { credentials: 'same-origin' },
                  );
                  if (!response.ok) return null;
                  const value = await response.json();
                  const session = value && value.data ? value.data : value;
                  sessionCache.set(cacheKey, session);
                  return session;
                } catch (_) {
                  return null;
                }
              };
              const sendToIde = (notification) => {
                const encodedPayload = [
                  notification.id,
                  notification.directory,
                  notification.route,
                  notification.title,
                  notification.body,
                  notification.kind,
                  notification.sessionID,
                  notification.requestID,
                ].map(encode).join('\n');
                try {
                  const payload = encodedPayload;
                  $notificationCallback;
                } catch (error) {
                  if (window.console && window.console.warn) {
                    window.console.warn('Failed to forward OpenCode notification to IntelliJ', error);
                  }
                }
              };
              const handleEvent = async (event) => {
                const record = event && event.payload;
                const directory = event && typeof event.directory === 'string' ? event.directory : '';
                if (!directory || !record || typeof record.type !== 'string') return;
                if (focused()) return;
                const properties = record.properties || {};
                let type = record.type;
                // session.status is the successor of the deprecated session.idle event; current
                // servers emit both for the same state change.
                if (type === 'session.status') {
                  const status = properties.status;
                  if (!status || status.type !== 'idle') return;
                  type = 'session.idle';
                }
                if (
                  type !== 'session.idle' &&
                  type !== 'session.error' &&
                  type !== 'permission.asked' &&
                  type !== 'question.asked'
                ) return;

                const sessionID = typeof properties.sessionID === 'string' ? properties.sessionID : '';
                if (type === 'session.idle') {
                  // Merge the paired session.idle/session.status events without suppressing a
                  // genuine re-idle shortly after.
                  const idleKey = directory + '\n' + sessionID;
                  const now = Date.now();
                  if (now - (recentIdle.get(idleKey) || 0) < 5000) return;
                  recentIdle.set(idleKey, now);
                  window.setTimeout(() => { if (recentIdle.get(idleKey) === now) recentIdle.delete(idleKey); }, 5000);
                }
                const session = await fetchSession(directory, sessionID);
                if (type === 'session.idle' && (!session || session.parentID)) return;
                if (type === 'session.error' && session && session.parentID) return;

                let title = '';
                let body = '';
                if (type === 'session.idle') {
                  title = 'Response ready';
                  body = session.title || sessionID;
                } else if (type === 'session.error') {
                  title = 'Session error';
                  body = (session && session.title) || (typeof properties.error === 'string' ? properties.error : 'An error occurred');
                } else if (type === 'permission.asked') {
                  title = 'Permission required';
                  body = ((session && session.title) || 'New session') + ' in ' + projectName(directory) + ' needs permission';
                } else if (type === 'question.asked') {
                  title = 'Question';
                  body = ((session && session.title) || 'New session') + ' in ' + projectName(directory) + ' has a question';
                }
                if (!title || !body) return;

                const kind = type === 'permission.asked' ? 'permission' : (type === 'question.asked' ? 'question' : 'session');
                const requestID = (type === 'permission.asked' || type === 'question.asked') ? String(properties.id || '') : '';
                const id = String(record.id || [type, directory, sessionID, body].join('|'));
                if (seen.has(id)) return;
                seen.add(id);
                window.setTimeout(() => seen.delete(id), 30000);
                sendToIde({ id, directory, route: routeFor(directory, sessionID), title, body, kind, sessionID, requestID });
              };
              window.__opencodeIntellijEventHub.subscribe({ onEvent: handleEvent });
            })();
        """
        return EVENT_HUB_BOOTSTRAP + "\n" + script.trimIndent()
    }

    fun buildAgentStatusBridgeScript(projectBasePath: String?, enabled: Boolean, statusCallback: String?): String? {
        if (!enabled || statusCallback == null) return null
        if (projectBasePath.isNullOrBlank()) return null
        val directory = escapeJavaScript(projectBasePath)
        @Language("JavaScript")
        val script = """
            (() => {
              if (window.__opencodeIntellijAgentStatusInstalled) return;
              window.__opencodeIntellijAgentStatusInstalled = true;
              const directory = '$directory';
              const comparableDirectory = (value) => {
                const normalized = String(value || '').trim().replace(/\\/g, '/').replace(/\/+${'$'}/, '');
                return /^[A-Za-z]:\//.test(normalized) ? normalized[0].toLowerCase() + normalized.slice(1) : normalized;
              };
              const targetDirectory = comparableDirectory(directory);
              const busySessions = new Set();
              const attentionRequests = new Set();
              let lastState = '';
              const send = () => {
                const state = attentionRequests.size > 0 ? 'attention' : (busySessions.size > 0 ? 'busy' : 'idle');
                if (state === lastState) return;
                lastState = state;
                try {
                  $statusCallback;
                } catch (_) {}
              };
              const seed = async () => {
                try {
                  const response = await fetch('/session/status?directory=' + encodeURIComponent(directory), { credentials: 'same-origin' });
                  if (response.ok) {
                    const statuses = await response.json();
                    busySessions.clear();
                    if (statuses && typeof statuses === 'object' && !Array.isArray(statuses)) {
                      for (const [sessionID, status] of Object.entries(statuses)) {
                        if (status && (status.type === 'busy' || status.type === 'retry')) busySessions.add(sessionID);
                      }
                    }
                  }
                } catch (_) {}
                for (const path of ['/permission', '/question']) {
                  try {
                    const response = await fetch(path + '?directory=' + encodeURIComponent(directory), { credentials: 'same-origin' });
                    if (!response.ok) continue;
                    const requests = await response.json();
                    if (Array.isArray(requests)) {
                      for (const request of requests) {
                        if (request && typeof request.id === 'string') attentionRequests.add(request.id);
                      }
                    }
                  } catch (_) {}
                }
                send();
              };
              const handleEvent = (event) => {
                const record = event && event.payload;
                if (!record || typeof record.type !== 'string') return;
                if (comparableDirectory(event.directory) !== targetDirectory) return;
                const properties = record.properties || {};
                const type = record.type;
                if (type === 'session.status') {
                  const sessionID = String(properties.sessionID || '');
                  const status = properties.status;
                  if (!sessionID || !status) return;
                  if (status.type === 'busy' || status.type === 'retry') busySessions.add(sessionID); else busySessions.delete(sessionID);
                } else if (type === 'session.idle') {
                  busySessions.delete(String(properties.sessionID || ''));
                } else if (type === 'permission.asked' || type === 'question.asked') {
                  if (typeof properties.id === 'string') attentionRequests.add(properties.id);
                } else if (type === 'permission.replied' || type === 'question.replied' || type === 'question.rejected') {
                  if (typeof properties.requestID === 'string') attentionRequests.delete(properties.requestID);
                } else {
                  return;
                }
                send();
              };
              window.__opencodeIntellijEventHub.subscribe({
                onConnect: () => { seed().catch(() => {}); },
                onEvent: handleEvent,
              });
            })();
        """
        return EVENT_HUB_BOOTSTRAP + "\n" + script.trimIndent()
    }

    fun buildCodeNavigationScript(enabled: Boolean, openCodeCallback: String?): String? {
        if (!enabled || openCodeCallback == null) return null
        @Language("JavaScript")
        val script = """
            (() => {
              if (window.__opencodeIntellijCodeNavInstalled) return;
              window.__opencodeIntellijCodeNavInstalled = true;
              const hasExtension = /\.[a-zA-Z][a-zA-Z0-9]{0,8}(?::\d+)?${'$'}/;
              const hasPathSeparator = /[\\/]/;
              const isPascalCase = /^[A-Z][a-zA-Z0-9_]*${'$'}/;
              const isQualifiedClass = /^(?:[a-zA-Z_][a-zA-Z0-9_]*\.)+[A-Z][a-zA-Z0-9_]*${'$'}/;
              const isSnakeCase = /^[a-z][a-z0-9]*_[a-z0-9_]+${'$'}/;
              const looksLikeCodeRef = (text) => {
                const t = text.trim();
                if (t.length < 2 || t.length > 512) return false;
                if (t.includes(' ') || t.includes('\n')) return false;
                if (hasExtension.test(t)) return true;
                if (hasPathSeparator.test(t) && /:\d+${'$'}/.test(t)) return true;
                if (isPascalCase.test(t)) return true;
                if (isQualifiedClass.test(t)) return true;
                if (isSnakeCase.test(t) && /[A-Z]/.test(t)) return true;
                return false;
              };
              const extractRef = (codeEl) => {
                const text = (codeEl.textContent || '').trim();
                if (!looksLikeCodeRef(text)) return '';
                const parent = codeEl.parentElement;
                if (!parent) return text;
                const path = parent.getAttribute('data-path') || parent.getAttribute('data-file');
                if (path) return path;
                const lineMatch = text.match(/:(\d+)${'$'}/);
                if (lineMatch) return text;
                return text;
              };
              document.addEventListener('click', (event) => {
                if (event.defaultPrevented) return;
                const code = event.target && event.target.closest ? event.target.closest('code') : null;
                if (!code) return;
                const ref = extractRef(code);
                if (!ref) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                $openCodeCallback;
              }, true);
            })();
        """
        return script.trimIndent()
    }

    fun buildCompactLayoutScript(enabled: Boolean): String? {
        if (!enabled) return null
        @Language("JavaScript")
        val script = """
            (() => {
              if (window.__opencodeIntellijCompactInstalled) return;
              window.__opencodeIntellijCompactInstalled = true;
              // OpenCode checks both the wide query and its inverse (titlebar, settings), so both
              // must be patched for a consistent compact layout.
              const WIDE_QUERY = '(min-width: 768px)';
              const NARROW_QUERY = '(max-width: 767px)';
              const orig = window.matchMedia.bind(window);
              window.__opencodeIntellijOrigMatchMedia = orig;
              const stub = (media, matches) => ({ matches, media, onchange: null, addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false });
              window.matchMedia = (q) => {
                if (q === WIDE_QUERY) return stub(WIDE_QUERY, false);
                if (q === NARROW_QUERY) return stub(NARROW_QUERY, true);
                return orig(q);
              };
              const style = document.createElement('style');
              style.id = 'opencode-intellij-compact-layout';
              style.textContent = '@media (min-width: 768px) {\n  .md\\:flex-row { flex-direction: column !important; }\n  .md\\:flex-none { flex: 1 1 0% !important; }\n  .hidden.md\\:flex { display: none !important; }\n  .hidden.md\\:block { display: none !important; }\n}';
              // At the earliest injection point document.documentElement can still be null; retry
              // until a parent exists and re-attach if the SPA replaces the head content.
              const ensureStyle = () => {
                const parent = document.head || document.documentElement;
                if (!parent) return false;
                if (!style.isConnected) parent.appendChild(style);
                return true;
              };
              if (!ensureStyle()) {
                const observer = new MutationObserver(() => { if (ensureStyle()) observer.disconnect(); });
                observer.observe(document, { childList: true, subtree: true });
              }
              document.addEventListener('DOMContentLoaded', ensureStyle, { once: true });
            })();
        """
        return script.trimIndent()
    }

    fun buildIdeThemeSyncScript(enabled: Boolean, dark: Boolean): String? {
        if (!enabled) return null
        val darkLiteral = dark.toString()
        @Language("JavaScript")
        val script = """
            (() => {
              const QUERY = '(prefers-color-scheme: dark)';
              const dark = $darkLiteral;
              if (window.__opencodeIntellijThemeInstalled) {
                if (window.__opencodeIntellijThemeMql && window.__opencodeIntellijThemeDark !== dark) {
                  window.__opencodeIntellijThemeDark = dark;
                  window.__opencodeIntellijThemeMql.matches = dark;
                  window.__opencodeIntellijThemeMql.dispatchEvent(new MediaQueryListEvent('change', { matches: dark, media: QUERY }));
                }
                return;
              }
              window.__opencodeIntellijThemeInstalled = true;
              window.__opencodeIntellijThemeDark = dark;
              const orig = window.matchMedia.bind(window);
              window.__opencodeIntellijOrigMatchMedia = orig;
              const mql = {
                matches: dark,
                media: QUERY,
                onchange: null,
                __listeners: new Set(),
                addEventListener(type, listener) { if (type === 'change' && typeof listener === 'function') this.__listeners.add(listener); },
                removeEventListener(type, listener) { if (type === 'change') this.__listeners.delete(listener); },
                addListener(listener) { if (typeof listener === 'function') this.__listeners.add(listener); },
                removeListener(listener) { this.__listeners.delete(listener); },
                dispatchEvent(event) {
                  if (typeof this.onchange === 'function') this.onchange.call(this, event);
                  for (const listener of this.__listeners) { try { listener.call(this, event); } catch (_) {} }
                  return true;
                },
              };
              window.__opencodeIntellijThemeMql = mql;
              window.matchMedia = (q) => q === QUERY ? mql : orig(q);
            })();
        """
        return script.trimIndent()
    }

    fun buildProjectSwitchPromptSuppressionScript(enabled: Boolean): String? {
        if (!enabled) return null
        @Language("JavaScript")
        val script = """
            (() => {
              if (window.__opencodeIntellijProjectSwitchPromptSuppressionInstalled) return;
              window.__opencodeIntellijProjectSwitchPromptSuppressionInstalled = true;
              const notificationTitles = new Set(['Permission required', 'Question', 'Berechtigung erforderlich', 'Frage']);
              const goToSessionLabels = new Set(['Go to session', 'Zur Sitzung gehen']);
              const text = (element) => (element && element.textContent ? element.textContent : '').replace(/\s+/g, ' ').trim();
              const toastSelector = '[data-component="toast"], [data-component="toast-v2"]';
              const titleSelector = '[data-slot="toast-title"], [data-slot="toast-v2-title"]';
              const actionSelector = '[data-slot="toast-action"], [data-slot="toast-v2-actions"] button';
              const closeSelector = '[data-slot="toast-close-button"], [data-slot="toast-v2-close-button"]';
              const dismissIfProjectSwitchPrompt = (toast) => {
                const title = toast.querySelector(titleSelector);
                if (!notificationTitles.has(text(title))) return;
                const actions = Array.from(toast.querySelectorAll(actionSelector));
                if (!actions.some((action) => goToSessionLabels.has(text(action)))) return;
                const close = toast.querySelector(closeSelector);
                if (close && typeof close.click === 'function') {
                  close.click();
                } else {
                  toast.remove();
                }
              };
              const scan = (root) => {
                if (!root || root.nodeType !== Node.ELEMENT_NODE) return;
                if (root.matches && root.matches(toastSelector)) dismissIfProjectSwitchPrompt(root);
                if (root.querySelectorAll) root.querySelectorAll(toastSelector).forEach(dismissIfProjectSwitchPrompt);
              };
              const install = () => {
                const target = document.body || document.documentElement;
                if (!target) return;
                const observer = new MutationObserver((mutations) => {
                  for (const mutation of mutations) {
                    mutation.addedNodes.forEach(scan);
                  }
                });
                observer.observe(target, { childList: true, subtree: true });
                scan(target);
              };
              if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', install, { once: true });
              } else {
                install();
              }
            })();
        """
        return script.trimIndent()
    }

    fun buildDispatchDroppedFilesScript(files: List<OpenCodeServerProtocol.DroppedFilePayload>): String? {
        return buildDispatchDroppedFilesScript(files, enabled = true)
    }

    fun buildDispatchDroppedFilesScript(files: List<OpenCodeServerProtocol.DroppedFilePayload>, enabled: Boolean): String? {
        return buildDispatchDroppedFilesScript(files, textPlain = null, enabled)
    }

    fun buildDispatchDroppedFilesScript(
        files: List<OpenCodeServerProtocol.DroppedFilePayload>,
        textPlain: String?,
        enabled: Boolean,
    ): String? {
        return buildDispatchDroppedFilesScript(files, listOfNotNull(textPlain?.takeIf { it.isNotBlank() }), enabled)
    }

    fun buildDispatchDroppedFilesScript(
        files: List<OpenCodeServerProtocol.DroppedFilePayload>,
        textPlain: List<String>,
        enabled: Boolean,
    ): String? {
        val textEntries = textPlain.filter { it.isNotBlank() }
        if (!enabled || (files.isEmpty() && textEntries.isEmpty())) return null
        val fileEntries = files.joinToString(",\n") { file ->
            @Language("JavaScript")
            val entry = "{ name: '${escapeJavaScript(file.name)}', mime: '${escapeJavaScript(file.mime)}', lastModified: ${file.lastModified}, base64: '${escapeJavaScript(file.base64)}' }"
            entry
        }
        val textDrops = textEntries.joinToString("\n") {
            @Language("JavaScript")
            val drop = "dispatchDrop((transfer) => transfer.setData('text/plain', '${escapeJavaScript(it)}'));"
            drop
        }
        val fileDrop = if (files.isNotEmpty()) {
            @Language("JavaScript")
            val drop = """
                dispatchDrop((transfer) => {
                  const entries = [
                    $fileEntries
                  ];
                  for (const entry of entries) {
                    transfer.items.add(new File([decode(entry.base64)], entry.name, {
                      type: entry.mime,
                      lastModified: entry.lastModified,
                    }));
                  }
                });
            """
            drop.trimIndent()
        } else {
            ""
        }
        @Language("JavaScript")
        val script = """
            (() => {
              if (typeof DataTransfer !== 'function' || typeof File !== 'function' || typeof DragEvent !== 'function') {
                  console.warn('OpenCode Web Panel could not dispatch dropped files: browser drag APIs unavailable');
                  return;
                }
              const decode = (base64) => {
                const binary = atob(base64);
                const bytes = new Uint8Array(binary.length);
                for (let index = 0; index < binary.length; index += 1) {
                  bytes[index] = binary.charCodeAt(index);
                }
                return bytes;
              };
              const dispatchDrop = (fill) => {
                const previousActive = document.activeElement;
                const target = previousActive instanceof HTMLElement && document.contains(previousActive) ? previousActive : document;
                const transfer = new DataTransfer();
                fill(transfer);
                const options = { bubbles: true, cancelable: true, dataTransfer: transfer };
                target.dispatchEvent(new DragEvent('dragover', options));
                target.dispatchEvent(new DragEvent('drop', options));
                if (previousActive instanceof HTMLElement && previousActive.isContentEditable) {
                  requestAnimationFrame(() => {
                    if (document.contains(previousActive)) previousActive.focus();
                  });
                }
              };
              $textDrops
              $fileDrop
            })();
        """
        return script.trimIndent()
    }

    fun buildFilePasteSuppressionScript(enabled: Boolean): String? {
        if (!enabled) return null
        @Language("JavaScript")
        val script = """
            (() => {
              if (window.__opencodeIntellijFilePasteSuppressionInstalled) return;
              window.__opencodeIntellijFilePasteSuppressionInstalled = true;
              document.addEventListener('paste', (event) => {
                const clipboard = event.clipboardData;
                const hasFile = Array.from(clipboard?.items || []).some((item) => item.kind === 'file') ||
                  Array.from(clipboard?.types || []).includes('Files');
                if (!hasFile) return;
                event.preventDefault();
                event.stopImmediatePropagation();
              }, true);
            })();
        """
        return script.trimIndent()
    }

    fun buildFileLinkHandlerScript(projectBasePath: String?, enabled: Boolean): String? {
        return buildFileLinkHandlerScript(projectBasePath, enabled, openFileCallback = null)
    }

    fun buildFileLinkHandlerScript(projectBasePath: String?, enabled: Boolean, openFileCallback: String?): String? {
        if (!enabled) return null
        if (projectBasePath.isNullOrBlank()) return null
        val directory = escapeJavaScript(projectBasePath)

        @Language("JavaScript")
        val openFileFallback =
            "window.location.assign('${OpenCodeServerProtocol.OPEN_FILE_LINK_SCHEME}://${OpenCodeServerProtocol.OPEN_FILE_LINK_HOST}?href=' + encodeURIComponent(rawHref) + '&base=' + encodeURIComponent(directory))"
        val openFileAction = openFileCallback
            ?.let { callback ->
                @Language("JavaScript")
                val action = """
                    if (typeof window.cefQuery === 'function') {
                      try {
                        $callback;
                        return;
                      } catch (error) {
                        if (window.console && window.console.warn) {
                          window.console.warn('Failed to forward file link to IntelliJ', error);
                        }
                      }
                    }
                    $openFileFallback;
                """
                action.trimIndent()
            }
            ?: openFileFallback

        @Language("JavaScript")
        val script = """
            (() => {
              const FILE_LINKS_VERSION = 2;
              if ((window.__opencodeIntellijFileLinksInstalledVersion || 0) >= FILE_LINKS_VERSION) return;
              window.__opencodeIntellijFileLinksInstalled = true;
              window.__opencodeIntellijFileLinksInstalledVersion = FILE_LINKS_VERSION;
              const directory = '$directory';
              const unsupportedProtocol = /^(https?|mailto|tel|data|blob|javascript):/i;
              const absoluteFilePath = /^(\/|[A-Za-z]:[\\/])/;
              const decodeRouteDirectory = (value) => {
                try {
                  const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
                  const padded = base64 + '='.repeat((4 - base64.length % 4) % 4);
                  const binary = atob(padded);
                  const bytes = new Uint8Array(binary.length);
                  for (let index = 0; index < binary.length; index += 1) {
                    bytes[index] = binary.charCodeAt(index);
                  }
                  return new TextDecoder().decode(bytes);
                } catch (_) {
                  return '';
                }
              };
              const openCodeRoutePath = (value) => {
                const text = (value || '').trim();
                if (text.startsWith('/')) return text;
                if (!/^https?:\/\//i.test(text)) return '';
                try {
                  const url = new URL(text);
                  return url.origin === window.location.origin ? url.pathname : '';
                } catch (_) {
                  return '';
                }
              };
              const isOpenCodeAppRoute = (value) => {
                const path = openCodeRoutePath(value);
                const match = /^\/([^/?#]+)\/session(?:[/?#]|${'$'})/.exec(path);
                if (!match) return false;
                return absoluteFilePath.test(decodeRouteDirectory(match[1]));
              };
              const looksLikeFilePath = (value) => {
                if (!value) return false;
                const text = value.trim();
                return text.length > 0 && text.length < 512 && !/\s/.test(text) && /[./\\]/.test(text);
              };
              const inferredFileLink = (link) => {
                const row = link.closest ? link.closest('tr') : null;
                const cell = link.closest ? link.closest('td,th') : null;
                if (!row || !cell) return '';
                const cells = Array.from(row.children);
                const index = cells.indexOf(cell);
                if (index <= 0) return '';
                for (const candidate of cells.slice(0, index).reverse()) {
                  const text = (candidate.textContent || '').trim();
                  if (looksLikeFilePath(text)) return text;
                }
                return '';
              };
              let lastOpenedHref = '';
              let lastOpenedAt = 0;
              const cleanDisplayedPath = (value) => (value || '').replace(/[\u202A-\u202E]/g, '').trim();
              const changedFileButtonLink = (target) => {
                const button = target && target.closest ? target.closest('[data-slot="session-review-view-button"], button[aria-label="Open file"], button[aria-label="Datei öffnen"], button[title="Open file"], button[title="Datei öffnen"]') : null;
                if (!button) return '';
                const item = button.closest ? button.closest('[data-file], [data-path], [data-slot="session-review-accordion-item"]') : null;
                const dataPath = item ? (item.getAttribute('data-file') || item.getAttribute('data-path') || '') : '';
                if (dataPath) return dataPath;
                const info = (button.closest && button.closest('[data-slot="session-review-file-info"]')) ||
                  (item && item.querySelector ? item.querySelector('[data-slot="session-review-file-info"]') : null);
                const directory = cleanDisplayedPath(info && info.querySelector ? info.querySelector('[data-slot="session-review-directory"]')?.textContent : '');
                const fileName = cleanDisplayedPath(info && info.querySelector ? info.querySelector('[data-slot="session-review-filename"]')?.textContent : '');
                if (!fileName) return '';
                return directory ? directory.replace(/[\\/]?${'$'}/, '/') + fileName : fileName;
              };
              const isLocalFileLink = (href) => {
                if (!href || href.startsWith('#')) return false;
                if (isOpenCodeAppRoute(href)) return false;
                if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(href)) return !unsupportedProtocol.test(href);
                if (href.startsWith('/') || href.startsWith('./') || href.startsWith('../')) return true;
                if (/^[A-Za-z]:[\\/]/.test(href)) return true;
                return !href.startsWith('//') && !href.includes('://');
              };
              const openFileInIde = (rawHref) => {
                const now = Date.now();
                if (rawHref === lastOpenedHref && now - lastOpenedAt < 750) return;
                lastOpenedHref = rawHref;
                lastOpenedAt = now;
                $openFileAction;
              };
              const handleFileOpenEvent = (event, changedButtonOnly) => {
                if (event.defaultPrevented) return;
                const changedFileHref = changedFileButtonLink(event.target);
                if (changedButtonOnly && !changedFileHref) return;
                const link = !changedFileHref && event.target && event.target.closest ? event.target.closest('a') : null;
                const rawHref = changedFileHref || (link ? (link.getAttribute('href') || inferredFileLink(link)) : '');
                if (!isLocalFileLink(rawHref)) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                openFileInIde(rawHref);
              };
              document.addEventListener('pointerdown', (event) => handleFileOpenEvent(event, true), true);
              document.addEventListener('mousedown', (event) => handleFileOpenEvent(event, true), true);
              document.addEventListener('click', (event) => handleFileOpenEvent(event, false), true);
            })();
        """
        return script.trimIndent()
    }

    private fun escapeJavaScript(value: String): String {
        val builder = StringBuilder(value.length + 8)
        for (char in value) {
            when (char) {
                '\\' -> builder.append("\\\\")
                '\'' -> builder.append("\\'")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                // Escape '<' so an interpolated value can never break out of an inline <script> context.
                '<' -> builder.append("\\u003C")
                // U+2028/U+2029 are valid line terminators inside JS string literals and must be escaped.
                '\u2028' -> builder.append("\\u2028")
                '\u2029' -> builder.append("\\u2029")
                else -> if (char.code < 0x20) {
                    builder.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(char)
                }
            }
        }
        return builder.toString()
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
