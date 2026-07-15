package de.moritzf.opencodewebpanel.browser

import org.intellij.lang.annotations.Language
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol

internal object OpenCodeBrowserSnippets {

    /** Decodes a base64url route segment back to the project directory. Shared by the builders below. */
    @Language("JavaScript")
    private val DECODE_ROUTE_DIRECTORY_JS = """
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
    """.trimIndent()

    /**
     * Hovered interactive elements get the pointer cursor; the cursor mirror reads computed
     * styles, so the embedded panel cursor follows automatically. Callers wire their own
     * mouseover listener that calls `markHovered(elementOrNull)`.
     */
    @Language("JavaScript")
    private val POINTER_CURSOR_KIT_JS = """
        const POINTER_ATTR = 'data-opencode-intellij-pointer';
        const ensurePointerCursorStyle = () => {
          if (window.__opencodeIntellijPointerCursorStyleInstalled) return;
          window.__opencodeIntellijPointerCursorStyleInstalled = true;
          const style = document.createElement('style');
          style.textContent = '[' + POINTER_ATTR + '], [' + POINTER_ATTR + '] * { cursor: pointer !important; }';
          (document.head || document.documentElement).appendChild(style);
        };
        let hoveredElement = null;
        const markHovered = (element) => {
          if (element === hoveredElement) return;
          if (hoveredElement) hoveredElement.removeAttribute(POINTER_ATTR);
          hoveredElement = element;
          if (hoveredElement) {
            ensurePointerCursorStyle();
            hoveredElement.setAttribute(POINTER_ATTR, '');
          }
        };
        document.addEventListener('mouseout', (event) => {
          if (!event.relatedTarget) markHovered(null);
        }, true);
    """.trimIndent()

    /** The OpenCode localStorage keys mirrored into the IDE-side settings store. */
    @Language("JavaScript")
    private val PERSISTED_STORAGE_KEY_FILTER_JS = """
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
    """.trimIndent()

    /**
     * Maps a CSS cursor computed value to the closest AWT predefined cursor type. Custom
     * `url(...)` cursors resolve through their keyword fallback; CSS values without an AWT
     * counterpart (help, copy, zoom-in, ...) fall back to the default arrow.
     */
    fun awtCursorTypeForCss(cssCursor: String?): Int {
        val keyword = cssCursor?.split(',')
            ?.map { it.trim().lowercase() }
            ?.lastOrNull { it.isNotBlank() && !it.startsWith("url(") }
            ?: return java.awt.Cursor.DEFAULT_CURSOR
        return when (keyword) {
            "pointer" -> java.awt.Cursor.HAND_CURSOR
            "text", "vertical-text" -> java.awt.Cursor.TEXT_CURSOR
            "wait", "progress" -> java.awt.Cursor.WAIT_CURSOR
            "crosshair", "cell" -> java.awt.Cursor.CROSSHAIR_CURSOR
            "move", "grab", "grabbing", "all-scroll" -> java.awt.Cursor.MOVE_CURSOR
            "n-resize" -> java.awt.Cursor.N_RESIZE_CURSOR
            "s-resize", "ns-resize", "row-resize" -> java.awt.Cursor.S_RESIZE_CURSOR
            "e-resize" -> java.awt.Cursor.E_RESIZE_CURSOR
            "w-resize", "ew-resize", "col-resize" -> java.awt.Cursor.W_RESIZE_CURSOR
            "ne-resize", "nesw-resize" -> java.awt.Cursor.NE_RESIZE_CURSOR
            "nw-resize", "nwse-resize" -> java.awt.Cursor.NW_RESIZE_CURSOR
            "se-resize" -> java.awt.Cursor.SE_RESIZE_CURSOR
            "sw-resize" -> java.awt.Cursor.SW_RESIZE_CURSOR
            else -> java.awt.Cursor.DEFAULT_CURSOR
        }
    }

    fun buildOpenProjectScript(
        projectBasePath: String?,
        serverUrl: String? = null,
        openMostRecentConversation: Boolean = false,
        mostRecentSessionId: String? = null,
    ): String? {
        if (projectBasePath.isNullOrBlank()) return null
        val directory = escapeJavaScript(projectBasePath)
        val providedSessionId = mostRecentSessionId
            ?.takeIf { OpenCodeServerProtocol.isOpenCodeRecordId(it) }
            ?.let(::escapeJavaScript)
            .orEmpty()
        val escapedProjectPath = escapeJavaScript("/${OpenCodeServerProtocol.encodeDirectory(projectBasePath)}/session")
        val originGuard = serverUrl?.let(OpenCodeServerProtocol::buildOrigin)
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
              $DECODE_ROUTE_DIRECTORY_JS
              const comparableDirectory = (value) => {
                const normalized = String(value || '').trim().replace(/\\/g, '/').replace(/\/+$/, '');
                return /^[A-Za-z]:\//.test(normalized) ? normalized[0].toLowerCase() + normalized.slice(1) : normalized;
              };
              const pathKey = (value) => {
                const text = String(value || '');
                const isWindows = (text.length >= 2 && text.charAt(1) === ':') || text.startsWith('\\\\');
                let normalized = isWindows ? text.replace(/\\/g, '/') : text;
                while (normalized.endsWith('/')) normalized = normalized.slice(0, -1);
                if (!normalized && text.startsWith('/')) return '/';
                if (/^[A-Za-z]:$/.test(normalized)) return normalized + '/';
                return normalized;
              };
              const findLastProjectSession = (layout) => {
                if (!layout || typeof layout.lastProjectSession !== 'object' || layout.lastProjectSession === null) return undefined;
                const targetKey = pathKey(directory);
                // The session's own directory must also match this panel's project: the value is
                // the navigation target, and a stale or inconsistent entry must never send the
                // panel to another project's session.
                const isUsable = (value) => value && typeof value.directory === 'string' && typeof value.id === 'string' && pathKey(value.directory) === targetKey;
                const raw = layout.lastProjectSession[directory];
                if (isUsable(raw)) return raw;
                for (const [key, value] of Object.entries(layout.lastProjectSession)) {
                  if (pathKey(key) === targetKey && isUsable(value)) {
                    return value;
                  }
                }
                return undefined;
              };
              const currentRouteDirectory = () => {
                const match = /^\/([^/?#]+)\/session(?:[/?#]|${'$'})/.exec(window.location.pathname);
                return match ? decodeRouteDirectory(match[1]) : '';
              };
              const onSameProjectRoute = () => comparableDirectory(currentRouteDirectory()) === comparableDirectory(directory);
              // The session with the newest message, resolved server-side by the IDE. The
              // localStorage layout below only knows the session this panel viewed last and
              // serves as the fallback when the server lookup failed.
              const providedSessionId = '$providedSessionId';
              if (openMostRecentConversation && providedSessionId) {
                foundRecentSession = true;
                path = '/' + routeDirectory(directory) + '/session/' + encodeURIComponent(providedSessionId);
              } else if (openMostRecentConversation) {
                try {
                  const rawLayout = window.localStorage.getItem(layoutStorageKey);
                  const layout = rawLayout ? JSON.parse(rawLayout) : undefined;
                  const session = findLastProjectSession(layout);
                  if (session) {
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
              // The redesigned layout uses directoryless routes (/server/<id>/session...,
              // /new-session) whose project is the SPA's own current project. Its only
              // pre-navigation trace is the persisted lastProject entry - capture it before
              // the seeding below overwrites it, so a panel can tell whether such a route
              // already shows its project or a stale/foreign one (e.g. after a directory
              // rename or when another IDE project seeded the shared browser profile last).
              let directorylessProject = '';
              try {
                const rawServerState = window.localStorage.getItem(storageKey);
                const serverState = rawServerState ? JSON.parse(rawServerState) : undefined;
                const lastProject = serverState && serverState.lastProject && typeof serverState.lastProject === 'object'
                  ? serverState.lastProject[scope]
                  : undefined;
                if (typeof lastProject === 'string') directorylessProject = lastProject;
              } catch (_) {}
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
              if (window.location.pathname !== projectPath && onProjectSessionRoute) {
                // Already viewing a specific conversation of this project (possibly opened by
                // the user between scheduled runs of this script) - never navigate away from it.
                setNavigationState(window.location.pathname);
                return;
              }
              const onDirectorylessSessionRoute =
                /^\/server\/[^\/]+\/session(?:\/|${'$'})/.test(window.location.pathname) ||
                /^\/new-session(?:\/|${'$'})/.test(window.location.pathname);
              const onDirectorylessConversation =
                /^\/server\/[^\/]+\/session\/[^\/?#]/.test(window.location.pathname) ||
                /^\/new-session(?:\/|${'$'})/.test(window.location.pathname);
              if (onDirectorylessSessionRoute && directorylessProject && pathKey(directorylessProject) === pathKey(directory)) {
                // The directoryless route already shows this panel's project: stay on a viewed
                // conversation or new-session composer; from the bare project root, still move
                // to the most recent conversation once one is known.
                if (onDirectorylessConversation || !foundRecentSession) {
                  setNavigationState(window.location.pathname);
                  return;
                }
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
              $PERSISTED_STORAGE_KEY_FILTER_JS
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
              $PERSISTED_STORAGE_KEY_FILTER_JS
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
                if (isSnakeCase.test(t)) return true;
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
              $POINTER_CURSOR_KIT_JS
              document.addEventListener('mouseover', (event) => {
                const code = event.target && event.target.closest ? event.target.closest('code') : null;
                markHovered(code && extractRef(code) ? code : null);
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

    /**
     * Mirrors the web page's mouse cursor to the IDE. JCEF's off-screen rendering does not
     * reliably propagate Chromium's cursor changes to the Swing component, so the embedded
     * panel never shows text or link cursors and can get stuck with a stale resize cursor.
     * This tracks the hovered element's effective CSS cursor (including the I-beam that
     * browsers render for `cursor: auto` over selectable text) and reports each transition
     * through [cursorCallback]; the IDE applies the matching AWT cursor to the panel. While
     * a button is held the cursor from the drag start is kept, matching Chromium's own
     * behavior during drags.
     */
    fun buildCursorMirrorScript(enabled: Boolean, cursorCallback: String?): String? {
        if (!enabled || cursorCallback == null) return null
        @Language("JavaScript")
        val script = """
            (() => {
              if (window.__opencodeIntellijCursorMirrorInstalled) return;
              window.__opencodeIntellijCursorMirrorInstalled = true;
              let lastSent = '';
              let lastX = -1;
              let lastY = -1;
              const send = (cursor) => {
                if (!cursor || cursor === lastSent) return;
                lastSent = cursor;
                try {
                  const payload = cursor;
                  $cursorCallback;
                } catch (_) {}
              };
              const effectiveCursor = (x, y, target) => {
                const el = target && target.nodeType === 1 ? target : (target && target.parentElement);
                if (!el) return 'default';
                const cursor = getComputedStyle(el).cursor;
                if (cursor !== 'auto') return cursor;
                if (el.isContentEditable) return 'text';
                const tag = el.tagName;
                if (tag === 'TEXTAREA') return 'text';
                if (tag === 'INPUT' && !/^(button|submit|reset|checkbox|radio|range|file|color|image)${'$'}/i.test(el.type)) return 'text';
                // Over selectable text, auto renders as the text I-beam. caretRangeFromPoint
                // snaps to the nearest text, so require the point to be inside its element.
                if (document.caretRangeFromPoint) {
                  const range = document.caretRangeFromPoint(x, y);
                  const node = range && range.startContainer;
                  if (node && node.nodeType === 3 && node.parentElement) {
                    const rect = node.parentElement.getBoundingClientRect();
                    if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) return 'text';
                  }
                }
                return 'default';
              };
              const recomputeAtPointer = () => {
                if (lastX < 0) return;
                const el = document.elementFromPoint(lastX, lastY);
                if (el) send(effectiveCursor(lastX, lastY, el));
              };
              document.addEventListener('pointermove', (event) => {
                lastX = event.clientX;
                lastY = event.clientY;
                if (event.buttons !== 0) return;
                send(effectiveCursor(event.clientX, event.clientY, event.target));
              }, true);
              document.addEventListener('pointerdown', (event) => {
                send(effectiveCursor(event.clientX, event.clientY, event.target));
              }, true);
              document.addEventListener('pointerup', (event) => {
                const el = document.elementFromPoint(event.clientX, event.clientY);
                send(effectiveCursor(event.clientX, event.clientY, el || event.target));
              }, true);
              // Content moving under a stationary pointer also changes the cursor in a browser.
              let scrollRecomputeQueued = false;
              window.addEventListener('scroll', () => {
                if (scrollRecomputeQueued) return;
                scrollRecomputeQueued = true;
                window.requestAnimationFrame(() => {
                  scrollRecomputeQueued = false;
                  recomputeAtPointer();
                });
              }, true);
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

    fun buildDispatchDroppedFilesScript(
        files: List<OpenCodeServerProtocol.DroppedFilePayload>,
        textPlain: List<String> = emptyList(),
        enabled: Boolean = true,
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

    fun buildFileLinkHandlerScript(projectBasePath: String?, enabled: Boolean, openFileCallback: String? = null): String? {
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
              if (window.__opencodeIntellijFileLinksInstalled) return;
              window.__opencodeIntellijFileLinksInstalled = true;
              const directory = '$directory';
              const unsupportedProtocol = /^(https?|mailto|tel|data|blob|javascript):/i;
              const absoluteFilePath = /^(\/|[A-Za-z]:[\\/])/;
              $DECODE_ROUTE_DIRECTORY_JS
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
              const changedFileButtonSelector = '[data-slot="session-review-view-button"], button[aria-label="Open file"], button[aria-label="Datei öffnen"], button[title="Open file"], button[title="Datei öffnen"]';
              const changedFileButtonLink = (target) => {
                const button = target && target.closest ? target.closest(changedFileButtonSelector) : null;
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
              // The redesigned (v2) review panel (default new layout on desktop, i.e. when
              // forceCompactLayout is off) drops the per-file "open" button for an in-app sidebar
              // tree + preview. The sidebar rows (button[data-path]) are the SPA's own preview
              // navigation, so hijacking them would break it; the safe "open in IDE" surface is the
              // non-interactive preview header file name/path (session-review-v2-file-*).
              const reviewV2FileButtonSelector = '[data-slot="session-review-v2-file-title"]';
              const reviewV2FileSelector = reviewV2FileButtonSelector + ', [data-slot="session-review-v2-file-name"], [data-slot="session-review-v2-file-path"]';
              const reviewV2FileLink = (target) => {
                const title = target && target.closest ? target.closest(reviewV2FileSelector) : null;
                if (!title) return '';
                const header = (title.closest && title.closest('[data-slot="session-review-v2-file-header"]')) || title;
                const fileName = cleanDisplayedPath(header.querySelector ? header.querySelector('[data-slot="session-review-v2-file-name"]')?.textContent : '');
                if (!fileName) return '';
                const directory = cleanDisplayedPath(header.querySelector ? header.querySelector('[data-slot="session-review-v2-file-path"]')?.textContent : '');
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
              const resolveFileOpenTarget = (target, changedButtonOnly) => {
                const changedFileHref = changedFileButtonLink(target);
                const reviewV2Href = changedFileHref ? '' : reviewV2FileLink(target);
                if (changedButtonOnly && !changedFileHref && !reviewV2Href) return null;
                const link = !changedFileHref && !reviewV2Href && target && target.closest ? target.closest('a') : null;
                const rawHref = changedFileHref || reviewV2Href || (link ? (link.getAttribute('href') || inferredFileLink(link)) : '');
                if (!isLocalFileLink(rawHref)) return null;
                const element = changedFileHref
                  ? target.closest(changedFileButtonSelector)
                  : (reviewV2Href ? target.closest(reviewV2FileButtonSelector) : link);
                return { element: element, href: rawHref };
              };
              const handleFileOpenEvent = (event, changedButtonOnly) => {
                if (event.defaultPrevented) return;
                // Alt is reserved for the IDE diff gesture (buildDiffNavigationScript); a plain
                // click still opens the file.
                if (event.altKey) return;
                const resolved = resolveFileOpenTarget(event.target, changedButtonOnly);
                if (!resolved) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                openFileInIde(resolved.href);
              };
              document.addEventListener('pointerdown', (event) => handleFileOpenEvent(event, true), true);
              document.addEventListener('mousedown', (event) => handleFileOpenEvent(event, true), true);
              document.addEventListener('click', (event) => handleFileOpenEvent(event, false), true);
              $POINTER_CURSOR_KIT_JS
              document.addEventListener('mouseover', (event) => {
                const target = event.target && event.target.nodeType === 1 ? event.target : null;
                const resolved = target ? resolveFileOpenTarget(target, false) : null;
                markHovered(resolved ? resolved.element : null);
              }, true);
            })();
        """
        return script.trimIndent()
    }

    /**
     * Installs an Alt+Click handler that opens the IDE diff viewer for a diff target in the
     * OpenCode page. Recognises changes/review-panel rows (`[data-file]` → cumulative session
     * diff for that file), chat edit/write blocks (`[data-component="edit-tool"|"write-tool"]`
     * inside a `[data-message]` → that message's diff for the edited file) and any diff indicator
     * (`[data-component="diff-changes"]` → that message's diff, all files). Forwards
     * `messageID + "\n" + filePath` (both may be empty) to the JVM via [openDiffCallback]; the JVM
     * derives the session id and directory itself. Returns null when disabled or without a callback.
     */
    fun buildDiffNavigationScript(enabled: Boolean, openDiffCallback: String? = null): String? {
        if (!enabled || openDiffCallback == null) return null

        @Language("JavaScript")
        val script = """
            (() => {
              if (window.__opencodeIntellijDiffNavInstalled) return;
              window.__opencodeIntellijDiffNavInstalled = true;
              const clean = (value) => (value || '').replace(/[\u202A-\u202E]/g, '').trim();
              const messageIdOf = (node) => {
                const el = node && node.closest ? node.closest('[data-message-id], [data-message]') : null;
                return el ? (el.getAttribute('data-message-id') || el.getAttribute('data-message') || '') : '';
              };
              const pathFrom = (root, dirSel, nameSel) => {
                if (!root || !root.querySelector) return '';
                const dir = clean(root.querySelector(dirSel)?.textContent);
                const name = clean(root.querySelector(nameSel)?.textContent);
                if (!name) return '';
                return dir ? dir.replace(/[\\/]?${'$'}/, '/') + name : name;
              };
              // The server keys /session/{id}/diff by the turn's *user* message id (an empty or
              // assistant id yields []), and data-message-id sits on the turn container, so every
              // target resolves its message id from the nearest [data-message-id] ancestor.
              const resolveDiffTarget = (start) => {
                if (!start || !start.closest) return null;
                // Review panel row: file path from data-file.
                const fileItem = start.closest('[data-file]');
                if (fileItem) return { messageID: messageIdOf(fileItem), filePath: fileItem.getAttribute('data-file') || '' };
                // "Changed files" turn-summary row: no data-file, path lives in its spans.
                const turnRow = start.closest('[data-slot="session-turn-diff-trigger"]');
                if (turnRow) return { messageID: messageIdOf(turnRow), filePath: pathFrom(turnRow, '[data-slot="session-turn-diff-directory"]', '[data-slot="session-turn-diff-filename"]') };
                // Multi-file apply_patch ("Patch" tool): the specific file row.
                const patchRow = start.closest('[data-slot="apply-patch-trigger-content"]');
                if (patchRow) return { messageID: messageIdOf(patchRow), filePath: pathFrom(patchRow, '[data-slot="apply-patch-directory"]', '[data-slot="apply-patch-filename"]') };
                // Chat edit/write/single-file-patch block: the edited file (all reuse message-part-* spans).
                const editBlock = start.closest('[data-component="edit-tool"], [data-component="write-tool"], [data-component="apply-patch-tool"]');
                if (editBlock) return { messageID: messageIdOf(editBlock), filePath: pathFrom(editBlock, '[data-slot="message-part-directory"]', '[data-slot="message-part-title-filename"]') };
                // Any diff indicator (e.g. the summary total): the whole turn's diff, all files.
                const indicator = start.closest('[data-component="diff-changes"]');
                if (indicator) return { messageID: messageIdOf(indicator), filePath: '' };
                return null;
              };
              document.addEventListener('click', (event) => {
                if (!event.altKey || event.defaultPrevented) return;
                const target = resolveDiffTarget(event.target);
                if (!target) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                const messageID = target.messageID || '';
                const filePath = target.filePath || '';
                try {
                  $openDiffCallback;
                } catch (error) {
                  if (window.console && window.console.warn) {
                    window.console.warn('Failed to forward diff target to IntelliJ', error);
                  }
                }
              }, true);
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
}
