package de.moritzf.opencodewebpanel.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * `contains(...)` checks do not catch a broken template. Parse every generated injection
 * with Node when it is on PATH so a quote, brace, or template-literal mistake fails here.
 */
class OpenCodeInjectedScriptSyntaxTest {
    @Test
    fun generatedInjectionScriptsAreValidJavaScript() {
        val node = nodeExecutable()
        assumeTrue("node is not on PATH; skipping injected-script syntax check", node != null)

        val failures = mutableListOf<String>()
        for ((name, script) in generatedScripts()) {
            val file = Files.createTempFile("opencode-snippet-$name-", ".js")
            try {
                Files.writeString(file, script)
                val process = ProcessBuilder(node, "--check", file.toString())
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val finished = process.waitFor(15, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    failures += "$name: node --check timed out"
                } else if (process.exitValue() != 0) {
                    failures += "$name: $output"
                }
            } finally {
                Files.deleteIfExists(file)
            }
        }
        if (failures.isNotEmpty()) {
            fail(failures.joinToString("\n"))
        }
        assertEquals(0, failures.size)
    }

    private fun generatedScripts(): List<Pair<String, String>> {
        val hostile = "proj'\\\"\${not}\n</script>\u2028"
        val callback = "window.intellijCallback(${"'payload'"})"
        val dropped = OpenCodeServerProtocol.DroppedFilePayload(
            name = "hello 'world'.txt",
            mime = "text/plain",
            lastModified = 123,
            base64 = "aGVsbG8=",
        )
        return listOf(
            "openProject" to OpenCodeBrowserSnippets.buildOpenProjectScript("/tmp/$hostile", "http://127.0.0.1:60482/"),
            "fileLink" to OpenCodeBrowserSnippets.buildFileLinkHandlerScript("/tmp/$hostile", enabled = true, openFileCallback = callback),
            "externalLink" to OpenCodeBrowserSnippets.buildExternalLinkHandlerScript(enabled = true, openExternalCallback = callback),
            "restoreStorage" to OpenCodeBrowserSnippets.buildRestoreOpenCodeLocalStorageScript("""{"settings.v3":"{\\"x\\":1}"}"""),
            "syncStorage" to OpenCodeBrowserSnippets.buildSyncOpenCodeLocalStorageScript(callback),
            "clearStorage" to OpenCodeBrowserSnippets.buildClearOpenCodeWebStateScript(),
            "drop" to OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(listOf(dropped), textPlain = listOf("file:$hostile", "line\n$hostile")),
            "matchMedia" to OpenCodeBrowserSnippets.buildMatchMediaPatchScript(compact = true, theme = true, dark = true),
            "compact" to OpenCodeBrowserSnippets.buildCompactLayoutScript(enabled = true),
            "compactHome" to OpenCodeBrowserSnippets.buildCompactHomeLayoutScript(enabled = true),
            "hideWebsite" to OpenCodeBrowserSnippets.buildHideWebsiteButtonScript(enabled = true),
            "pathHover" to OpenCodeBrowserSnippets.buildPathHoverPreviewScript(enabled = true),
            "eventWatchdog" to OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true),
            "forceReconnect" to OpenCodeBrowserSnippets.buildForceEventReconnectScript(),
            "chunkLoad" to OpenCodeBrowserSnippets.buildChunkLoadRecoveryScript(enabled = true, fatalCallback = callback),
            "heartbeat" to OpenCodeBrowserSnippets.buildRendererHeartbeatScript(enabled = true, heartbeatCallback = callback),
            "raster" to OpenCodeBrowserSnippets.buildViewportRasterNudgeScript(),
            "theme" to OpenCodeBrowserSnippets.buildIdeThemeSyncScript(enabled = true, dark = true),
            "projectSwitch" to OpenCodeBrowserSnippets.buildProjectSwitchPromptSuppressionScript(enabled = true),
            "cursor" to OpenCodeBrowserSnippets.buildCursorMirrorScript(enabled = true, cursorCallback = callback),
            "paste" to OpenCodeBrowserSnippets.buildFilePasteSuppressionScript(enabled = true),
            "codeNav" to OpenCodeBrowserSnippets.buildCodeNavigationScript(enabled = true, openCodeCallback = callback),
            "diffNav" to OpenCodeBrowserSnippets.buildDiffNavigationScript(enabled = true, openDiffCallback = callback),
            "shortcuts" to OpenCodeBrowserSnippets.buildShortcutDispatchScript(listOf("Mod+Shift+'"), listOf("Mod+N")),
            "foreignTab" to OpenCodeBrowserSnippets.buildForeignSessionTabOutlineScript("ses_abc123"),
            "foreignTabClear" to OpenCodeBrowserSnippets.buildForeignSessionTabOutlineScript(null),
        ).mapNotNull { (name, script) -> script?.let { name to it } }
    }

    private fun nodeExecutable(): String? {
        val names = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            listOf("node.exe", "node")
        } else {
            listOf("node")
        }
        return System.getenv("PATH").orEmpty()
            .split(File.pathSeparator)
            .asSequence()
            .flatMap { dir -> names.asSequence().map { File(dir, it) } }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }
}
