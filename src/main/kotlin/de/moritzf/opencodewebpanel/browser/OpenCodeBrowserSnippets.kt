package de.moritzf.opencodewebpanel.browser

import org.intellij.lang.annotations.Language
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol

internal object OpenCodeBrowserSnippets {

    /**
     * How long the embedded page's event stream may stay byte-silent before it is treated as
     * dead. OpenCode emits `server.heartbeat` every 10 seconds, so this allows three to four
     * missed beats — the same budget the JVM-side reader uses for its read timeout.
     */
    const val EVENT_STREAM_STALL_TIMEOUT_MILLIS = 45_000

    /** Page-side heartbeat cadence; the JVM watchdog treats several missed beats as a stall. */
    const val RENDERER_HEARTBEAT_INTERVAL_MILLIS = 5_000

    /** OpenCode session-tab popover (`titlebar-tab-popover` `OPEN_DELAY`). */
    const val OPENCODE_TAB_POPOVER_OPEN_DELAY_MILLIS = 2_000

    /** Panel hover delay for that popover and the injected project-row path preview. */
    const val PATH_HOVER_PREVIEW_DELAY_MILLIS = 250

    /** Floor that keeps a misconfigured timeout from reconnect-looping through normal heartbeats. */
    const val MIN_EVENT_STREAM_STALL_TIMEOUT_MILLIS = 15_000

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
        const POINTER_STYLE_ID = 'opencode-intellij-pointer-cursor';
        const POINTER_CSS = '[' + POINTER_ATTR + '], [' + POINTER_ATTR + '] * { cursor: pointer !important; }';
        const ensurePointerCursorStyle = () => {
          const parent = document.head || document.documentElement;
          if (!parent) return;
          let style = document.getElementById(POINTER_STYLE_ID);
          if (!style) {
            style = document.createElement('style');
            style.id = POINTER_STYLE_ID;
          }
          if (style.textContent !== POINTER_CSS) style.textContent = POINTER_CSS;
          if (!style.isConnected) parent.appendChild(style);
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

    /**
     * OpenCode localStorage keys mirrored into the IDE-side settings store.
     *
     * Isolated serve processes (one per IDE project) do not share sessions, so
     * only user settings/prefs are restored: `settings.v3`, theme, language, and
     * model favorites. Tabs, layout, home.servers, workspace, and notification
     * lists stay with the live origin.
     */
    @Language("JavaScript")
    private val PERSISTED_STORAGE_KEY_FILTER_JS = $$"""
        const exactKeys = new Set([
          '$${OpenCodeServerProtocol.OPEN_CODE_THEME_ID_STORAGE_KEY}',
          '$${OpenCodeServerProtocol.OPEN_CODE_COLOR_SCHEME_STORAGE_KEY}',
          'opencode-theme-css-light',
          'opencode-theme-css-dark',
          'settings.v3',
        ]);
        const globalKeys = /^opencode\.global\.dat:(language|model)$/;
        const shouldPersistKey = (key) => typeof key === 'string' && (exactKeys.has(key) || globalKeys.test(key));
        // Settings values should not embed server origins; keep the rewrite so a
        // leftover loopback URL in an older snapshot cannot pin a dead port.
        const LOOPBACK_ORIGIN_RE = /^https?:\/\/(?:127\.0\.0\.1|localhost)(?::\d+)?$/i;
        const LOOPBACK_ORIGIN_IN_TEXT_RE = /https?:\/\/(?:127\.0\.0\.1|localhost)(?::\d+)?/gi;
        const encodeServerKey = (value) => {
          const bytes = new TextEncoder().encode(value);
          let binary = '';
          bytes.forEach((byte) => binary += String.fromCharCode(byte));
          return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
        };
        const decodeServerKey = (value) => {
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
        const rewriteLoopbackServerRefs = (text) => {
          if (typeof text !== 'string' || !text) return text;
          const origin = window.location.origin;
          if (!LOOPBACK_ORIGIN_RE.test(origin)) return text;
          let next = text.replace(LOOPBACK_ORIGIN_IN_TEXT_RE, origin);
          const currentKey = encodeServerKey(origin);
          next = next.replace(/[A-Za-z0-9_-]{16,64}/g, (token) => {
            const decoded = decodeServerKey(token);
            if (!decoded || !LOOPBACK_ORIGIN_RE.test(decoded)) return token;
            return currentKey;
          });
          return next;
        };
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

    /**
     * Seeds the opencode SPA's project state for [projectBasePath].
     *
     * Inject from `onLoadStart` so `lastProject` is set before the SPA bundle reads
     * localStorage. Session choice is left to OpenCode (tabs / lastProjectSession).
     *
     * Auto-port loopback origins are reused across IDE projects. OpenCode 2 persists
     * session tabs by origin (`opencode.window.browser.dat:tabs`), so a previous
     * occupant's `ses_` ids reopen as "This session cannot be found". Drop those
     * tabs when this origin's project worktree changes.
     */
    fun buildOpenProjectScript(
        projectBasePath: String?,
        serverUrl: String? = null,
    ): String? {
        if (projectBasePath.isNullOrBlank()) return null
        val directory = escapeJavaScript(projectBasePath)
        val originGuard = serverUrl?.let(OpenCodeServerProtocol::buildOrigin)
            ?.let(::escapeJavaScript)
            ?.let {
                @Language("JavaScript")
                val guard = "if (window.location.origin !== '$it') return;"
                guard
            }
            .orEmpty()
        @Language("JavaScript")
        val script = $$"""
            (() => {
              const directory = '$${directory}';
              const scope = 'local';
              $$originGuard
              const sameWorktree = (left, right) => {
                if (typeof left !== 'string' || typeof right !== 'string') return false;
                const norm = (value) => {
                  let next = value.replace(/\\/g, '/').replace(/\/+$/g, '');
                  if (/^[A-Za-z]:\//.test(next) || next.startsWith('//')) next = next.toLowerCase();
                  return next;
                };
                return norm(left) === norm(right);
              };
              const markerKey = 'opencode-intellij-project';
              try {
                const previous = window.localStorage.getItem(markerKey);
                if (!previous || !sameWorktree(previous, directory)) {
                  const staleKeys = [
                    'opencode.window.browser.dat:tabs',
                    'opencode.window.browser.dat:tabs.recent',
                    'opencode.window.browser.dat:tabs.info',
                    'opencode.window.browser.dat:tabs.closed',
                    'opencode.window.browser.dat:tabs.panes',
                  ];
                  for (const key of staleKeys) window.localStorage.removeItem(key);
                }
                if (previous !== directory) window.localStorage.setItem(markerKey, directory);
              } catch (_) {}
              try {
                const storageKey = 'opencode.global.dat:server';
                const raw = window.localStorage.getItem(storageKey);
                let parsed = {};
                let parseFailed = false;
                try { parsed = raw ? JSON.parse(raw) : {}; } catch (_) { parseFailed = true; }
                const isPlainObject = !!parsed && typeof parsed === 'object' && !Array.isArray(parsed);
                if (!parseFailed && parsed !== null && !isPlainObject) {
                  if (window.console && window.console.warn) {
                    window.console.warn('Skipping OpenCode project seed: unrecognized project-state schema');
                  }
                } else {
                  const state = isPlainObject ? parsed : {};
                  state.projects = state.projects && typeof state.projects === 'object' && !Array.isArray(state.projects)
                    ? state.projects : {};
                  state.lastProject = state.lastProject && typeof state.lastProject === 'object' && !Array.isArray(state.lastProject)
                    ? state.lastProject : {};
                  const projects = Array.isArray(state.projects[scope]) ? state.projects[scope] : [];
                  let found = false;
                  const nextProjects = [];
                  for (const project of projects) {
                    if (!project || !sameWorktree(project.worktree, directory)) {
                      nextProjects.push(project);
                      continue;
                    }
                    if (found) continue;
                    found = true;
                    nextProjects.push(Object.assign({}, project, {
                      worktree: directory,
                      expanded: typeof project.expanded === 'boolean' ? project.expanded : true,
                    }));
                  }
                  if (!found) nextProjects.unshift({ worktree: directory, expanded: true });
                  state.projects[scope] = nextProjects;
                  state.lastProject[scope] = directory;
                  const nextRaw = JSON.stringify(state);
                  if (nextRaw !== raw) window.localStorage.setItem(storageKey, nextRaw);
                }
              } catch (error) {
                if (window.console && window.console.warn) {
                  window.console.warn('Failed to seed OpenCode project state', error);
                }
              }
            })();
        """
        return script.trimIndent()
    }

    /**
     * User-invoked escape hatch (not an injection feature): wipes the page's localStorage and
     * sessionStorage so a bad persisted value — a corrupt seeded project state or a mirrored
     * snapshot that keeps getting restored — can be cleared without digging into the JCEF
     * profile. The caller clears the IDE-side snapshot and reloads the page afterwards.
     */
    fun buildClearOpenCodeWebStateScript(): String {
        @Language("JavaScript")
        val script = """
            (() => {
              try { window.localStorage.clear(); } catch (_) {}
              try { window.sessionStorage.clear(); } catch (_) {}
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
                    window.localStorage.setItem(key, rewriteLoopbackServerRefs(value));
                  }
                }
                // Port-auto relaunches (and localhost↔127.0.0.1 flips) leave stale server keys
                // in already-present entries; rewrite those in place too.
                const existingKeys = [];
                for (let index = 0; index < window.localStorage.length; index += 1) {
                  const key = window.localStorage.key(index);
                  if (shouldPersistKey(key)) existingKeys.push(key);
                }
                for (const key of existingKeys) {
                  const current = window.localStorage.getItem(key);
                  if (typeof current !== 'string') continue;
                  const rewritten = rewriteLoopbackServerRefs(current);
                  if (rewritten !== current) window.localStorage.setItem(key, rewritten);
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
                  if (typeof value === 'string' && value.length <= MAX_VALUE_CHARS) {
                    entries[key] = rewriteLoopbackServerRefs(value);
                  }
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
              // The original method always runs first and its result is always returned; the
              // mirror tail is additionally try-caught so no bug in it can ever escalate into
              // breaking the SPA's own storage operations (this is the only page-wide API patch).
              Storage.prototype.setItem = function(key, value) {
                const result = originalSetItem.apply(this, arguments);
                try {
                  if (this === window.localStorage && shouldPersistKey(String(key))) queueSend();
                } catch (_) {}
                return result;
              };
              Storage.prototype.removeItem = function(key) {
                const result = originalRemoveItem.apply(this, arguments);
                try {
                  if (this === window.localStorage && shouldPersistKey(String(key))) queueSend();
                } catch (_) {}
                return result;
              };
              Storage.prototype.clear = function() {
                const result = originalClear.apply(this, arguments);
                try {
                  if (this === window.localStorage) queueSend();
                } catch (_) {}
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

    /** Dispatches a remapped IntelliJ action through OpenCode's current page-local command map. */
    fun buildShortcutDispatchScript(newLayoutKeybinds: List<String>, classicKeybinds: List<String>): String? {
        if (newLayoutKeybinds.isEmpty() && classicKeybinds.isEmpty()) return null
        val newLayout = newLayoutKeybinds.joinToString(", ", prefix = "[", postfix = "]") {
            "'${escapeJavaScript(it)}'"
        }
        val classic = classicKeybinds.joinToString(", ", prefix = "[", postfix = "]") {
            "'${escapeJavaScript(it)}'"
        }
        val configs = if (newLayoutKeybinds == classicKeybinds) {
            newLayout
        } else {
            "document.querySelector('[data-slot=\"titlebar-v2\"]') ? $newLayout : $classic"
        }
        @Language("JavaScript")
        val script = $$"""
            (() => {
              const configs = $${configs};
              const isMac = /(Mac|iPod|iPhone|iPad)/.test(navigator.platform);
              const namedKeys = {
                comma: ',', plus: '+', space: ' ', escape: 'Escape', esc: 'Escape',
                enter: 'Enter', return: 'Enter', tab: 'Tab', backspace: 'Backspace',
                delete: 'Delete', insert: 'Insert', home: 'Home', end: 'End',
                pageup: 'PageUp', pagedown: 'PageDown', arrowup: 'ArrowUp',
                arrowdown: 'ArrowDown', arrowleft: 'ArrowLeft', arrowright: 'ArrowRight'
              };
              const codeFor = (key) => {
                if (/^[a-z]$/.test(key)) return 'Key' + key.toUpperCase();
                if (/^[0-9]$/.test(key)) return 'Digit' + key;
                if (/^F(?:[1-9]|1[0-9]|2[0-4])$/.test(key)) return key;
                return ({
                  "'": 'Quote', '.': 'Period', ',': 'Comma', ';': 'Semicolon',
                  '/': 'Slash', '\\': 'Backslash', '-': 'Minus', '=': 'Equal',
                  '+': 'NumpadAdd', ' ': 'Space', Escape: 'Escape'
                })[key] || key;
              };
              const parse = (chord) => {
                const binding = { key: '', ctrlKey: false, metaKey: false, altKey: false, shiftKey: false };
                for (const part of chord.trim().toLowerCase().split('+').filter(Boolean)) {
                  if (part === 'mod') {
                    if (isMac) binding.metaKey = true;
                    else binding.ctrlKey = true;
                  } else if (part === 'meta' || part === 'cmd' || part === 'command') {
                    binding.metaKey = true;
                  } else if (part === 'ctrl' || part === 'control') {
                    binding.ctrlKey = true;
                  } else if (part === 'alt' || part === 'option') {
                    binding.altKey = true;
                  } else if (part === 'shift') {
                    binding.shiftKey = true;
                  } else if (!binding.key) {
                    binding.key = namedKeys[part] || (/^f[0-9]+$/.test(part) ? part.toUpperCase() : part);
                  } else {
                    return null;
                  }
                }
                if (!binding.key) return null;
                return { ...binding, code: codeFor(binding.key) };
              };
              const target = document.activeElement instanceof Element ? document.activeElement : document;
              for (const config of configs) {
                for (const chord of config.split(',')) {
                  const binding = parse(chord);
                  if (!binding) continue;
                  const event = new KeyboardEvent('keydown', {
                    ...binding,
                    bubbles: true,
                    cancelable: true,
                    composed: true
                  });
                  target.dispatchEvent(event);
                  if (event.defaultPrevented) return;
                }
              }
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
        val script = $$"""
            (() => {
              if (window.__opencodeIntellijCodeNavInstalled) return;
              window.__opencodeIntellijCodeNavInstalled = true;
              const hasExtension = /\.[a-zA-Z][a-zA-Z0-9]{0,8}(?::L?\d+(?:-L?\d+)?|:\d+:\d+|#L?\d+(?:-L?\d+)?|\(L?\d+(?:\s*,\s*\d+)?\))?$/i;
              const hasPathLocator = /[\\/].*(?::L?\d+(?:-L?\d+)?|:\d+:\d+|#L?\d+(?:-L?\d+)?|\(L?\d+(?:\s*,\s*\d+)?\))$/i;
              const fileLocWithDir = /(?:[A-Za-z]:)?(?:[^\s<>"'`()]+[\/\\])+[^\s\/\\():]+\.[A-Za-z][A-Za-z0-9]{0,8}(?::L?\d+(?:-L?\d+)?|:\d+:\d+|#L?\d+(?:-L?\d+)?|\(L?\d+(?:\s*,\s*\d+)?\))?/i;
              const fileLocBare = /[^\s\/\\():]+\.[A-Za-z][A-Za-z0-9]{0,8}(?::L?\d+(?:-L?\d+)?|:\d+:\d+|#L?\d+(?:-L?\d+)?|\(L?\d+(?:\s*,\s*\d+)?\))/i;
              const locatorAtStart = /^\s*(:L?\d+(?:-L?\d+)?|:\d+:\d+|#L?\d+(?:-L?\d+)?|\(L?\d+(?:\s*,\s*\d+)?\))/i;
              const isUrl = /^[a-z][a-z0-9+.-]*:\/\//i;
              const isPascalCase = /^[A-Z][a-zA-Z0-9_]*$/;
              const isQualifiedClass = /^(?:[a-zA-Z_][a-zA-Z0-9_]*\.)+[A-Z][a-zA-Z0-9_]*$/;
              const isTypeMember = /^(?:[a-zA-Z_][a-zA-Z0-9_]*\.)*[A-Z][a-zA-Z0-9_]*(?:[.#][a-z_][a-zA-Z0-9_]*)?\(.*\)$/;
              const isTypeMemberBare = /^(?:[a-zA-Z_][a-zA-Z0-9_]*\.)*[A-Z][a-zA-Z0-9_]*[.#][a-z_][a-zA-Z0-9_]*$/;
              const fileExt = /\.(kt|kts|java|ts|tsx|js|jsx|mjs|cjs|py|rb|go|rs|c|h|cc|cpp|hpp|cs|swift|md|json|yml|yaml|xml|toml|txt)$/i;
              const isSnakeCase = /^[a-z][a-z0-9]*_[a-z0-9_]+$/;
              const looksLikeCodeRef = (text) => {
                const t = text.trim();
                if (t.length < 2 || t.length > 512) return false;
                if (t.includes('\n')) return false;
                if (isUrl.test(t)) return false;
                if (isTypeMember.test(t)) return true;
                if (isTypeMemberBare.test(t) && !fileExt.test(t)) return true;
                if (hasExtension.test(t)) return true;
                if (t.includes(' ')) return false;
                if (hasPathLocator.test(t)) return true;
                if (isPascalCase.test(t)) return true;
                if (isQualifiedClass.test(t)) return true;
                if (isSnakeCase.test(t)) return true;
                return false;
              };
              const adjacentLocator = (el) => {
                let node = el.nextSibling;
                while (node && node.nodeType === Node.TEXT_NODE && !/\S/.test(node.textContent || '')) {
                  node = node.nextSibling;
                }
                if (!node || node.nodeType !== Node.TEXT_NODE) return '';
                const match = locatorAtStart.exec(node.textContent || '');
                return match ? match[1] : '';
              };
              const withAdjacentLocator = (text, el) => {
                if (!text) return '';
                const loc = adjacentLocator(el);
                return loc ? text + loc : text;
              };
              const extractRef = (codeEl) => {
                if (!codeEl.closest('[data-component="markdown"]')) return '';
                if (codeEl.closest('pre') || codeEl.closest('a')) return '';
                const text = (codeEl.textContent || '').trim();
                const kind = codeEl.getAttribute('data-inline-code-kind');
                if (kind === 'url') return '';
                if (kind === 'path') {
                  return text.length > 0 && text.length <= 512 ? withAdjacentLocator(text, codeEl) : '';
                }
                if (!looksLikeCodeRef(text)) return '';
                const parent = codeEl.parentElement;
                if (!parent) return withAdjacentLocator(text, codeEl);
                const path = parent.getAttribute('data-path') || parent.getAttribute('data-file');
                if (path) return withAdjacentLocator(path, codeEl);
                return withAdjacentLocator(text, codeEl);
              };
              const inOutput = (el) => !!(el && el.closest && el.closest('pre, [data-slot="bash-pre"], [data-component="tool-output"], [data-component="tool-loaded-file"]'));
              const skipEmptyText = (node) => {
                let current = node;
                while (current && current.nodeType === Node.TEXT_NODE && !/\S/.test(current.textContent || '')) {
                  current = current.previousSibling;
                }
                return current;
              };
              const codeBesideLocator = (event) => {
                try {
                  const caret = document.caretRangeFromPoint
                    ? document.caretRangeFromPoint(event.clientX, event.clientY)
                    : null;
                  const node = caret && caret.startContainer;
                  if (!node || node.nodeType !== Node.TEXT_NODE) return null;
                  const prev = skipEmptyText(node.previousSibling);
                  if (!prev || prev.nodeName !== 'CODE') return null;
                  const loc = adjacentLocator(prev);
                  if (!loc || !extractRef(prev)) return null;
                  const offset = Math.min(caret.startOffset, (node.textContent || '').length);
                  const lead = (/^\s*/.exec(node.textContent || '') || [''])[0].length;
                  if (offset < lead || offset > lead + loc.length + 1) return null;
                  return prev;
                } catch (_) {
                  return null;
                }
              };
              const fileLocAtEvent = (event) => {
                const selection = window.getSelection && window.getSelection();
                if (selection && !selection.isCollapsed) return '';
                const caret = document.caretRangeFromPoint
                  ? document.caretRangeFromPoint(event.clientX, event.clientY)
                  : null;
                const node = caret && caret.startContainer;
                if (!node || node.nodeType !== Node.TEXT_NODE) return '';
                const parent = node.parentElement;
                if (!parent || parent.closest('a, [contenteditable="true"]')) return '';
                const output = inOutput(parent);
                if (!output && !parent.closest('[data-component="markdown"]')) return '';
                if (!output && parent.closest('code')) return '';
                const text = node.textContent || '';
                const offset = Math.min(caret.startOffset, text.length);
                const py = /File\s+"([^"\n]+)",\s+line\s+(\d+)/ig;
                let python;
                while ((python = py.exec(text))) {
                  if (offset >= python.index && offset <= python.index + python[0].length) {
                    const path = python[1];
                    if (path && !isUrl.test(path)) return path + ':' + python[2];
                  }
                }
                const isBreak = (ch) => /[\s<>"'`]/.test(ch);
                let start = offset;
                let end = offset;
                while (start > 0 && !isBreak(text[start - 1])) start -= 1;
                while (end < text.length && !isBreak(text[end])) end += 1;
                const token = text.slice(start, end).replace(/:+$/, '');
                if (isUrl.test(token)) return '';
                const glueAfter = () => {
                  const rest = text.slice(end).replace(/^[\s<>"'`]+/, '');
                  const glued = locatorAtStart.exec(rest);
                  return glued ? token + glued[1] : token;
                };
                const glueBefore = () => {
                  let prevEnd = start;
                  while (prevEnd > 0 && isBreak(text[prevEnd - 1])) prevEnd -= 1;
                  let prevStart = prevEnd;
                  while (prevStart > 0 && !isBreak(text[prevStart - 1])) prevStart -= 1;
                  return text.slice(prevStart, prevEnd).replace(/:+$/, '') + token;
                };
                const pick = (value) => {
                  const cleaned = (value || '').replace(/:+$/, '');
                  return (fileLocWithDir.exec(cleaned) || fileLocBare.exec(cleaned) || [])[0] || '';
                };
                const candidate = pick(token) ? token : glueAfter();
                return pick(candidate) || pick(glueBefore());
              };
              document.addEventListener('click', (event) => {
                if (event.defaultPrevented) return;
                const code = (event.target && event.target.closest && event.target.closest('code')) || codeBesideLocator(event);
                const ref = (!inOutput(event.target) && code && extractRef(code)) || fileLocAtEvent(event);
                if (!ref) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                $${openCodeCallback};
              }, true);
              $$POINTER_CURSOR_KIT_JS
              document.addEventListener('mouseover', (event) => {
                const code = (event.target && event.target.closest && event.target.closest('code')) || codeBesideLocator(event);
                markHovered(code && extractRef(code) ? code : null);
              }, true);
            })();
        """
        return script.trimIndent()
    }

    /**
     * Forces OpenCode's compact (mobile) layout by stubbing the breakpoint media queries the SPA
     * uses for layout. No CSS class overrides: layout is driven by `createMediaQuery` on
     * `(min-width: 768px)` / `(max-width: 767px)`, so patching those is enough and stays free of
     * Tailwind class names that change with redesigns.
     *
     * Must run before the SPA bundle initializes media queries (`onLoadStart`).
     */
    fun buildCompactLayoutScript(enabled: Boolean): String? {
        return buildMatchMediaPatchScript(compact = enabled, theme = false, dark = false)
    }

    fun buildMatchMediaPatchScript(compact: Boolean, theme: Boolean, dark: Boolean): String? {
        if (!compact && !theme) return null
        val compactLiteral = compact.toString()
        val themeLiteral = theme.toString()
        val darkLiteral = dark.toString()
        @Language("JavaScript")
        val script = """
            (() => {
              const compact = $compactLiteral;
              const theme = $themeLiteral;
              const dark = $darkLiteral;
              const WIDE_KEY = '(min-width:768px)';
              const NARROW_KEY = '(max-width:767px)';
              const THEME_KEY = '(prefers-color-scheme:dark)';
              const THEME_MEDIA = '(prefers-color-scheme: dark)';
              const keyOf = (q) => String(q || '').replace(/\s+/g, '').toLowerCase();
              if (window.__opencodeIntellijMatchMediaInstalled && theme && window.__opencodeIntellijThemeMql) {
                if (window.__opencodeIntellijThemeDark !== dark) {
                  window.__opencodeIntellijThemeDark = dark;
                  window.__opencodeIntellijThemeMql.matches = dark;
                  window.__opencodeIntellijThemeMql.dispatchEvent(new MediaQueryListEvent('change', { matches: dark, media: THEME_MEDIA }));
                }
                return;
              }
              window.__opencodeIntellijMatchMediaInstalled = true;
              window.__opencodeIntellijCompactInstalled = compact;
              window.__opencodeIntellijThemeInstalled = theme;
              const orig = window.__opencodeIntellijOrigMatchMedia || window.matchMedia.bind(window);
              window.__opencodeIntellijOrigMatchMedia = orig;
              const stub = (media, matches) => ({ matches, media, onchange: null, addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false });
              let themeMql = null;
              if (theme) {
                window.__opencodeIntellijThemeDark = dark;
                themeMql = {
                  matches: dark,
                  media: THEME_MEDIA,
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
                window.__opencodeIntellijThemeMql = themeMql;
              }
              window.matchMedia = (q) => {
                const key = keyOf(q);
                if (compact && key === WIDE_KEY) return stub(q, false);
                if (compact && key === NARROW_KEY) return stub(q, true);
                if (theme && key === THEME_KEY) return themeMql;
                return orig(q);
              };
            })();
        """
        return script.trimIndent()
    }

    /**
     * Gives the SPA's `/global/event` reader the stall detection it does not have, so a socket
     * the OS severed silently (laptop sleep, VPN/adapter change — common on Windows, where a
     * half-open TCP connection is not reset) cannot leave the page permanently deaf.
     *
     * OpenCode's stream loop (`packages/app/src/context/server-sdk.tsx`) reconnects only when the
     * response iterator *ends or throws*; it has no read timeout and its only resume hook is
     * `pageshow` with `event.persisted`, which never fires for a live JCEF page. A half-open
     * socket therefore delivers neither bytes nor an error and `for await` blocks forever: the
     * page keeps its last state, never learns about `permission.replied` (so an IDE-answered
     * permission prompt stays on screen) and cannot start a new turn until a manual reload.
     *
     * The fix stays outside SPA internals: `window.fetch` is wrapped so the event-stream response
     * body is piped through a reader that aborts the request after [stallTimeoutMillis] without a
     * single byte. The abort surfaces as a normal stream error, which is exactly the signal
     * OpenCode's own reconnect loop already handles. The server emits `server.heartbeat` every
     * 10s, so silence well past that is unambiguous evidence of a dead transport.
     *
     * Must run before the SPA bundle captures `window.fetch` (`onLoadStart`).
     */
    fun buildEventStreamWatchdogScript(enabled: Boolean, stallTimeoutMillis: Int = EVENT_STREAM_STALL_TIMEOUT_MILLIS): String? {
        if (!enabled) return null
        val timeout = stallTimeoutMillis.coerceAtLeast(MIN_EVENT_STREAM_STALL_TIMEOUT_MILLIS)
        @Language("JavaScript")
        val script = """
            (() => {
              if (window.__opencodeIntellijEventWatchdogInstalled) return;
              const STALL_MS = $timeout;
              // Every generation of the event route the SPA may use. Matching on pathname keeps
              // this independent of origin rewrites and query parameters (directory, workspace).
              const EVENT_PATHS = ['/global/event', '/event', '/api/event'];
              const realFetch = window.fetch;
              if (typeof realFetch !== 'function' || typeof AbortController !== 'function') return;
              window.__opencodeIntellijEventWatchdogInstalled = true;
              // Live stream controllers, so the IDE can cut a stream the moment it knows the
              // transport is gone (resume from suspend) instead of waiting out the timeout.
              const active = new Set();
              window.__opencodeIntellijForceEventReconnect = () => {
                const current = Array.from(active);
                active.clear();
                current.forEach((controller) => { try { controller.abort(); } catch (_) {} });
                return current.length;
              };
              const requestUrl = (input) => {
                if (typeof input === 'string') return input;
                if (input && typeof input.url === 'string') return input.url;
                if (input && typeof input.href === 'string') return input.href;
                return '';
              };
              const isEventStream = (input) => {
                try { return EVENT_PATHS.indexOf(new URL(requestUrl(input), location.href).pathname) !== -1; }
                catch (_) { return false; }
              };
              const watchedFetch = function (input, init) {
                if (!isEventStream(input)) return realFetch.apply(this, arguments);
                // Chain the caller's signal into ours so the SPA can still cancel normally.
                const controller = new AbortController();
                const outer = (init && init.signal) || (input && input.signal);
                let detachOuter = () => {};
                if (outer) {
                  if (outer.aborted) controller.abort();
                  else {
                    const abortFromOuter = () => controller.abort();
                    outer.addEventListener('abort', abortFromOuter, { once: true });
                    detachOuter = () => outer.removeEventListener('abort', abortFromOuter);
                  }
                }
                const options = Object.assign({}, init, { signal: controller.signal });
                let fetchInput = input;
                let fetchInit = options;
                let timer;
                const done = () => {
                  clearTimeout(timer);
                  detachOuter();
                  active.delete(controller);
                };
                const arm = () => {
                  clearTimeout(timer);
                  timer = setTimeout(() => {
                    // Aborting the request makes fetch/the body reader throw, which is the error
                    // OpenCode's reconnect loop waits for. It reopens the stream itself.
                    try { controller.abort(); } catch (_) {}
                  }, STALL_MS);
                };
                try {
                  if (typeof Request === 'function' && input instanceof Request) {
                    fetchInput = new Request(input, options);
                    fetchInit = undefined;
                  }
                  active.add(controller);
                  // Cover a connection that stalls before response headers as well as a body
                  // that stops delivering heartbeat bytes after the response has started.
                  arm();
                } catch (error) {
                  done();
                  throw error;
                }
                return realFetch.call(this, fetchInput, fetchInit).then((response) => {
                  if (!response.body) { done(); return response; }
                  const reader = response.body.getReader();
                  const watched = new ReadableStream({
                    start(target) {
                      arm();
                      const pump = () => reader.read().then((result) => {
                        if (result.done) { done(); target.close(); return; }
                        arm();
                        target.enqueue(result.value);
                        return pump();
                      }).catch((error) => { done(); target.error(error); });
                      pump();
                    },
                    cancel(reason) { done(); return reader.cancel(reason); },
                  });
                  return new Response(watched, {
                    status: response.status,
                    statusText: response.statusText,
                    headers: response.headers,
                  });
                }).catch((error) => { done(); throw error; });
              };
              window.fetch = watchedFetch;
              if (typeof globalThis === 'object' && globalThis.fetch === realFetch) {
                globalThis.fetch = watchedFetch;
              }
            })();
        """
        return script.trimIndent()
    }

    /**
     * Drops the page's event stream immediately so OpenCode's reconnect loop reopens it. Used
     * when the IDE already knows the transport cannot have survived (resume from system
     * suspend), instead of waiting out the watchdog's silence budget.
     *
     * No-op when the watchdog is not installed, so it is safe to fire unconditionally.
     */
    fun buildForceEventReconnectScript(): String {
        @Language("JavaScript")
        val script = """
            (() => {
              const force = window.__opencodeIntellijForceEventReconnect;
              if (typeof force === 'function') force();
            })();
        """
        return script.trimIndent()
    }

    fun isInPlaceDialogRepaintEvent(type: String): Boolean {
        return type == "permission.asked" || type == "permission.replied" ||
            type == "question.asked" || type == "question.replied" || type == "question.rejected"
    }

    /**
     * Forces Chromium to re-raster the viewport after an in-page layout change without
     * resizing the Swing host. A 1px host bounds change reallocates the OSR surface and
     * flashes on Windows. Toggle a 1px CSS translate on `documentElement` (no OpenCode
     * selectors) after two animation frames, then fire `resize` so the SPA relayouts.
     */
    fun buildViewportRasterNudgeScript(): String {
        @Language("JavaScript")
        val script = """
            (() => {
              const nudge = () => {
                const root = document.documentElement;
                if (!root) return;
                const style = root.style;
                const previous = style.getPropertyValue('transform');
                style.setProperty('transform', 'translate(1px, 0)');
                void root.offsetHeight;
                if (previous) style.setProperty('transform', previous);
                else style.removeProperty('transform');
                window.dispatchEvent(new Event('resize'));
              };
              requestAnimationFrame(() => requestAnimationFrame(nudge));
            })();
        """
        return script.trimIndent()
    }

    /**
     * Hides OpenCode's floating "open the website" control (help / marketing link out to
     * opencode.ai). Inside the embedded IDE panel it only overlaps the composer.
     *
     * Selectors prefer durable signals (`href` to opencode.ai + icon-button / fixed chrome) over
     * English aria labels and Tailwind position utilities. Style is kept alive with a permanent
     * MutationObserver because the SPA can replace `<head>` after early injection.
     */
    fun buildHideWebsiteButtonScript(enabled: Boolean): String? {
        if (!enabled) return null
        @Language("JavaScript")
        val script = """
            (() => {
              if (window.__opencodeIntellijHideWebsiteButtonInstalled) return;
              window.__opencodeIntellijHideWebsiteButtonInstalled = true;
              const STYLE_ID = 'opencode-intellij-hide-website-button';
              // Prefer href + durable chrome role over locale labels and Tailwind layout classes.
              const SELECTOR = [
                'a[href^="https://opencode.ai"][data-component*="icon-button"]',
                'a[href^="http://opencode.ai"][data-component*="icon-button"]',
              ].join(', ');
              const CSS = SELECTOR + ' { display: none !important; visibility: hidden !important; pointer-events: none !important; }';
              const ensureStyle = () => {
                const parent = document.head || document.documentElement;
                if (!parent) return false;
                let style = document.getElementById(STYLE_ID);
                if (!style) {
                  style = document.createElement('style');
                  style.id = STYLE_ID;
                  style.textContent = CSS;
                }
                if (!style.isConnected) parent.appendChild(style);
                else if (style.textContent !== CSS) style.textContent = CSS;
                return true;
              };
              // The stylesheet alone does the hiding; the observer only re-attaches it if the SPA
              // replaces <head>. Reattachment checks are debounced to one per animation frame so
              // chat streaming (which mutates the DOM constantly) costs nothing measurable.
              ensureStyle();
              let ensureQueued = false;
              const queueEnsureStyle = () => {
                if (ensureQueued) return;
                ensureQueued = true;
                window.requestAnimationFrame(() => {
                  ensureQueued = false;
                  ensureStyle();
                });
              };
              const observer = new MutationObserver(queueEnsureStyle);
              const root = document.documentElement || document;
              observer.observe(root, { childList: true, subtree: true });
              document.addEventListener('DOMContentLoaded', ensureStyle, { once: true });
            })();
        """
        return script.trimIndent()
    }

    /**
     * Shortens OpenCode's session-tab path popover (Kobalte `openDelay` 2000ms) to
     * [PATH_HOVER_PREVIEW_DELAY_MILLIS], and adds the same styled preview on home project rows
     * so duplicate display names still show distinct worktrees.
     *
     * Tab delay: Kobalte schedules `window.setTimeout(..., 2000)` when the pointer enters
     * `[data-component="session-tab-popover-trigger"]`. The clamp applies only to two-argument
     * 2000ms timers scheduled within a short window of such a pointerenter, so unrelated page
     * timers with the same 2000ms delay (copy-state reset, typewriter cursor) are untouched;
      * the skip-window path uses 0 and is left alone. Home rows (`home-project-row` on 1.18,
      * `home-session-row` on CLI 2.x) do not put the worktree in the DOM. 1.18 maps row order
      * onto `opencode.global.dat:server` `projects` and skips when counts do not match. CLI 2.x
      * session rows expose a project-name span; overlay uses it only when that basename uniquely
      * matches a stored worktree. The overlay reuses OpenCode's `session-tab-popover` slots so
      * it picks up the page CSS. Must be removable by reload (safeguard); the builder returns
      * null when disabled.
     */
    fun buildPathHoverPreviewScript(enabled: Boolean): String? {
        if (!enabled) return null
        val tabDelay = OPENCODE_TAB_POPOVER_OPEN_DELAY_MILLIS
        val previewDelay = PATH_HOVER_PREVIEW_DELAY_MILLIS
        @Language("JavaScript")
        val script = """
            (() => {
              if (window.__opencodeIntellijPathHoverPreviewInstalled) return;
              window.__opencodeIntellijPathHoverPreviewInstalled = true;
              const TAB_DELAY = $tabDelay;
              const PREVIEW_DELAY = $previewDelay;
              const TAB_TRIGGER = '[data-component="session-tab-popover-trigger"]';
              const PROJECT_ROW = '[data-component="home-project-row"], [data-component="home-session-row"]';
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
                const rows = document.querySelectorAll(PROJECT_ROW);
                const trees = worktreesMatchingRowCount(rows.length);
                if (!trees.length) return '';
                const index = Array.prototype.indexOf.call(rows, row);
                if (index < 0 || index >= trees.length) return '';
                return trees[index];
              };
              let overlay = null;
              const hidePopover = () => {
                if (!overlay) return;
                if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
                overlay = null;
              };
              const showPopover = (anchor, title, path) => {
                hidePopover();
                if (!path) return;
                const pop = document.createElement('div');
                pop.setAttribute('data-component', 'session-tab-popover');
                pop.setAttribute('data-opencode-intellij-path-preview', '');
                const themeRoot = anchor.closest('[data-theme]') || document.documentElement;
                const theme = themeRoot && themeRoot.getAttribute && themeRoot.getAttribute('data-theme');
                if (theme) pop.setAttribute('data-theme', theme);
                pop.style.position = 'fixed';
                pop.style.zIndex = '50';
                pop.style.pointerEvents = 'none';
                const header = document.createElement('div');
                header.setAttribute('data-slot', 'header');
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
                pop.style.left = left + 'px';
                pop.style.top = top + 'px';
              };
              const projectRowFrom = (node) => {
                if (!node || !node.closest) return null;
                return node.closest(PROJECT_ROW);
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
              const scheduleHover = (row) => {
                if (hoverRow === row) return;
                cancelHover();
                hoverRow = row;
                hoverTimer = nativeSetTimeout(() => {
                  hoverTimer = 0;
                  if (hoverRow !== row) return;
                  showPopover(row, projectNameFromRow(row), prettyPath(pathForProjectRow(row)));
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

    fun buildIdeThemeSyncScript(enabled: Boolean, dark: Boolean): String? {
        return buildMatchMediaPatchScript(compact = false, theme = enabled, dark = dark)
    }

    /**
     * Signals the IDE that the page raised a failed lazy-chunk import — the error OpenCode's
     * own error boundary presents as "Failed to fetch dynamically imported module". CEF only
     * reports main-frame failures to the JVM, so a chunk that times out (e.g. the panel was on a
     * dead origin after a server restart, or a hung first-run Windows server stalls delivery)
     * leaves the SPA stuck behind its error boundary until a manual reload. Chromium also caches
     * the failed import per renderer, so retrying the import keeps failing; only a full reload
     * recovers it.
     *
     * The listener only signals; the JVM side decides whether and when to reload. It must run
     * before the SPA bundle (`onLoadStart`/document-start), because a boot chunk can already be
     * the failing one. Errors are delivered through capture-phase `error` (module-script src)
     * and `unhandledrejection` (uncaught `import()`). Solid's error boundary often *catches*
     * the rejected lazy() promise (route chunks such as `new-session-*.js`), so those events
     * never fire — the same TypeError is then copied into the error-page details field (engine
     * text, not a localized label). Scan that field with `setTimeout` retries: `requestAnimationFrame`
     * does not run in a hidden JCEF tool window, and Kobalte may assign `textarea.value` after
     * the first frame. Only readOnly fields count as the error page: editable inputs can hold
     * the same pasted engine text, and reloading a healthy session out from under the user is
     * worse than missing the scan. Every page gets at most one signal.
     */
    fun buildChunkLoadRecoveryScript(enabled: Boolean, fatalCallback: String?): String? {
        if (!enabled || fatalCallback == null) return null
        @Language("JavaScript")
        val script = """
            (() => {
              if (window.__opencodeIntellijChunkRecoveryInstalled) return;
              window.__opencodeIntellijChunkRecoveryInstalled = true;
              let notified = false;
              const textOf = (reason) => {
                if (typeof reason === 'string') return reason;
                if (reason && typeof reason.message === 'string') return reason.message;
                try { return String(reason ?? ''); } catch (_) { return ''; }
              };
              const isAssetUrl = (value) => /\/_?assets\/[\w.-]+\.js(?:$|[?#])/i.test(value) ||
                /\/_?assets\/[\w.-]+\.js:\d+/i.test(value);
              const isChunkFailure = (text) =>
                /failed to fetch dynamically imported module/i.test(text) ||
                (/failed to fetch/i.test(text) && isAssetUrl(text));
              let observer = null;
              const notify = (message) => {
                if (notified) return;
                notified = true;
                try { if (observer) observer.disconnect(); } catch (_) {}
                try { $fatalCallback; } catch (_) {}
              };
              window.addEventListener('error', (event) => {
                if (notified) return;
                const target = event.target;
                if (target && target !== window && target.tagName === 'SCRIPT' &&
                    typeof target.src === 'string' && isAssetUrl(target.src)) {
                  notify('chunk load failed: ' + target.src);
                  return;
                }
                if (isChunkFailure(textOf(event.message))) notify(textOf(event.message));
              }, true);
              window.addEventListener('unhandledrejection', (event) => {
                if (notified) return;
                const text = textOf(event.reason);
                if (isChunkFailure(text)) notify(text);
              }, true);
              const fieldText = (el) => {
                if (!el) return '';
                if (typeof el.value === 'string' && el.value) return el.value;
                if (typeof el.textContent === 'string') return el.textContent;
                return '';
              };
              // Solid's error boundary renders the engine text into a readOnly details field.
              // Editable inputs (settings forms, the chat composer's textareas) can legitimately
              // contain the same words when a user types or pastes them — a reload out of a healthy
              // session. Only readOnly fields can be the error page.
              const isReadOnlyField = (el) => !!(el && (el.readOnly === true || el.hasAttribute('readonly') || el.disabled === true));
              const scanErrorPage = () => {
                if (notified) return;
                const fields = document.querySelectorAll('textarea, input, [data-slot="input-input"]');
                for (let i = 0; i < fields.length; i += 1) {
                  const el = fields[i];
                  if (!isReadOnlyField(el)) continue;
                  const value = fieldText(el);
                  if (isChunkFailure(value)) {
                    notify(value);
                    return;
                  }
                }
              };
              let scanQueued = false;
              const queueScan = () => {
                if (notified || scanQueued) return;
                scanQueued = true;
                setTimeout(scanErrorPage, 0);
                setTimeout(scanErrorPage, 50);
                setTimeout(() => {
                  scanQueued = false;
                  scanErrorPage();
                }, 250);
                setTimeout(scanErrorPage, 1000);
              };
              observer = new MutationObserver(queueScan);
              const root = document.documentElement || document;
              observer.observe(root, { childList: true, subtree: true });
              document.addEventListener('visibilitychange', () => {
                if (!document.hidden) scanErrorPage();
              });
              queueScan();
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
        val script = $$"""
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
                  $${cursorCallback};
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
                if (tag === 'INPUT' && !/^(button|submit|reset|checkbox|radio|range|file|color|image)$/i.test(el.type)) return 'text';
                // Over selectable text, auto renders as the text I-beam. Point-to-caret APIs
                // snap to the nearest text, so require the point to be inside its element.
                if (document.caretPositionFromPoint || document.caretRangeFromPoint) {
                  const caret = document.caretPositionFromPoint
                    ? document.caretPositionFromPoint(x, y)
                    : document.caretRangeFromPoint(x, y);
                  const node = caret && (caret.offsetNode || caret.startContainer);
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
              document.addEventListener('mouseout', (event) => {
                if (!event.relatedTarget) send('default');
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
              // Structural match, no locale-dependent label text: the permission/question toast is
              // the only toast rendered with the "checklist" (permission) or "bubble-5" (question)
              // sprite icon, and it always carries an action row (go-to-session/dismiss buttons).
              // The Icon component renders the sprite name in the DOM as use[href="#opencode-icon-…"],
              // in both the legacy toast and the redesigned toast-v2.
              const toastSelector = '[data-component="toast"], [data-component="toast-v2"]';
              const iconSlotSelector = '[data-slot="toast-icon"], [data-slot="toast-v2-icon"]';
              // Cover both icon sprites: toasts currently use the v1 sprite even inside v2 toast
              // chrome; if icons migrate to the v2 sprite the same names keep matching.
              const promptIconSelector = [
                'use[href="#opencode-icon-checklist"]',
                'use[href="#opencode-icon-bubble-5"]',
                'use[href="#opencode-v2-icon-checklist"]',
                'use[href="#opencode-v2-icon-bubble-5"]',
              ].join(', ');
              const actionSelector = '[data-slot="toast-action"], [data-slot="toast-v2-actions"] button';
              const closeSelector = '[data-slot="toast-close-button"], [data-slot="toast-v2-close-button"]';
              // Bound auto-dismissals per page load: this feature acts on the user's behalf, so a
              // matcher gone wrong after an OpenCode redesign must not silently eat an unbounded
              // stream of toasts. Legit use dismisses a handful per load; hitting the cap disables
              // suppression for this page load and logs once.
              const MAX_DISMISSALS = 20;
              let dismissals = 0;
              let observer = null;
              const dismissIfProjectSwitchPrompt = (toast) => {
                const iconSlot = toast.querySelector(iconSlotSelector);
                if (!iconSlot || !iconSlot.querySelector(promptIconSelector)) return;
                if (!toast.querySelector(actionSelector)) return;
                if (dismissals >= MAX_DISMISSALS) {
                  if (observer) {
                    observer.disconnect();
                    observer = null;
                    if (window.console && window.console.warn) {
                      window.console.warn('OpenCode toast suppression cap reached; disabled until the next page load');
                    }
                  }
                  return;
                }
                dismissals += 1;
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
                observer = new MutationObserver((mutations) => {
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
        batchId: String? = null,
        resultCallback: String? = null,
        focusPrompt: Boolean = false,
    ): String? {
        val textEntries = textPlain.filter { it.isNotBlank() }
        if (!enabled || (files.isEmpty() && textEntries.isEmpty())) return null
        val fileEntries = files.joinToString(",\n") { file ->
            @Language("JavaScript")
            val entry = "{ name: '${escapeJavaScript(file.name)}', mime: '${escapeJavaScript(file.mime)}', lastModified: ${file.lastModified}, base64: '${escapeJavaScript(file.base64)}' }"
            entry
        }
        val textDrops = textEntries.joinToString("\n") { text ->
            val isStandaloneFileReference = text.startsWith("file:") && !text.contains('\n') && !text.contains('\r')
            val drop = if (isStandaloneFileReference) {
                "results.push(dispatchDrop((transfer) => transfer.setData('text/plain', '${escapeJavaScript(text)}')));"
            } else {
                "results.push(dispatchPaste('${escapeJavaScript(text)}'));"
            }
            drop
        }
        val fileDrop = if (files.isNotEmpty()) {
            @Language("JavaScript")
            val drop = """
                results.push(dispatchDrop((transfer) => {
                  const entries = [
                    $fileEntries
                  ];
                  for (const entry of entries) {
                    transfer.items.add(new File([decode(entry.base64)], entry.name, {
                      type: entry.mime,
                      lastModified: entry.lastModified,
                    }));
                  }
                }));
            """
            drop.trimIndent()
        } else {
            ""
        }
        val escapedBatchId = escapeJavaScript(batchId.orEmpty())
        val focusPromptLiteral = if (focusPrompt) "true" else "false"
        val reportResult = resultCallback?.let { callback ->
            @Language("JavaScript")
            val report = """
                try {
                  $callback;
                } catch (error) {
                  if (window.console && window.console.warn) {
                    window.console.warn('Failed to report OpenCode drop result to IntelliJ', error);
                  }
                }
            """
            report.trimIndent()
        }.orEmpty()
        @Language("JavaScript")
        val script = """
            (() => {
              const batchId = '$escapedBatchId';
              const focusPrompt = $focusPromptLiteral;
              const report = (accepted) => {
                $reportResult
              };
              if (typeof DataTransfer !== 'function' || typeof File !== 'function' ||
                  typeof DragEvent !== 'function' || typeof ClipboardEvent !== 'function') {
                console.warn('OpenCode Web Panel could not dispatch dropped files: browser drag APIs unavailable');
                report(false);
                return;
              }
              const target = Array.from(document.querySelectorAll(
                '[data-component="prompt-input"][contenteditable="true"], [data-component="composer-editor"][contenteditable="true"], [data-slot="composer-editor"][contenteditable="true"]',
              ))
                .find((element) => {
                  const style = window.getComputedStyle(element);
                  return element.isConnected && style.display !== 'none' && style.visibility !== 'hidden';
                });
              if (!target) {
                report(false);
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
                const transfer = new DataTransfer();
                fill(transfer);
                const options = { bubbles: true, cancelable: true, dataTransfer: transfer };
                target.dispatchEvent(new DragEvent('dragover', options));
                const event = new DragEvent('drop', options);
                target.dispatchEvent(event);
                if (focusPrompt) {
                  requestAnimationFrame(() => { if (document.contains(target)) target.focus(); });
                }
                return event.defaultPrevented;
              };
              const dispatchPaste = (text) => {
                const transfer = new DataTransfer();
                transfer.setData('text/plain', text);
                const event = new ClipboardEvent('paste', {
                  bubbles: true,
                  cancelable: true,
                  clipboardData: transfer,
                });
                target.dispatchEvent(event);
                if (focusPrompt) {
                  requestAnimationFrame(() => { if (document.contains(target)) target.focus(); });
                }
                return event.defaultPrevented;
              };
              const results = [];
              $textDrops
              $fileDrop
              const accepted = results.length > 0 && results.every(Boolean);
              report(accepted);
            })();
        """
        return script.trimIndent()
    }

    /**
     * Renderer liveness heartbeat for the JVM-side [de.moritzf.opencodewebpanel.toolWindow.OpenCodeRendererWatchdog].
     * A dead renderer never schedules the timer and never delivers the callback, so staleness is
     * the reliable signal — no pinging back into the page. Reports the page's
     * `visibilityState` on a 5s timer and on every `visibilitychange` (including hide) so the
     * JVM can pause the stall clock. Do not beat from `requestAnimationFrame` — that floods the
     * JCEF IPC channel at display refresh.
     */
    fun buildRendererHeartbeatScript(enabled: Boolean, heartbeatCallback: String?): String? {
        if (!enabled || heartbeatCallback == null) return null
        val intervalMillis = RENDERER_HEARTBEAT_INTERVAL_MILLIS
        @Language("JavaScript")
        val script = """
            (() => {
              if (window.__opencodeIntellijRendererHeartbeatInstalled) return;
              window.__opencodeIntellijRendererHeartbeatInstalled = true;
              const beat = () => {
                try {
                  const visibility = document.visibilityState || '';
                  $heartbeatCallback;
                } catch (_) {}
              };
              window.setInterval(beat, $intervalMillis);
              document.addEventListener('visibilitychange', beat);
              beat();
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
                    try {
                      $callback;
                      return;
                    } catch (error) {
                      if (window.console && window.console.warn) {
                        window.console.warn('Failed to forward file link to IntelliJ', error);
                      }
                    }
                    $openFileFallback;
                """
                action.trimIndent()
            }
            ?: openFileFallback

        @Language("JavaScript")
        val script = $$"""
            (() => {
              if (window.__opencodeIntellijFileLinksInstalled) return;
              window.__opencodeIntellijFileLinksInstalled = true;
              const directory = '$${directory}';
              const explicitProtocol = /^[a-zA-Z][a-zA-Z0-9+.-]*:/;
              const supportedFileProtocol = /^(file|sandbox):/i;
              const absoluteFilePath = /^(\/|\\\\|[A-Za-z]:[\\/])/;
              $$DECODE_ROUTE_DIRECTORY_JS
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
              // SPA routes must never be treated as local files. The 1.18 layout uses
              // /server/<key>/session/<id> (e.g. task/subagent cards); the legacy layout uses a
              // base64url-encoded project directory as the first segment. Bare project roots and
              // "/" are also SPA destinations (home / project switch), not filesystem paths.
              const isOpenCodeAppRoute = (value) => {
                const path = openCodeRoutePath(value);
                if (!path) return false;
                if (path === '/' || path === '') return true;
                if (/^\/server(?:\/|[/?#]|$)/.test(path)) return true;
                if (/^\/new-session(?:\/|[/?#]|$)/.test(path)) return true;
                const match = /^\/([^/?#]+)(?:\/session(?:[/?#]|$)|[/?#]|$)/.exec(path);
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
              // Durable slot only — no locale-specific aria/title labels.
              const changedFileButtonSelector = '[data-slot="session-review-view-button"]';
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
                return directory ? directory.replace(/[\\/]?$/, '/') + fileName : fileName;
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
                return directory ? directory.replace(/[\\/]?$/, '/') + fileName : fileName;
              };
              const lastSegmentLooksLikeFile = (value) => {
                const path = String(value || '').split('?')[0].split('#')[0].replace(/[\\/]+$/, '');
                const last = (path.split(/[\\/]/).filter(Boolean).pop() || '').replace(/:\\d+(?::\\d+)?$/, '');
                return /\\.[a-zA-Z0-9]{1,8}$/.test(last);
              };
              const isLocalFileLink = (href) => {
                if (!href || href.startsWith('#')) return false;
                if (isOpenCodeAppRoute(href)) return false;
                if (/^(\\\\|[A-Za-z]:[\\/])/.test(href)) return true;
                if (href.startsWith('/') && !href.startsWith('//')) return lastSegmentLooksLikeFile(href);
                if (explicitProtocol.test(href)) return supportedFileProtocol.test(href);
                if (href.startsWith('./') || href.startsWith('../')) return true;
                return !href.startsWith('//') && !href.includes('://');
              };
              const openFileInIde = (rawHref, partID) => {
                const now = Date.now();
                if (rawHref === lastOpenedHref && now - lastOpenedAt < 750) return;
                lastOpenedHref = rawHref;
                lastOpenedAt = now;
                partID = partID || '';
                $${openFileAction};
              };
              const toolOpenIconSelector = '[data-slot="opencode-intellij-open-file"]';
              const toolOpenRootSelector = '[data-component="edit-trigger"], [data-component="write-trigger"], [data-component="edit-tool"], [data-component="write-tool"], [data-slot="apply-patch-trigger-content"], [data-slot="session-turn-diff-trigger"]';
              const closestElement = (node, selector) => {
                let el = node;
                while (el && el.nodeType !== 1) el = el.parentNode;
                while (el) {
                  if (el.matches && el.matches(selector)) return el;
                  if (typeof el.closest === 'function') return el.closest(selector);
                  el = el.parentElement;
                }
                return null;
              };
              const pathFromToolRoot = (root) => {
                if (!root || !root.querySelector) return '';
                const fileName = cleanDisplayedPath(root.querySelector('[data-slot="message-part-title-filename"], [data-slot="apply-patch-filename"], [data-slot="session-turn-diff-filename"]')?.textContent);
                if (!fileName) return '';
                const directory = cleanDisplayedPath(root.querySelector('[data-slot="message-part-directory"], [data-slot="apply-patch-directory"], [data-slot="session-turn-diff-directory"]')?.textContent);
                return directory ? directory.replace(/[\\/]?$/, '/') + fileName : fileName;
              };
              const toolOpenIconLink = (target) => {
                const icon = closestElement(target, toolOpenIconSelector);
                if (!icon) return '';
                const stored = icon.getAttribute('data-href');
                if (stored) return stored;
                const root = closestElement(icon, toolOpenRootSelector);
                return pathFromToolRoot(root);
              };
              const filesBrowserRow = (node) => {
                const row = closestElement(node, '[data-slot="file-tree-v2-row"]');
                if (!row) return null;
                const path = row.getAttribute('data-path') || '';
                if (!path) return null;
                const sidebar = row.closest('[data-slot="session-review-v2-sidebar"]');
                if (!sidebar || sidebar.querySelector('[data-component="select-v2"]')) return null;
                return row;
              };
              const resolveFileOpenTarget = (target, changedButtonOnly) => {
                const iconHref = toolOpenIconLink(target);
                if (iconHref) {
                  return { element: closestElement(target, toolOpenIconSelector), href: iconHref };
                }
                if (!changedButtonOnly) {
                  const filesRow = filesBrowserRow(target);
                  if (filesRow) return { element: filesRow, href: filesRow.getAttribute('data-path') || '' };
                }
                const changedFileHref = changedFileButtonLink(target);
                const reviewV2Href = changedFileHref ? '' : reviewV2FileLink(target);
                if (changedButtonOnly && !changedFileHref && !reviewV2Href) return null;
                const link = !changedFileHref && !reviewV2Href && target && target.closest ? target.closest('a') : null;
                if (link && (!link.closest('[data-component="markdown"]') || link.target !== '_blank')) return null;
                const rawHref = changedFileHref || reviewV2Href || (link ? (link.getAttribute('href') || inferredFileLink(link)) : '');
                if (!isLocalFileLink(rawHref)) return null;
                const element = changedFileHref
                  ? target.closest(changedFileButtonSelector)
                  : (reviewV2Href ? target.closest(reviewV2FileButtonSelector) : link);
                return { element: element, href: rawHref };
              };
              const partIdOf = (node) => {
                const el = node && node.closest ? node.closest('[data-timeline-part-id]') : null;
                return el ? (el.getAttribute('data-timeline-part-id') || '') : '';
              };
              const insertToolOpenIcons = () => {
                const roots = document.querySelectorAll(toolOpenRootSelector);
                for (const root of roots) {
                  const href = pathFromToolRoot(root);
                  if (!href) continue;
                  const actions = root.querySelector('[data-slot="message-part-actions"], [data-slot="apply-patch-trigger-actions"], [data-slot="session-turn-diff-meta"]');
                  if (!actions) continue;
                  const partID = partIdOf(root);
                  const existing = root.querySelector(toolOpenIconSelector);
                  if (existing) {
                    existing.setAttribute('data-href', href);
                    existing.setAttribute('data-part-id', partID);
                    continue;
                  }
                  const icon = document.createElement('span');
                  icon.setAttribute('data-slot', 'opencode-intellij-open-file');
                  icon.setAttribute('data-href', href);
                  icon.setAttribute('data-part-id', partID);
                  icon.setAttribute('role', 'button');
                  icon.setAttribute('tabindex', '0');
                  icon.style.cssText = 'display:inline-flex;align-items:center;flex:0 0 auto;width:14px;height:14px;margin-inline-end:6px;color:inherit;opacity:0.72;';
                  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
                  svg.setAttribute('viewBox', '0 0 20 20');
                  svg.setAttribute('width', '14');
                  svg.setAttribute('height', '14');
                  svg.setAttribute('aria-hidden', 'true');
                  svg.style.pointerEvents = 'none';
                  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                  path.setAttribute('d', 'M11.7 4.6H15.4V8.3M15.2 4.8L10 10M4.6 6.2V15.4H13.8V11.2');
                  path.setAttribute('fill', 'none');
                  path.setAttribute('stroke', 'currentColor');
                  path.setAttribute('stroke-linecap', 'square');
                  svg.appendChild(path);
                  icon.appendChild(svg);
                  actions.insertBefore(icon, actions.firstChild);
                }
              };
              let insertQueued = false;
              const queueInsertToolOpenIcons = () => {
                if (insertQueued) return;
                insertQueued = true;
                queueMicrotask(() => {
                  insertQueued = false;
                  insertToolOpenIcons();
                });
              };
              new MutationObserver(queueInsertToolOpenIcons).observe(document.documentElement, { childList: true, subtree: true });
              queueInsertToolOpenIcons();
              const handleFileOpenEvent = (event, changedButtonOnly) => {
                const icon = closestElement(event.target, toolOpenIconSelector);
                if (icon) {
                  event.preventDefault();
                  event.stopPropagation();
                  event.stopImmediatePropagation();
                  if (event.type !== 'mousedown') {
                    const iconHref = toolOpenIconLink(icon);
                    if (iconHref) openFileInIde(iconHref, icon.getAttribute('data-part-id') || partIdOf(icon));
                  }
                  return;
                }
                if (event.defaultPrevented) return;
                // Alt and Ctrl/Cmd+Click are reserved for the IDE diff gesture
                // (buildDiffNavigationScript), except Files-tab tree rows (open the file).
                if (event.altKey || (/Mac|iPhone|iPod|iPad/.test(navigator.platform) ? event.metaKey : event.ctrlKey)) {
                  if (!filesBrowserRow(event.target)) return;
                }
                const resolved = resolveFileOpenTarget(event.target, changedButtonOnly);
                if (!resolved) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                openFileInIde(resolved.href);
              };
              window.addEventListener('pointerdown', (event) => handleFileOpenEvent(event, true), true);
              window.addEventListener('mousedown', (event) => handleFileOpenEvent(event, true), true);
              window.addEventListener('click', (event) => handleFileOpenEvent(event, false), true);
              $$POINTER_CURSOR_KIT_JS
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
     * Installs a Ctrl/Cmd+Click (and Alt+Click) handler that opens the IDE diff viewer for a
     * diff target in the OpenCode page. Chat edit/write/patch blocks send the tool `[data-timeline-part-id]`
     * (`prt_…`) so the JVM can load that part's `filediff`/`files`; multi-file patch rows also
     * send the reconstructed relative path to pick the row. Review/turn-summary rows and the
     * whole-turn indicator still send the user `messageID` (+ optional file path) for
     * `session.diff`. CLI 2.x Changes Git/Branch adds a fourth `vcsMode` line (`working`/`branch`).
     * Forwards `messageID + "\n" + filePath + "\n" + partID + "\n" + vcsMode` (each may be empty)
     * to the JVM via [openDiffCallback]. Returns null when disabled or without a callback.
     */
    fun buildDiffNavigationScript(enabled: Boolean, openDiffCallback: String? = null): String? {
        if (!enabled || openDiffCallback == null) return null

        @Language("JavaScript")
        val script = $$"""
            (() => {
              if (window.__opencodeIntellijDiffNavInstalled) return;
              window.__opencodeIntellijDiffNavInstalled = true;
              const clean = (value) => (value || '').replace(/[\u202A-\u202E]/g, '').trim();
              const messageIdOf = (node) => {
                const el = node && node.closest ? node.closest('[data-message-id]') : null;
                return el ? (el.getAttribute('data-message-id') || '') : '';
              };
              const partIdOf = (node) => {
                const el = node && node.closest ? node.closest('[data-timeline-part-id]') : null;
                return el ? (el.getAttribute('data-timeline-part-id') || '') : '';
              };
              const pathFrom = (root, dirSel, nameSel) => {
                if (!root || !root.querySelector) return '';
                const dir = clean(root.querySelector(dirSel)?.textContent).replace(/\\/g, '/');
                const name = clean(root.querySelector(nameSel)?.textContent).replace(/\\/g, '/');
                if (!name) return '';
                return dir ? dir.replace(/[\\/]?$/, '/') + name : name;
              };
              const isMac = /Mac|iPhone|iPod|iPad/.test(navigator.platform);
              const isDiffGesture = (event) => event.altKey || (isMac ? event.metaKey : event.ctrlKey);
              const REVIEW_MODE_ATTR = 'data-opencode-intellij-review-mode';
              const syncReviewMode = () => {
                const items = document.querySelectorAll('[data-slot="select-v2-listbox"] [data-key]');
                let selectedKey = '';
                items.forEach((el) => {
                  if (el.hasAttribute('data-selected') || el.getAttribute('aria-selected') === 'true') {
                    selectedKey = el.getAttribute('data-key') || '';
                  }
                });
                if (selectedKey !== 'git' && selectedKey !== 'branch' && selectedKey !== 'turn') return;
                document.querySelectorAll('[data-slot="session-review-v2-sidebar-header"] [data-component="select-v2"]').forEach((el) => {
                  el.setAttribute(REVIEW_MODE_ATTR, selectedKey);
                });
              };
              document.addEventListener('click', syncReviewMode, true);
              new MutationObserver(syncReviewMode).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['data-selected', 'aria-selected'] });
              const changesSidebarOf = (node) => {
                if (!node || !node.closest) return null;
                const sidebar = node.closest('[data-slot="session-review-v2-sidebar"]');
                if (!sidebar || !sidebar.querySelector('[data-component="select-v2"]')) return null;
                return sidebar;
              };
              const filesBrowserRow = (node) => {
                if (!node || !node.closest) return null;
                const row = node.closest('[data-slot="file-tree-v2-row"]');
                if (!row) return null;
                const path = row.getAttribute('data-path') || '';
                if (!path) return null;
                const sidebar = row.closest('[data-slot="session-review-v2-sidebar"]');
                if (!sidebar || sidebar.querySelector('[data-component="select-v2"]')) return null;
                return row;
              };
              const reviewModeOf = (sidebar) => {
                const trigger = sidebar.querySelector('[data-component="select-v2"]');
                const key = trigger ? (trigger.getAttribute(REVIEW_MODE_ATTR) || trigger.getAttribute('data-key') || '') : '';
                if (key === 'git' || key === 'branch' || key === 'turn') return key;
                return 'git';
              };
              const reviewFilePath = (start) => {
                const row = start.closest('[data-slot="file-tree-v2-row"]');
                if (row) return row.getAttribute('data-path') || '';
                const header = start.closest('[data-slot="session-review-v2-file-title"], [data-slot="session-review-v2-file-name"], [data-slot="session-review-v2-file-path"]');
                if (!header) return '';
                const root = (header.closest && header.closest('[data-slot="session-review-v2-file-header"]')) || header;
                const name = clean(root.querySelector ? root.querySelector('[data-slot="session-review-v2-file-name"]')?.textContent : '');
                const dir = clean(root.querySelector ? root.querySelector('[data-slot="session-review-v2-file-path"]')?.textContent : '');
                if (!name) return '';
                return dir ? dir.replace(/[\\\\/]?$/, '/') + name : name;
              };
              // Review/turn-summary/indicator: session.diff is keyed by the turn's *user*
              // message id. Chat edit/write/patch: the tool part id (prt_…) is the stable key;
              // there is no GET-by-part, so the JVM pages session.messages to find it.
              // CLI 2.x Changes tab: Git/Branch → /api/vcs/diff; Last turn → session.diff.
              // Files tab file-tree rows are not diffs — file-link opens them.
              const resolveDiffTarget = (start) => {
                if (!start || !start.closest) return null;
                if (start.closest('[data-slot="opencode-intellij-open-file"]')) return null;
                if (filesBrowserRow(start)) return null;
                const changesSidebar = changesSidebarOf(start);
                if (changesSidebar) {
                  const path = reviewFilePath(start);
                  if (!path) return null;
                  const mode = reviewModeOf(changesSidebar);
                  if (mode === 'git') return { messageID: '', filePath: path, partID: '', vcsMode: 'working' };
                  if (mode === 'branch') return { messageID: '', filePath: path, partID: '', vcsMode: 'branch' };
                  return { messageID: '', filePath: path, partID: '', vcsMode: '' };
                }
                const fileItem = start.closest('[data-file]');
                if (fileItem) return { messageID: messageIdOf(fileItem), filePath: fileItem.getAttribute('data-file') || '', partID: '' };
                const turnRow = start.closest('[data-slot="session-turn-diff-trigger"]');
                if (turnRow) return { messageID: messageIdOf(turnRow), filePath: pathFrom(turnRow, '[data-slot="session-turn-diff-directory"]', '[data-slot="session-turn-diff-filename"]'), partID: '' };
                const patchRow = start.closest('[data-slot="apply-patch-trigger-content"]');
                if (patchRow) return { messageID: messageIdOf(patchRow), filePath: pathFrom(patchRow, '[data-slot="apply-patch-directory"]', '[data-slot="apply-patch-filename"]'), partID: partIdOf(patchRow) };
                const editBlock = start.closest('[data-component="edit-tool"], [data-component="write-tool"], [data-component="apply-patch-tool"]');
                if (editBlock) return { messageID: messageIdOf(editBlock), filePath: '', partID: partIdOf(editBlock) };
                const indicator = start.closest('[data-component="diff-changes"]');
                if (indicator) return { messageID: messageIdOf(indicator), filePath: '', partID: '' };
                const turn = start.closest('[data-component="user-message"], [data-component="text-part"]');
                if (turn) {
                  const messageID = messageIdOf(turn);
                  if (messageID) return { messageID: messageID, filePath: '', partID: '' };
                }
                return null;
              };
              document.addEventListener('click', (event) => {
                if (event.defaultPrevented || !isDiffGesture(event)) return;
                const target = resolveDiffTarget(event.target);
                if (!target) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                const messageID = target.messageID || '';
                const filePath = target.filePath || '';
                const partID = target.partID || '';
                const vcsMode = target.vcsMode || '';
                try {
                  $${openDiffCallback};
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
