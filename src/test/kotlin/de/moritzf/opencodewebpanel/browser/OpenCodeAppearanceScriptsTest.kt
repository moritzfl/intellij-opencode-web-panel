package de.moritzf.opencodewebpanel.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builder safeguards and external contracts; DOM behavior lives in scripts/browser-contract.js. */
class OpenCodeAppearanceScriptsTest {
    @Test
    fun buildMatchMediaPatchScriptIsMissingWhenBothDisabled() {
        assertNull(
            OpenCodeBrowserSnippets.buildMatchMediaPatchScript(
                compact = false,
                theme = false,
                dark = true,
            )
        )
    }

    @Test
    fun buildMatchMediaPatchScriptCombinesCompactAndThemeInOneWrapper() {
        val script =
            OpenCodeBrowserSnippets.buildMatchMediaPatchScript(
                compact = true,
                theme = true,
                dark = true,
            )!!

        assertEquals(1, Regex("window\\.matchMedia = ").findAll(script).count())
        assertTrue(script.contains("(min-width:768px)"))
        assertTrue(script.contains("THEME_KEY"))
        assertTrue(script.contains("window.__opencodeIntellijOrigMatchMedia"))
    }

    @Test
    fun buildCompactLayoutScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildCompactLayoutScript(enabled = false))
    }

    @Test
    fun buildCompactLayoutScriptPatchesMatchMediaOnly() {
        val script = OpenCodeBrowserSnippets.buildCompactLayoutScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijCompactInstalled"))
        assertTrue(script.contains("window.matchMedia = "))
        assertTrue(script.contains("(min-width:768px)"))
        assertTrue(script.contains("(max-width:767px)"))
        // Whitespace-tolerant match so minifier/formatter changes do not disable the stub.
        assertTrue(script.contains("const keyOf = (q) =>"))
        assertTrue(script.contains("replace(/\\s+/g, '')"))
        assertTrue(script.contains("stub(q, false)"))
        assertTrue(script.contains("stub(q, true)"))
        // No Tailwind class overrides — layout is driven only by the media-query stub.
        assertFalse(script.contains("opencode-intellij-compact-layout"))
        assertFalse(script.contains("md\\\\:flex-row"))
        assertFalse(script.contains("createElement('style')"))
    }

    @Test
    fun buildHideWebsiteButtonScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildHideWebsiteButtonScript(enabled = false))
    }

    @Test
    fun buildHideWebsiteButtonScriptUsesDurableHrefSelectors() {
        val script = OpenCodeBrowserSnippets.buildHideWebsiteButtonScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijHideWebsiteButtonInstalled"))
        assertTrue(script.contains("href^=\"https://opencode.ai\""))
        assertTrue(script.contains("data-component*=\"icon-button\""))
        // Locale-specific labels and Tailwind layout utilities are not matchers.
        assertFalse(script.contains(".fixed"))
        assertFalse(script.contains("Open the OpenCode website"))
        assertFalse(script.contains("bottom-5"))
        assertFalse(script.contains("right-5"))
    }

    @Test
    fun buildViewportRasterNudgeScriptTogglesTranslateWithoutHostResize() {
        val script = OpenCodeBrowserSnippets.buildViewportRasterNudgeScript()

        assertTrue(script.contains("document.documentElement"))
        assertTrue(script.contains("setProperty('transform', 'translate(1px, 0)')"))
        assertTrue(script.contains("removeProperty('transform')"))
        assertTrue(script.contains("requestAnimationFrame"))
        assertTrue(script.contains("dispatchEvent(new Event('resize'))"))
        assertFalse(script.contains("data-component"))
        assertFalse(script.contains("setBounds"))
        assertFalse(script.contains("1.001"))
    }

    @Test
    fun isInPlaceDialogRepaintEventCoversAskAndDismiss() {
        assertTrue(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("permission.asked"))
        assertTrue(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("permission.replied"))
        assertTrue(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("question.asked"))
        assertTrue(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("question.replied"))
        assertTrue(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("question.rejected"))
        assertFalse(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("session.status"))
        assertFalse(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("session.created"))
    }

    @Test
    fun buildCompactLayoutScriptIsIdempotent() {
        val script = OpenCodeBrowserSnippets.buildCompactLayoutScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijMatchMediaInstalled"))
    }

    @Test
    fun buildIdeThemeSyncScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildIdeThemeSyncScript(enabled = false, dark = true))
    }

    @Test
    fun buildIdeThemeSyncScriptPatchesMatchMediaForPrefersColorScheme() {
        val darkScript =
            OpenCodeBrowserSnippets.buildIdeThemeSyncScript(enabled = true, dark = true)!!
        val lightScript =
            OpenCodeBrowserSnippets.buildIdeThemeSyncScript(enabled = true, dark = false)!!

        assertTrue(darkScript.contains("(prefers-color-scheme: dark)"))
        assertTrue(darkScript.contains("const dark = true"))
        assertTrue(lightScript.contains("const dark = false"))
        assertTrue(darkScript.contains("window.__opencodeIntellijMatchMediaInstalled"))
        assertTrue(darkScript.contains("const THEME_KEY = '(prefers-color-scheme:dark)'"))
        assertTrue(darkScript.contains("replace(/\\s+/g, '').toLowerCase()"))
        assertTrue(darkScript.contains("theme && key === THEME_KEY"))
        assertTrue(darkScript.contains("matches: dark"))
        assertFalse(darkScript.contains("window.localStorage.setItem"))
        assertFalse(darkScript.contains("StorageEvent"))
    }

    @Test
    fun buildIdeThemeSyncScriptDispatchesChangeEventOnUpdate() {
        val script = OpenCodeBrowserSnippets.buildIdeThemeSyncScript(enabled = true, dark = true)!!

        assertTrue(script.contains("MediaQueryListEvent('change'"))
        assertTrue(script.contains("window.__opencodeIntellijThemeMql"))
        assertTrue(script.contains("window.__opencodeIntellijThemeDark !== dark"))
    }

    @Test
    fun buildProjectSwitchPromptSuppressionScriptIsMissingWhenDisabled() {
        assertNull(
            OpenCodeBrowserSnippets.buildProjectSwitchPromptSuppressionScript(enabled = false)
        )
    }

    @Test
    fun buildProjectSwitchPromptSuppressionScriptDismissesGoToSessionNotifications() {
        val script =
            OpenCodeBrowserSnippets.buildProjectSwitchPromptSuppressionScript(enabled = true)!!

        assertTrue(
            script.contains("window.__opencodeIntellijProjectSwitchPromptSuppressionInstalled")
        )
        assertTrue(script.contains("[data-component=\"toast\"], [data-component=\"toast-v2\"]"))
        // Locale-independent structural match: sprite icon names, not translated labels.
        // Both v1 and v2 sprite prefixes are covered so an icon-system migration stays matched.
        assertTrue(script.contains("use[href=\"#opencode-icon-checklist\"]"))
        assertTrue(script.contains("use[href=\"#opencode-icon-bubble-5\"]"))
        assertTrue(script.contains("use[href=\"#opencode-v2-icon-checklist\"]"))
        assertTrue(script.contains("use[href=\"#opencode-v2-icon-bubble-5\"]"))
        assertTrue(script.contains("[data-slot=\"toast-icon\"], [data-slot=\"toast-v2-icon\"]"))
        assertFalse(script.contains("Permission required"))
        assertFalse(script.contains("Go to session"))
        assertTrue(
            script.contains(
                "[data-slot=\"toast-close-button\"], [data-slot=\"toast-v2-close-button\"]"
            )
        )
        assertTrue(script.contains("new MutationObserver"))
        // Bounded blast radius: auto-dismissals are capped per page load, and hitting the cap
        // disconnects the observer (suppression off for this load) with a single warning.
        assertTrue(script.contains("const MAX_DISMISSALS = 20;"))
        assertTrue(script.contains("if (dismissals >= MAX_DISMISSALS)"))
        assertTrue(script.contains("observer.disconnect()"))
        assertTrue(script.contains("OpenCode toast suppression cap reached"))
    }

    @Test
    fun buildCursorMirrorScriptIsMissingWhenDisabledOrIncomplete() {
        assertNull(
            OpenCodeBrowserSnippets.buildCursorMirrorScript(
                enabled = false,
                cursorCallback = "cb(payload)",
            )
        )
        assertNull(
            OpenCodeBrowserSnippets.buildCursorMirrorScript(enabled = true, cursorCallback = null)
        )
    }

    @Test
    fun buildCursorMirrorScriptTracksHoveredElementCursor() {
        val script =
            OpenCodeBrowserSnippets.buildCursorMirrorScript(
                enabled = true,
                cursorCallback = "window.intellijCursor(payload)",
            )!!

        assertTrue(script.contains("window.__opencodeIntellijCursorMirrorInstalled"))
        assertTrue(script.contains("getComputedStyle(el).cursor"))
        assertTrue(script.contains("caretPositionFromPoint"))
        assertTrue(script.contains("caretRangeFromPoint"))
        assertTrue(script.contains("addEventListener('pointermove'"))
        assertTrue(script.contains("addEventListener('pointerdown'"))
        assertTrue(script.contains("addEventListener('pointerup'"))
        assertTrue(script.contains("addEventListener('scroll'"))
        assertTrue(script.contains("addEventListener('mouseout'"))
        assertTrue(script.contains("send('default')"))
        assertTrue(script.contains("event.buttons !== 0"))
        assertTrue(script.contains("window.intellijCursor(payload)"))
    }

    @Test
    fun awtCursorTypeCoversCommonCssCursors() {
        assertEquals(
            java.awt.Cursor.DEFAULT_CURSOR,
            OpenCodeBrowserSnippets.awtCursorTypeForCss(null),
        )
        assertEquals(
            java.awt.Cursor.DEFAULT_CURSOR,
            OpenCodeBrowserSnippets.awtCursorTypeForCss("default"),
        )
        assertEquals(
            java.awt.Cursor.DEFAULT_CURSOR,
            OpenCodeBrowserSnippets.awtCursorTypeForCss("auto"),
        )
        assertEquals(
            java.awt.Cursor.HAND_CURSOR,
            OpenCodeBrowserSnippets.awtCursorTypeForCss("pointer"),
        )
        assertEquals(
            java.awt.Cursor.TEXT_CURSOR,
            OpenCodeBrowserSnippets.awtCursorTypeForCss("text"),
        )
        assertEquals(
            java.awt.Cursor.WAIT_CURSOR,
            OpenCodeBrowserSnippets.awtCursorTypeForCss("progress"),
        )
        assertEquals(
            java.awt.Cursor.S_RESIZE_CURSOR,
            OpenCodeBrowserSnippets.awtCursorTypeForCss("row-resize"),
        )
        assertEquals(
            java.awt.Cursor.S_RESIZE_CURSOR,
            OpenCodeBrowserSnippets.awtCursorTypeForCss("ns-resize"),
        )
        assertEquals(
            java.awt.Cursor.W_RESIZE_CURSOR,
            OpenCodeBrowserSnippets.awtCursorTypeForCss("col-resize"),
        )
        assertEquals(
            java.awt.Cursor.MOVE_CURSOR,
            OpenCodeBrowserSnippets.awtCursorTypeForCss("grabbing"),
        )
        // Unknown keywords resolve to the default arrow; custom cursors use their keyword fallback.
        assertEquals(
            java.awt.Cursor.DEFAULT_CURSOR,
            OpenCodeBrowserSnippets.awtCursorTypeForCss("zoom-in"),
        )
        assertEquals(
            java.awt.Cursor.HAND_CURSOR,
            OpenCodeBrowserSnippets.awtCursorTypeForCss("url(\"custom.png\") 4 4, pointer"),
        )
        assertEquals(
            java.awt.Cursor.DEFAULT_CURSOR,
            OpenCodeBrowserSnippets.awtCursorTypeForCss("URL(x.cur)"),
        )
    }
}
