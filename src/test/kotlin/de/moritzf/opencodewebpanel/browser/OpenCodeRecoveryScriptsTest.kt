package de.moritzf.opencodewebpanel.browser

import de.moritzf.opencodewebpanel.server.OpenCodeWireProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builder safeguards and external contracts; DOM behavior lives in scripts/browser-contract.js. */
class OpenCodeRecoveryScriptsTest {
    @Test
    fun buildEventStreamWatchdogScriptIsMissingWhenDisabled() {
        for (protocol in OpenCodeWireProtocol.entries) {
            assertNull(
                OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(
                    enabled = false,
                    wireProtocol = protocol,
                )
            )
        }
    }

    @Test
    fun buildEventStreamWatchdogScriptDefersOnlyToTheNativeCliV2Watchdog() {
        for (protocol in OpenCodeWireProtocol.entries) {
            val script =
                OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(
                    enabled = true,
                    wireProtocol = protocol,
                )
            assertEquals(protocol == OpenCodeWireProtocol.V2_CLI, script == null)
        }
    }

    @Test
    fun buildEventStreamWatchdogScriptWatchesEveryEventRouteGeneration() {
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijEventWatchdogInstalled"))
        // All event route generations the SPA may pick, matched by pathname (not full URL) so
        // origin rewrites and directory/workspace query parameters cannot bypass the watchdog.
        assertTrue(script.contains("'/global/event'"))
        assertTrue(script.contains("'/event'"))
        assertTrue(script.contains("'/api/event'"))
        assertTrue(script.contains("new URL(requestUrl(input), location.href).pathname"))
        assertTrue(script.contains("typeof input.url === 'string'"))
        assertTrue(script.contains("typeof input.href === 'string'"))
        // Recovery works by aborting so OpenCode's own reconnect loop sees a stream error;
        // the plugin must never reimplement the stream or reload the page here.
        assertTrue(script.contains("controller.abort()"))
        assertFalse(script.contains("location.reload"))
    }

    @Test
    fun buildEventStreamWatchdogScriptDoesNotClaimInstallBeforeWrap() {
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!
        val installedAt = script.indexOf("window.__opencodeIntellijEventWatchdogInstalled = true")
        val fetchGuardAt = script.indexOf("typeof realFetch !== 'function'")
        assertTrue(installedAt > 0)
        assertTrue(fetchGuardAt > 0)
        assertTrue(fetchGuardAt < installedAt)
    }

    @Test
    fun buildEventStreamWatchdogScriptRewrapsRequestInputs() {
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!
        assertTrue(script.contains("input instanceof Request"))
        assertTrue(script.contains("new Request(input, options)"))
        assertTrue(script.contains("globalThis.fetch = watchedFetch"))
    }

    @Test
    fun buildEventStreamWatchdogScriptChainsTheCallerAbortSignal() {
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!

        // The SPA cancels its own stream on stop()/cleanup; dropping that signal would leak
        // the previous connection on every reconnect.
        assertTrue(script.contains("outer.addEventListener('abort'"))
        assertTrue(script.contains("outer.removeEventListener('abort'"))
        assertTrue(script.contains("if (outer.aborted) controller.abort();"))
    }

    @Test
    fun buildEventStreamWatchdogScriptTimesOutBeforeResponseHeaders() {
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!

        val armAt = script.indexOf("arm();")
        val fetchAt = script.indexOf("return realFetch.call")
        assertTrue(armAt > 0)
        assertTrue(fetchAt > armAt)
    }

    @Test
    fun buildEventStreamWatchdogScriptKeepsTimeoutAboveTheHeartbeatInterval() {
        // Heartbeats arrive every 10s; a timeout at or below that would reconnect endlessly on
        // a perfectly healthy stream.
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!
        assertTrue(
            script.contains(
                "const STALL_MS = ${OpenCodeBrowserSnippets.EVENT_STREAM_STALL_TIMEOUT_MILLIS};"
            )
        )

        val clamped =
            OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(
                enabled = true,
                stallTimeoutMillis = 1_000,
            )!!
        assertTrue(
            clamped.contains(
                "const STALL_MS = ${OpenCodeBrowserSnippets.MIN_EVENT_STREAM_STALL_TIMEOUT_MILLIS};"
            )
        )
    }

    @Test
    fun buildForceEventReconnectScriptIsANoOpWithoutTheWatchdog() {
        val script = OpenCodeBrowserSnippets.buildForceEventReconnectScript()

        // Fired unconditionally on resume, including when the safeguard is off and the hook
        // was never installed, so it must guard before calling.
        assertTrue(script.contains("typeof force === 'function'"))
        assertTrue(script.contains("window.__opencodeIntellijForceEventReconnect"))
    }

    @Test
    fun buildChunkLoadRecoveryScriptIsMissingWhenDisabled() {
        assertNull(
            OpenCodeBrowserSnippets.buildChunkLoadRecoveryScript(
                enabled = false,
                fatalCallback = "report();",
            )
        )
    }

    @Test
    fun buildChunkLoadRecoveryScriptIsMissingWithoutACallbackChannel() {
        // The JCEF callback channel can fail to be created (out-of-process CEF on Windows);
        // the feature then injects nothing instead of shipping a broken reporter into the page.
        assertNull(
            OpenCodeBrowserSnippets.buildChunkLoadRecoveryScript(
                enabled = true,
                fatalCallback = null,
            )
        )
    }

    @Test
    fun buildRendererHeartbeatScriptIsMissingWhenDisabled() {
        assertNull(
            OpenCodeBrowserSnippets.buildRendererHeartbeatScript(
                enabled = false,
                heartbeatCallback = "beat();",
            )
        )
    }

    @Test
    fun buildRendererHeartbeatScriptIsMissingWithoutACallbackChannel() {
        // Without a JCEF callback channel (macOS/Windows out-of-process JCEF can refuse its
        // creation) the heartbeat is pointless — a silence-only watchdog would recover
        // spuriously. Inject nothing then.
        assertNull(
            OpenCodeBrowserSnippets.buildRendererHeartbeatScript(
                enabled = true,
                heartbeatCallback = null,
            )
        )
    }

    @Test
    fun buildRendererHeartbeatScriptIsIdempotentAndReportsVisibility() {
        val script =
            OpenCodeBrowserSnippets.buildRendererHeartbeatScript(
                enabled = true,
                heartbeatCallback = "beat(visibility);",
            )!!

        assertTrue(script.contains("window.__opencodeIntellijRendererHeartbeatInstalled"))
        assertTrue(script.contains("document.visibilityState"))
        assertTrue(script.contains("setInterval"))
        assertTrue(script.contains("addEventListener('visibilitychange', beat)"))
        assertFalse(script.contains("requestAnimationFrame"))
        assertFalse(script.contains("if (!document.hidden)"))
    }

    @Test
    fun buildChunkLoadRecoveryScriptIsIdempotent() {
        val script =
            OpenCodeBrowserSnippets.buildChunkLoadRecoveryScript(
                enabled = true,
                fatalCallback = "report();",
            )!!

        assertTrue(script.contains("window.__opencodeIntellijChunkRecoveryInstalled"))
    }

    @Test
    fun buildChunkLoadRecoveryScriptCoversScriptSrcAndImportFailures() {
        val script =
            OpenCodeBrowserSnippets.buildChunkLoadRecoveryScript(
                enabled = true,
                fatalCallback = "report();",
            )!!

        // Module-script src failures surface as resource error events (event.target.src);
        // import() failures surface as the "dynamically imported module" message the boundary
        // shows.
        // Solid's error boundary often catches the rejected lazy() promise, so the same engine
        // text is scanned from the error-page details field (textarea/input value).
        // Hidden JCEF does not run requestAnimationFrame; scans use setTimeout instead.
        // Only readOnly fields are scanned: editable inputs can hold pasted engine text.
        assertTrue(script.contains("addEventListener('error'"))
        assertTrue(script.contains("addEventListener('unhandledrejection'"))
        assertTrue(script.contains("target.tagName === 'SCRIPT'"))
        assertTrue(script.contains("failed to fetch dynamically imported module"))
        assertTrue(script.contains("textarea, input, [data-slot=\"input-input\"]"))
        assertTrue(script.contains("isReadOnlyField"))
        assertTrue(script.contains("setTimeout"))
        assertTrue(script.contains("visibilitychange"))
        assertFalse(script.contains("requestAnimationFrame"))
        assertTrue(script.contains("assets"))
        assertTrue(script.contains("_?assets"))
        assertFalse(script.contains("location.reload"))
    }

    @Test
    fun buildChunkLoadRecoveryScriptSignalsAtMostOncePerPage() {
        val script =
            OpenCodeBrowserSnippets.buildChunkLoadRecoveryScript(
                enabled = true,
                fatalCallback = "report();",
            )!!

        // The boundary is terminal: after the first report the reload fixes the renderer, so a
        // second signal would only race another reload into the load the first one started.
        assertTrue(script.contains("let notified = false;"))
        assertTrue(script.contains("notified = true;"))
        assertTrue(script.contains("observer.disconnect()"))
    }

    @Test
    fun buildEventStreamWatchdogScriptExposesTheForceReconnectHook() {
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijForceEventReconnect = () =>"))
        // Finished streams must leave the tracking set or the hook would abort dead controllers
        // and leak them for the lifetime of the page.
        assertTrue(script.contains("active.delete(controller)"))
    }
}
