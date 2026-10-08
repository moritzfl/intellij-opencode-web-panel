package de.moritzf.opencodewebpanel.browser

import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builder safeguards and external contracts; DOM behavior lives in scripts/browser-contract.js. */
class OpenCodeNavigationScriptsTest {
    @Test
    fun buildFileLinkHandlerScriptInterceptsLocalFileLinks() {
        val script =
            OpenCodeBrowserSnippets.buildFileLinkHandlerScript("/tmp/project", enabled = true)!!

        // DOM markers/callbacks are contracts; real-page behavior is exercised by the browser gate.
        assertTrue(script.contains("session-review-view-button"))
        assertTrue(script.contains("opencode-intellij-open-file"))
        assertTrue(script.contains("data-file"))
        assertTrue(script.contains("data-local-link"))
    }

    @Test
    fun buildFileLinkHandlerScriptSupportsRedesignedReviewPanelPreviewHeader() {
        val script =
            OpenCodeBrowserSnippets.buildFileLinkHandlerScript("/tmp/project", enabled = true)!!

        // The redesigned (v2) review panel — shown on desktop when forceCompactLayout is off —
        // exposes the changed file only through its preview header spans, so the "open in IDE"
        // gesture must resolve the path from there.
        assertTrue(script.contains("reviewV2FileLink(target)"))
        assertTrue(script.contains("session-review-v2-file-title"))
        assertTrue(script.contains("session-review-v2-file-name"))
        assertTrue(script.contains("session-review-v2-file-path"))
        assertTrue(script.contains("session-review-v2-file-header"))
        // The sidebar tree rows are the SPA's own preview navigation; hijacking them would break
        // in-app review, so they must never be an intercept target.
        assertFalse(script.contains("session-review-v2-sidebar-tree"))
    }

    @Test
    fun buildFileLinkHandlerScriptIsMissingWhenDisabled() {
        assertNull(
            OpenCodeBrowserSnippets.buildFileLinkHandlerScript("/tmp/project", enabled = false)
        )
    }

    @Test
    fun buildFileLinkHandlerScriptCanUseDirectCallback() {
        val script =
            OpenCodeBrowserSnippets.buildFileLinkHandlerScript(
                "/tmp/project",
                enabled = true,
                openFileCallback = "window.intellijOpenFile(rawHref + '\\n' + directory)",
            )!!

        assertTrue(script.contains("window.intellijOpenFile(rawHref + '\\n' + directory)"))
        assertTrue(script.contains("Failed to forward file link to IntelliJ"))
        assertTrue(
            script.contains(
                "${OpenCodeServerProtocol.OPEN_FILE_LINK_SCHEME}://${OpenCodeServerProtocol.OPEN_FILE_LINK_HOST}"
            )
        )
        assertFalse(script.contains("window.location.assign(target)"))
    }

    @Test
    fun buildExternalLinkHandlerScriptInterceptsOnlyExternalHttpLinks() {
        val script =
            OpenCodeBrowserSnippets.buildExternalLinkHandlerScript(
                enabled = true,
                openExternalCallback = "window.intellijOpenExternal(href)",
            )!!

        assertTrue(script.contains("window.__opencodeIntellijExternalLinksInstalled"))
        assertTrue(script.contains("event.target.closest('a')"))
        assertTrue(script.contains("url.protocol !== 'http:' && url.protocol !== 'https:'"))
        assertTrue(script.contains("url.origin === window.location.origin"))
        assertTrue(script.contains("window.__opencodeIntellijNativeWindowOpen"))
        assertTrue(script.contains("window.open = function(url, target, features)"))
        assertTrue(script.contains("event.preventDefault()"))
        assertTrue(script.contains("event.stopImmediatePropagation()"))
        assertTrue(script.contains("window.intellijOpenExternal(href)"))
    }

    @Test
    fun buildExternalLinkHandlerScriptIsMissingWhenDisabled() {
        assertNull(
            OpenCodeBrowserSnippets.buildExternalLinkHandlerScript(
                enabled = false,
                openExternalCallback = "callback(href)",
            )
        )
    }

    @Test
    fun buildExternalLinkHandlerScriptIsMissingWithoutCallback() {
        assertNull(
            OpenCodeBrowserSnippets.buildExternalLinkHandlerScript(
                enabled = true,
                openExternalCallback = null,
            )
        )
    }

    @Test
    fun buildCodeNavigationScriptIsMissingWhenDisabled() {
        assertNull(
            OpenCodeBrowserSnippets.buildCodeNavigationScript(
                enabled = false,
                openCodeCallback = "callback(ref)",
            )
        )
    }

    @Test
    fun buildCodeNavigationScriptIsMissingWithoutCallback() {
        assertNull(
            OpenCodeBrowserSnippets.buildCodeNavigationScript(
                enabled = true,
                openCodeCallback = null,
            )
        )
    }

    @Test
    fun buildCodeNavigationScriptInstallsClickListenerOnCodeElements() {
        val script =
            OpenCodeBrowserSnippets.buildCodeNavigationScript(
                enabled = true,
                openCodeCallback = "window.intellijOpenCodeRef(ref)",
            )!!

        // DOM markers/callbacks are contracts; real-page behavior is exercised by the browser gate.
        assertTrue(script.contains("data-inline-code-kind"))
        assertTrue(script.contains("bash-pre"))
        assertTrue(script.contains("tool-output"))
        assertTrue(script.contains("window.intellijOpenCodeRef(ref)"))
    }

    @Test
    fun buildFileLinkHandlerScriptStopsAlreadyHandledClicks() {
        val script =
            OpenCodeBrowserSnippets.buildFileLinkHandlerScript(
                "/tmp/project",
                enabled = true,
                openFileCallback = "window.intellijOpenFile(rawHref)",
            )!!

        assertTrue(script.contains("if (event.defaultPrevented) return"))
        assertTrue(script.contains("event.stopImmediatePropagation()"))
    }

    @Test
    fun buildDiffNavigationScriptIsMissingWhenDisabled() {
        assertNull(
            OpenCodeBrowserSnippets.buildDiffNavigationScript(
                enabled = false,
                openDiffCallback = "cb(payload)",
            )
        )
    }

    @Test
    fun buildDiffNavigationScriptIsMissingWithoutCallback() {
        assertNull(
            OpenCodeBrowserSnippets.buildDiffNavigationScript(
                enabled = true,
                openDiffCallback = null,
            )
        )
    }

    @Test
    fun buildDiffNavigationScriptInstallsAltClickHandlerForDiffTargets() {
        val script =
            OpenCodeBrowserSnippets.buildDiffNavigationScript(
                enabled = true,
                openDiffCallback = "window.__openDiff(messageID, filePath, partID)",
            )!!
        // DOM markers/callbacks are contracts; real-page behavior is exercised by the browser gate.
        assertTrue(script.contains("[data-message-id]"))
        assertTrue(script.contains("[data-timeline-part-id]"))
        assertTrue(script.contains("session-review-v2-sidebar"))
        assertTrue(script.contains("select-v2"))
        assertTrue(script.contains("window.__openDiff(messageID, filePath, partID)"))
    }

    @Test
    fun fileLinkHandlerReservesDiffGesture() {
        val script =
            OpenCodeBrowserSnippets.buildFileLinkHandlerScript("/tmp/project", enabled = true)!!
        assertTrue(script.contains("event.altKey"))
        assertTrue(script.contains("event.metaKey"))
        assertTrue(script.contains("event.ctrlKey"))
        assertTrue(script.contains("filesBrowserRow"))
        assertTrue(script.contains("file-tree-v2-row"))
        assertTrue(script.contains("if (filesRow && !modified) return"))
        assertTrue(script.contains("if (modified && !filesRow) return"))
    }
}
