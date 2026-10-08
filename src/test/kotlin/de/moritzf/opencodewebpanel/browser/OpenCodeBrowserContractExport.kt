package de.moritzf.opencodewebpanel.browser

import com.google.gson.GsonBuilder
import de.moritzf.opencodewebpanel.server.OpenCodeWireProtocol
import java.nio.file.Files
import java.nio.file.Path

/** Used by scripts/check-browser-contract.mjs; never reconstruct Kotlin strings in the JS tests. */
object OpenCodeBrowserContractExport {
    @JvmStatic
    fun main(args: Array<String>) {
        val (directory, origin, output) = args
        val snippets =
            mapOf(
                "seed" to OpenCodeBrowserSnippets.buildOpenProjectScript(directory, origin),
                "capturePaste" to
                    OpenCodeBrowserSnippets.buildCaptureClipboardPasteScript(
                        "paste-contract",
                        enabled = true,
                    ),
                "paste" to
                    OpenCodeBrowserSnippets.buildClipboardPasteScript(
                        emptyList(),
                        "NEW",
                        emptyList(),
                        "paste-contract",
                        "window.__pasteResult = result",
                        enabled = true,
                    ),
                "nativePaste" to
                    OpenCodeBrowserSnippets.buildClipboardPasteScript(
                        emptyList(),
                        null,
                        emptyList(),
                        "paste-contract",
                        "window.__pasteResult = result",
                        enabled = true,
                        nativeFallback = true,
                    ),
                "files" to
                    OpenCodeBrowserSnippets.buildFileLinkHandlerScript(
                        directory,
                        enabled = true,
                        openFileCallback = "window.__fileCalls.push({href: rawHref, partID})",
                    ),
                "code" to
                    OpenCodeBrowserSnippets.buildCodeNavigationScript(
                        enabled = true,
                        openCodeCallback = "window.__codeCalls.push(ref)",
                    ),
                "diffs" to
                    OpenCodeBrowserSnippets.buildDiffNavigationScript(
                        enabled = true,
                        openDiffCallback =
                            "window.__diffCalls.push({messageID, filePath, partID, vcsMode})",
                    ),
                "chunks" to
                    OpenCodeBrowserSnippets.buildChunkLoadRecoveryScript(
                        enabled = true,
                        fatalCallback = "window.__chunkCalls.push(message)",
                    ),
                "watchdog" to
                    OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true),
                "pathHover" to OpenCodeBrowserSnippets.buildPathHoverPreviewScript(enabled = true),
                "nativeWatchdog" to
                    OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(
                        enabled = true,
                        wireProtocol = OpenCodeWireProtocol.V2_CLI,
                    ),
            )
        val target = Path.of(output)
        Files.createDirectories(target.parent)
        Files.writeString(target, GsonBuilder().serializeNulls().create().toJson(snippets))
    }
}
