package de.moritzf.opencodewebpanel.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builder safeguards and external contracts; DOM behavior lives in scripts/browser-contract.js. */
class OpenCodeStorageScriptsTest {
    @Test
    fun buildOpenProjectScriptSeedsProjectState() {
        val script =
            OpenCodeBrowserSnippets.buildOpenProjectScript(
                "/tmp/my 'project'",
                "http://127.0.0.1:60482/",
            )!!

        // DOM markers/callbacks are contracts; real-page behavior is exercised by the browser gate.
        assertTrue(script.contains("opencode.global.dat:server"))
        assertTrue(script.contains("opencode-intellij-project"))
        assertTrue(script.contains("opencode.window.browser.dat:tabs"))
    }

    @Test
    fun buildOpenProjectScriptIsMissingWithoutProjectPath() {
        assertNull(OpenCodeBrowserSnippets.buildOpenProjectScript(null))
        assertNull(OpenCodeBrowserSnippets.buildOpenProjectScript(""))
    }

    @Test
    fun buildRestoreOpenCodeLocalStorageScriptIsMissingWithoutSnapshot() {
        assertNull(OpenCodeBrowserSnippets.buildRestoreOpenCodeLocalStorageScript(null))
        assertNull(OpenCodeBrowserSnippets.buildRestoreOpenCodeLocalStorageScript("{}"))
    }

    @Test
    fun buildRestoreOpenCodeLocalStorageScriptRestoresOpenCodeKeysOnlyWhenMissing() {
        val script =
            OpenCodeBrowserSnippets.buildRestoreOpenCodeLocalStorageScript(
                "{\"opencode.global.dat:language\":\"{\\\"locale\\\":\\\"de\\\"}\"}"
            )!!

        // DOM markers/callbacks are contracts; real-page behavior is exercised by the browser gate.
        assertTrue(script.contains("settings.v3"))
        assertTrue(script.contains("opencode-theme-id"))
    }

    @Test
    fun buildSyncOpenCodeLocalStorageScriptMirrorsOpenCodeKeys() {
        val script =
            OpenCodeBrowserSnippets.buildSyncOpenCodeLocalStorageScript(
                "window.intellijStore(payload)"
            )!!

        // DOM markers/callbacks are contracts; real-page behavior is exercised by the browser gate.
        assertTrue(script.contains("settings.v3"))
        assertTrue(script.contains("opencode-theme-id"))
    }

    @Test
    fun buildClearOpenCodeWebStateScriptWipesBrowserStorage() {
        val script = OpenCodeBrowserSnippets.buildClearOpenCodeWebStateScript()

        assertTrue(script.contains("window.localStorage.clear()"))
        assertTrue(script.contains("window.sessionStorage.clear()"))
        // Each clear is individually guarded so a storage-access failure cannot abort the other.
        assertEquals(
            2,
            Regex("""try \{ window\.\w+Storage\.clear\(\); \} catch \(_\) \{\}""")
                .findAll(script)
                .count(),
        )
    }
}
