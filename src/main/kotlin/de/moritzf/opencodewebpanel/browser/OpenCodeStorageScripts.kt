package de.moritzf.opencodewebpanel.browser

import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserScriptSupport.PERSISTED_STORAGE_KEY_FILTER_JS
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserScriptSupport.escapeJavaScript
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import org.intellij.lang.annotations.Language

internal object OpenCodeStorageScripts {
    fun buildOpenProjectScript(
        projectBasePath: String?,
        serverUrl: String? = null,
    ): String? {
        if (projectBasePath.isNullOrBlank()) return null
        val directory = escapeJavaScript(projectBasePath)
        val originGuard =
            serverUrl
                ?.let(OpenCodeServerProtocol::buildOrigin)
                ?.let(::escapeJavaScript)
                ?.let {
                    @Language("JavaScript")
                    val guard = "if (window.location.origin !== '$it') return;"
                    guard
                }
                .orEmpty()
        @Language("JavaScript")
        val script =
            $$"""
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

    fun buildClearOpenCodeWebStateScript(): String {
        @Language("JavaScript")
        val script =
            """
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
        val script =
            """
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
        val script =
            """
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
}
