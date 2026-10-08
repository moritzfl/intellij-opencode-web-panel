package de.moritzf.opencodewebpanel.browser

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builder safeguards and external contracts; DOM behavior lives in scripts/browser-contract.js. */
class OpenCodePathHoverScriptsTest {
    @Test
    fun buildPathHoverPreviewScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildPathHoverPreviewScript(enabled = false))
    }

    @Test
    fun buildPathHoverPreviewScriptShortensTabDelayAndOverlaysProjectRows() {
        val script = OpenCodeBrowserSnippets.buildPathHoverPreviewScript(enabled = true)!!

        // DOM markers/callbacks are contracts; real-page behavior is exercised by the browser gate.
        assertTrue(script.contains("session-tab-popover-trigger"))
        assertTrue(script.contains("mobile-tabs-trigger"))
        assertTrue(script.contains("home-session-row"))
        assertTrue(script.contains("home-session-search"))
        assertTrue(script.contains("data-session-id"))
    }
}
