package de.moritzf.opencodewebpanel.browser

import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserScriptSupport.EVENT_STREAM_STALL_TIMEOUT_MILLIS
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserScriptSupport.MIN_EVENT_STREAM_STALL_TIMEOUT_MILLIS
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserScriptSupport.RENDERER_HEARTBEAT_INTERVAL_MILLIS
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserScriptSupport.VISIT_MATCHING_ELEMENTS_JS
import de.moritzf.opencodewebpanel.server.OpenCodeWireProtocol
import org.intellij.lang.annotations.Language

internal object OpenCodeRecoveryScripts {
    fun buildEventStreamWatchdogScript(
        enabled: Boolean,
        stallTimeoutMillis: Int = EVENT_STREAM_STALL_TIMEOUT_MILLIS,
        wireProtocol: OpenCodeWireProtocol = OpenCodeWireProtocol.UNKNOWN,
    ): String? {
        // CLI 2.x ships createClientConnection's byte-idle watchdog and foreground/network
        // resync. Keep fetch native there; 1.18 (including embedded-v2) still needs our patch.
        if (!enabled || wireProtocol == OpenCodeWireProtocol.V2_CLI) return null
        val timeout = stallTimeoutMillis.coerceAtLeast(MIN_EVENT_STREAM_STALL_TIMEOUT_MILLIS)
        @Language("JavaScript")
        val script =
            """
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

    fun buildForceEventReconnectScript(): String {
        @Language("JavaScript")
        val script =
            """
            (() => {
              const force = window.__opencodeIntellijForceEventReconnect;
              if (typeof force === 'function') force();
            })();
        """
        return script.trimIndent()
    }

    fun buildChunkLoadRecoveryScript(enabled: Boolean, fatalCallback: String?): String? {
        if (!enabled || fatalCallback == null) return null
        @Language("JavaScript")
        val script =
            """
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
              $VISIT_MATCHING_ELEMENTS_JS
              const errorFieldSelector = 'textarea, input, [data-slot="input-input"]';
              const scanErrorField = (el) => {
                if (notified || !el.isConnected || !isReadOnlyField(el)) return;
                const value = fieldText(el);
                if (isChunkFailure(value)) notify(value);
              };
              const pendingFields = new WeakSet();
              const queueScan = (el) => {
                if (notified || !el.isConnected || !isReadOnlyField(el) || pendingFields.has(el)) return;
                pendingFields.add(el);
                // value assignments do not emit mutations; retry just the newly rendered field.
                for (const delay of [0, 50, 250, 1000]) {
                  setTimeout(() => {
                    if (delay === 1000) pendingFields.delete(el);
                    scanErrorField(el);
                  }, delay);
                }
              };
              observer = new MutationObserver((mutations) => {
                for (const mutation of mutations) {
                  const target = mutation.target.nodeType === 1 ? mutation.target : mutation.target.parentElement;
                  const field = target && target.closest(errorFieldSelector);
                  if (field) queueScan(field);
                  mutation.addedNodes.forEach(node => visitMatchingElements(node, errorFieldSelector, queueScan));
                }
              });
              const root = document.documentElement || document;
              observer.observe(root, {
                childList: true, subtree: true, characterData: true,
                attributes: true, attributeFilter: ['readonly', 'disabled'],
              });
              document.addEventListener('visibilitychange', () => {
                if (!document.hidden) visitMatchingElements(document, errorFieldSelector, queueScan);
              });
              visitMatchingElements(document, errorFieldSelector, queueScan);
            })();
        """
        return script.trimIndent()
    }

    fun buildRendererHeartbeatScript(enabled: Boolean, heartbeatCallback: String?): String? {
        if (!enabled || heartbeatCallback == null) return null
        val intervalMillis = RENDERER_HEARTBEAT_INTERVAL_MILLIS
        @Language("JavaScript")
        val script =
            """
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
}
