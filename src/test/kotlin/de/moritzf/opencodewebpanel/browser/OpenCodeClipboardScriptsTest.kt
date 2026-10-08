package de.moritzf.opencodewebpanel.browser

import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builder safeguards and external contracts; DOM behavior lives in scripts/browser-contract.js. */
class OpenCodeClipboardScriptsTest {
    @Test
    fun buildDispatchDroppedFilesScriptCreatesBrowserDropEvent() {
        val script =
            OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
                listOf(
                    OpenCodeServerProtocol.DroppedFilePayload(
                        name = "hello 'world'.txt",
                        mime = "text/plain",
                        lastModified = 123,
                        base64 = "aGVsbG8=",
                    )
                )
            )!!

        assertTrue(script.contains("new DataTransfer()"))
        assertTrue(script.contains("new File([decode(entry.base64)], entry.name"))
        assertTrue(script.contains("[data-component=\"prompt-input\"][contenteditable=\"true\"]"))
        assertTrue(
            script.contains("[data-component=\"composer-editor\"][contenteditable=\"true\"]")
        )
        assertTrue(script.contains("const event = new DragEvent('drop'"))
        assertTrue(script.contains("return event.defaultPrevented"))
        assertTrue(script.contains("hello \\'world\\'.txt"))
        assertTrue(script.contains("aGVsbG8="))
        assertTrue(script.contains("const focusPrompt = false"))
        assertTrue(script.contains("if (focusPrompt)"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptFocusesPromptOnlyWhenRequested() {
        val script =
            OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
                emptyList(),
                textPlain = listOf("file:src/main/App.kt"),
                enabled = true,
                focusPrompt = true,
            )!!

        assertTrue(script.contains("const focusPrompt = true"))
        assertTrue(script.contains("if (focusPrompt)"))
        assertTrue(script.contains("target.focus()"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptEscapesUnsafeCharactersInFileNames() {
        val script =
            OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
                listOf(
                    OpenCodeServerProtocol.DroppedFilePayload(
                        name = "a<b\u2028c\u2029d\u0000e",
                        mime = "text/plain",
                        lastModified = 1,
                        base64 = "aGVsbG8=",
                    )
                )
            )!!

        assertTrue(script.contains("a\\u003Cb\\u2028c\\u2029d\\u0000e"))
        assertFalse(script.contains("a<b"))
        assertFalse(script.contains("\u2028"))
        assertFalse(script.contains("\u0000"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptCanForwardTextPlainDropData() {
        val script =
            OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
                emptyList(),
                textPlain = listOf("file:src/main/App.kt"),
                enabled = true,
            )!!

        assertTrue(script.contains("transfer.setData('text/plain', 'file:src/main/App.kt')"))
        assertTrue(script.contains("const event = new DragEvent('drop'"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptDispatchesTextPlainDropsSeparately() {
        val script =
            OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
                emptyList(),
                textPlain = listOf("file:CHANGELOG.md", "file:gradle.properties"),
                enabled = true,
            )!!

        assertTrue(
            script.contains(
                "results.push(dispatchDrop((transfer) => transfer.setData('text/plain', 'file:CHANGELOG.md')))"
            )
        )
        assertTrue(
            script.contains(
                "results.push(dispatchDrop((transfer) => transfer.setData('text/plain', 'file:gradle.properties')))"
            )
        )
    }

    @Test
    fun buildDispatchDroppedFilesScriptPastesGenericTextAndReportsAcceptance() {
        val script =
            OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
                emptyList(),
                textPlain = listOf("selected code"),
                batchId = "chat-1",
                resultCallback = "window.intellijResult(batchId, accepted)",
            )!!

        assertTrue(script.contains("new ClipboardEvent('paste'"))
        assertTrue(script.contains("results.push(dispatchPaste('selected code'))"))
        assertTrue(script.contains("const batchId = 'chat-1'"))
        assertTrue(script.contains("window.intellijResult(batchId, accepted)"))
        assertTrue(script.contains("results.every(Boolean)"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptPastesMultilineSelectionBeginningWithFileReference() {
        val selection =
            """
            |file:src/main/App.kt
            |src/main/App.kt lines 1-2:
            |```kotlin
            |fun main() = Unit
            |```
            """
                .trimMargin()

        val script =
            OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
                emptyList(),
                textPlain = listOf(selection),
            )!!

        assertTrue(script.contains("results.push(dispatchPaste('file:src/main/App.kt\\n"))
        assertFalse(script.contains("transfer.setData('text/plain', 'file:src/main/App.kt\\n"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptIsMissingWithoutFiles() {
        assertNull(OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(emptyList()))
        assertNull(
            OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
                emptyList(),
                textPlain = emptyList(),
                enabled = true,
            )
        )
    }

    @Test
    fun buildDispatchDroppedFilesScriptIsMissingWhenDisabled() {
        assertNull(
            OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
                listOf(
                    OpenCodeServerProtocol.DroppedFilePayload(
                        name = "hello.txt",
                        mime = "text/plain",
                        lastModified = 123,
                        base64 = "aGVsbG8=",
                    )
                ),
                enabled = false,
            )
        )
    }

    @Test
    fun clipboardScriptsAreMissingWhenDisabled() {
        assertNull(
            OpenCodeBrowserSnippets.buildCaptureClipboardPasteScript("paste-1", enabled = false)
        )
        assertNull(
            OpenCodeBrowserSnippets.buildClipboardPasteScript(
                emptyList(),
                "text",
                emptyList(),
                "paste-1",
                "callback(result)",
                enabled = false,
            )
        )
    }

    @Test
    fun clipboardBridgeRequiresResultChannelForNativeFallback() {
        assertNull(
            OpenCodeBrowserSnippets.buildClipboardPasteScript(
                emptyList(),
                "text",
                emptyList(),
                "paste-1",
                null,
                enabled = true,
            )
        )
    }
}
