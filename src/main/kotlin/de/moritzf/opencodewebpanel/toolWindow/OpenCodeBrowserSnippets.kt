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
                'opencode-color-scheme',
                'opencode-theme-css-light',
                'opencode-theme-css-dark',
              ]);
              const globalKeys = /^opencode\.global\.dat:(language|model|layout|layout\.page|permission|notification|tabs|open\.app|go-upsell)${'$'}/;
              const workspaceKeys = /^opencode\.workspace\.[^:]+:workspace:(model-selection|terminal|project|icon|vcs)${'$'}/;
              const shouldPersistKey = (key) => typeof key === 'string' && (exactKeys.has(key) || globalKeys.test(key) || workspaceKeys.test(key));
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
                'opencode-color-scheme',
                'opencode-theme-css-light',
                'opencode-theme-css-dark',
              ]);
              const globalKeys = /^opencode\.global\.dat:(language|model|layout|layout\.page|permission|notification|tabs|open\.app|go-upsell)${'$'}/;
              const workspaceKeys = /^opencode\.workspace\.[^:]+:workspace:(model-selection|terminal|project|icon|vcs)${'$'}/;
              const shouldPersistKey = (key) => typeof key === 'string' && (exactKeys.has(key) || globalKeys.test(key) || workspaceKeys.test(key));
              const snapshot = () => {
                const entries = {};
                for (let index = 0; index < window.localStorage.length; index += 1) {
                  const key = window.localStorage.key(index);
                  if (!shouldPersistKey(key)) continue;
                  const value = window.localStorage.getItem(key);
                  if (typeof value === 'string') entries[key] = value;
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

    fun buildStartupErrorPageHtml(executable: String): String {
        val path = escapeHtml(executable.ifBlank { OpenCodeServerProtocol.DEFAULT_EXECUTABLE })
        @Language("HTML")
        val html = """
            <html>
            <body style="background-color: #2B2B2B; color: #A9B7C6; font-family: sans-serif; padding: 20px;">
                <h2>Failed to start OpenCode server</h2>
                <p>OpenCode Web Panel tried to start <code>$path</code>, but the server did not become available.</p>
                <p>Check <strong>Settings &gt; Tools &gt; OpenCode Web Panel &gt; OpenCode Server Setup</strong> to configure the OpenCode executable path, use auto-detection, or adjust the port.</p>
                <p>You can also verify the executable manually:</p>
                <pre style="background: #3C3F41; padding: 10px; border-radius: 4px;">opencode serve --hostname 127.0.0.1 --port 0 --print-logs</pre>
            </body>
            </html>
        """
        return html.trimIndent()
    }

    fun buildSystemNotificationBridgeScript(enabled: Boolean, notificationCallback: String?): String? {
        if (!enabled || notificationCallback == null) return null
        @Language("JavaScript")
        val script = """
            (() => {
              if (window.__opencodeIntellijNotificationBridgeInstalled) return;
              window.__opencodeIntellijNotificationBridgeInstalled = true;
              window.__opencodeIntellijNativeNotification = window.Notification;
              const notifications = new Map();
              let nextNotificationId = 1;
              const encode = (value) => encodeURIComponent(String(value == null ? '' : value));
              const sendToIde = (notification) => {
                const payload = [
                  notification.__opencodeIntellijNotificationId,
                  notification.title,
                  notification.body,
                ].map(encode).join('\n');
                try {
                  $notificationCallback;
                } catch (error) {
                  if (window.console && window.console.warn) {
                    window.console.warn('Failed to forward OpenCode notification to IntelliJ', error);
                  }
                }
              };
              const createEvent = (type, notification) => ({
                type,
                target: notification,
                currentTarget: notification,
                defaultPrevented: false,
                preventDefault() { this.defaultPrevented = true; },
                stopPropagation() {},
              });
              class IntellijNotification {
                constructor(title, options = {}) {
                  this.__opencodeIntellijNotificationId = String(nextNotificationId++);
                  this.title = String(title == null ? '' : title);
                  this.body = String(options && options.body != null ? options.body : '');
                  this.icon = String(options && options.icon != null ? options.icon : '');
                  this.tag = String(options && options.tag != null ? options.tag : '');
                  this.data = options && 'data' in options ? options.data : null;
                  this.onclick = null;
                  this.onclose = null;
                  this.onerror = null;
                  this.onshow = null;
                  this.__listeners = new Map();
                  notifications.set(this.__opencodeIntellijNotificationId, this);
                  window.setTimeout(() => {
                    sendToIde(this);
                    this.dispatchEvent(createEvent('show', this));
                  }, 0);
                }
                close() {
                  notifications.delete(this.__opencodeIntellijNotificationId);
                  this.dispatchEvent(createEvent('close', this));
                }
                addEventListener(type, listener) {
                  if (typeof listener !== 'function') return;
                  const listeners = this.__listeners.get(type) || [];
                  listeners.push(listener);
                  this.__listeners.set(type, listeners);
                }
                removeEventListener(type, listener) {
                  const listeners = this.__listeners.get(type) || [];
                  this.__listeners.set(type, listeners.filter((candidate) => candidate !== listener));
                }
                dispatchEvent(event) {
                  const handler = this['on' + event.type];
                  if (typeof handler === 'function') handler.call(this, event);
                  for (const listener of this.__listeners.get(event.type) || []) {
                    listener.call(this, event);
                  }
                  return !event.defaultPrevented;
                }
                static requestPermission(callback) {
                  const promise = Promise.resolve('granted');
                  if (typeof callback === 'function') promise.then(callback);
                  return promise;
                }
              }
              Object.defineProperty(IntellijNotification, 'permission', { configurable: true, enumerable: true, get: () => 'granted' });
              Object.defineProperty(IntellijNotification, 'maxActions', { configurable: true, enumerable: true, get: () => 0 });
              window.__opencodeIntellijNotificationClick = (notificationId) => {
                const notification = notifications.get(String(notificationId));
                if (!notification) return;
                notification.dispatchEvent(createEvent('click', notification));
              };
              try {
                Object.defineProperty(window, 'Notification', { configurable: true, writable: true, value: IntellijNotification });
              } catch (_) {
                window.Notification = IntellijNotification;
              }
            })();
        """
        return script.trimIndent()
    }

    fun buildSystemNotificationClickScript(notificationId: String): String {
        val id = escapeJavaScript(notificationId)
        @Language("JavaScript")
        val script = """
            (() => {
              if (typeof window.focus === 'function') window.focus();
              const click = window.__opencodeIntellijNotificationClick;
              if (typeof click === 'function') click('$id');
            })();
        """
        return script.trimIndent()
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
              const QUERY = '(min-width: 768px)';
              const orig = window.matchMedia.bind(window);
              window.__opencodeIntellijOrigMatchMedia = orig;
              window.matchMedia = (q) => {
                if (q !== QUERY) return orig(q);
                return { matches: false, media: QUERY, onchange: null, addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false };
              };
              const style = document.createElement('style');
              style.id = 'opencode-intellij-compact-layout';
              style.textContent = '@media (min-width: 768px) {\n  .md\\:flex-row { flex-direction: column !important; }\n  .md\\:flex-none { flex: 1 1 0% !important; }\n  .hidden.md\\:flex { display: none !important; }\n  .hidden.md\\:block { display: none !important; }\n}';
              const ensureStyle = () => {
                if (!document.getElementById('opencode-intellij-compact-layout')) {
                  (document.head || document.documentElement).appendChild(style);
                }
              };
              if (document.head) {
                ensureStyle();
              } else {
                const observer = new MutationObserver(() => { ensureStyle(); if (document.getElementById('opencode-intellij-compact-layout')) observer.disconnect(); });
                observer.observe(document.documentElement, { childList: true, subtree: true });
              }
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
        if (!enabled || files.isEmpty()) return null
        val fileEntries = files.joinToString(",\n") { file ->
            @Language("JavaScript")
            val entry = "{ name: '${escapeJavaScript(file.name)}', mime: '${escapeJavaScript(file.mime)}', lastModified: ${file.lastModified}, base64: '${file.base64}' }"
            entry
        }
        @Language("JavaScript")
        val script = """
            (() => {
              if (typeof DataTransfer !== 'function' || typeof File !== 'function' || typeof DragEvent !== 'function') {
                console.warn('OpenCode Web Panel could not dispatch dropped files: browser drag APIs unavailable');
                return;
              }
              const entries = [
                $fileEntries
              ];
              const transfer = new DataTransfer();
              const decode = (base64) => {
                const binary = atob(base64);
                const bytes = new Uint8Array(binary.length);
                for (let index = 0; index < binary.length; index += 1) {
                  bytes[index] = binary.charCodeAt(index);
                }
                return bytes;
              };
              for (const entry of entries) {
                transfer.items.add(new File([decode(entry.base64)], entry.name, {
                  type: entry.mime,
                  lastModified: entry.lastModified,
                }));
              }
              const options = { bubbles: true, cancelable: true, dataTransfer: transfer };
              document.dispatchEvent(new DragEvent('dragover', options));
              document.dispatchEvent(new DragEvent('drop', options));
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
        val openFileAction = openFileCallback
            ?: run {
                @Language("JavaScript")
                val action = "window.location.assign('${OpenCodeServerProtocol.OPEN_FILE_LINK_SCHEME}://${OpenCodeServerProtocol.OPEN_FILE_LINK_HOST}?href=' + encodeURIComponent(rawHref) + '&base=' + encodeURIComponent(directory))"
                action
            }
        @Language("JavaScript")
        val script = """
            (() => {
              if (window.__opencodeIntellijFileLinksInstalled) return;
              window.__opencodeIntellijFileLinksInstalled = true;
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
              const isLocalFileLink = (href) => {
                if (!href || href.startsWith('#')) return false;
                if (isOpenCodeAppRoute(href)) return false;
                if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(href)) return !unsupportedProtocol.test(href);
                if (href.startsWith('/') || href.startsWith('./') || href.startsWith('../')) return true;
                if (/^[A-Za-z]:[\\/]/.test(href)) return true;
                return !href.startsWith('//') && !href.includes('://');
              };
              document.addEventListener('click', (event) => {
                if (event.defaultPrevented) return;
                const link = event.target && event.target.closest ? event.target.closest('a') : null;
                if (!link) return;
                const rawHref = link.getAttribute('href') || inferredFileLink(link);
                if (!isLocalFileLink(rawHref)) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                $openFileAction;
              }, true);
            })();
        """
        return script.trimIndent()
    }

    private fun escapeJavaScript(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
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
