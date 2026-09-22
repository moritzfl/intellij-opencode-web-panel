package de.moritzf.opencodewebpanel.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeCompactHomeLayoutTest {
    @Test
    fun disabledCompactHomeLayoutInjectsNothing() {
        assertNull(OpenCodeBrowserSnippets.buildCompactHomeLayoutScript(enabled = false))
    }

    @Test
    fun compactHomeLayoutTargetsOnlyTheSessionSearchGrid() {
        val script = OpenCodeBrowserSnippets.buildCompactHomeLayoutScript(enabled = true)!!

        assertTrue(script.contains("div:has(> section [data-component=\"home-session-search\"]):not(:has(> aside))"))
        assertTrue(script.contains("max-width: none !important"))
        assertTrue(script.contains("grid-template-columns: minmax(0, 1fr) !important"))
        assertFalse(script.contains("lg:"))
        assertFalse(script.contains("Recent sessions"))
        assertFalse(script.contains("querySelectorAll"))
        assertFalse(script.contains("window.matchMedia ="))
    }

    @Test
    fun compactHomeLayoutKeepsOneStylesheetAcrossEarlyInjectionRetries() {
        val script = OpenCodeBrowserSnippets.buildCompactHomeLayoutScript(enabled = true)!!

        assertTrue(script.contains("if (window.__opencodeIntellijCompactHomeLayoutInstalled) return;"))
        assertTrue(script.contains("document.getElementById(STYLE_ID)"))
        assertTrue(script.contains("if (ensureQueued) return;"))
        assertTrue(script.contains("window.requestAnimationFrame"))
    }
}
