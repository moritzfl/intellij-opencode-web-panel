package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import java.awt.Image
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.TransferHandler
import org.cef.handler.CefKeyboardHandler.CefKeyEvent
import org.cef.handler.CefKeyboardHandler.CefKeyEvent.EventType
import org.cef.handler.CefKeyboardHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.EventFlags

internal class OpenCodeFileDropHandler(
    private val project: Project,
    private val browser: JBCefBrowser,
    private val serverManager: SharedOpenCodeServerManager,
    private val openCodeProjectDirectory: () -> String?,
    private val isDisposed: () -> Boolean,
    private val parentDisposable: Disposable,
) {
    internal companion object {
        private const val MAX_DROPPED_FILE_BYTES = 5L * 1024L * 1024L
        private const val MAX_DROPPED_FILES_TOTAL_BYTES = 10L * 1024L * 1024L
        private const val BROWSER_PASTE_SUPPRESSION_MILLIS = 1_500L

        fun isPasteShortcut(keyCode: Int, modifiers: Int): Boolean {
            return isPasteShortcut(keyCode, modifiers, 0.toChar(), 0.toChar())
        }

        fun isPasteShortcut(keyCode: Int, modifiers: Int, character: Char, unmodifiedCharacter: Char): Boolean {
            val hasCommand = modifiers and EventFlags.EVENTFLAG_COMMAND_DOWN != 0
            val hasControl = modifiers and EventFlags.EVENTFLAG_CONTROL_DOWN != 0
            val hasAlt = modifiers and EventFlags.EVENTFLAG_ALT_DOWN != 0
            val key = listOf(character, unmodifiedCharacter).any { it.lowercaseChar() == 'v' }
            return (keyCode == KeyEvent.VK_V || key) && hasCommand != hasControl && !hasAlt
        }

        internal fun shouldUseDroppedImageFlavor(droppedFiles: List<File>, projectDirectory: String?): Boolean {
            if (droppedFiles.isEmpty()) return true
            if (droppedFiles.any { OpenCodeServerProtocol.localFileDropText(it, projectDirectory) != null }) return false
            return droppedFiles.none { Files.isRegularFile(it.toPath()) }
        }

        internal fun encodeImageToPng(image: Image): ByteArray? {
            val bufferedImage = (image as? BufferedImage) ?: toBufferedImage(image) ?: return null
            return runCatching {
                ByteArrayOutputStream().use { stream ->
                    if (ImageIO.write(bufferedImage, "png", stream)) stream.toByteArray() else null
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

        private fun toBufferedImage(image: Image): BufferedImage? {
            val width = image.getWidth(null)
            val height = image.getHeight(null)
            if (width <= 0 || height <= 0) return null
            // Off-screen buffer used only to encode the clipboard image to PNG bytes; it must match the
            // source pixel dimensions exactly, so UIUtil.createImage()'s HiDPI scaling is intentionally avoided.
            @Suppress("UndesirableClassUsage")
            val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val graphics = buffered.createGraphics()
            try {
                graphics.drawImage(image, 0, 0, null)
            } finally {
                graphics.dispose()
            }
            return buffered
        }
    }

    @Volatile
    private var suppressNextBrowserPasteAtMillis = 0L

    fun install() {
        val handler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                if (!OpenCodeSettingsState.getInstance().enableChatFileDrop) return false
                if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverManager.getServerUrl(), browser.cefBrowser.url)) return false
                if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) &&
                    !supportsText(support.transferable) &&
                    !supportsImageDrop(support)
                ) {
                    return false
                }
                if (support.isDrop) support.dropAction = COPY
                return true
            }

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                val droppedFiles = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                }.getOrNull().orEmpty()
                val imagePayloads = droppedImagePayloads(support.transferable, droppedFiles)
                val text = droppedTextPayload(support.transferable)
                val fileReferenceText = text?.takeIf { it.startsWith("file:") }
                val textToDispatch = if (imagePayloads.isNotEmpty()) fileReferenceText else text
                return dispatchDroppedData(droppedFiles, textToDispatch, imagePayloads)
            }
        }
        installTransferHandler(browser.component, handler)
        (browser.browserComponent as? JComponent)?.let { installTransferHandler(it, handler) }
        installPasteDispatcher()
        installPasteKeyboardHandler()
    }

    private fun installTransferHandler(component: JComponent, handler: TransferHandler) {
        component.transferHandler = handler
        component.components
            .filterIsInstance<JComponent>()
            .forEach { installTransferHandler(it, handler) }
    }

    private fun installPasteKeyboardHandler() {
        val handler = object : CefKeyboardHandlerAdapter() {
            override fun onPreKeyEvent(
                browser: org.cef.browser.CefBrowser?,
                event: CefKeyEvent?,
                isKeyboardShortcut: BoolRef?,
            ): Boolean {
                if (event?.type != EventType.KEYEVENT_RAWKEYDOWN && event?.type != EventType.KEYEVENT_KEYDOWN) return false
                if (!isPasteShortcut(event.windows_key_code, event.modifiers, event.character, event.unmodified_character)) return false
                if (isDisposed()) return false
                if (!OpenCodeSettingsState.getInstance().enableChatFileDrop) return false
                if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverManager.getServerUrl(), this@OpenCodeFileDropHandler.browser.cefBrowser.url)) {
                    return false
                }
                return shouldSuppressBrowserPaste()
            }
        }
        browser.jbCefClient.addKeyboardHandler(handler, browser.cefBrowser)
        Disposer.register(parentDisposable) {
            browser.jbCefClient.removeKeyboardHandler(handler, browser.cefBrowser)
        }
    }

    private fun installPasteDispatcher() {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = KeyEventDispatcher { event ->
            if (event.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            if (!isPasteShortcut(event)) return@KeyEventDispatcher false
            if (isDisposed()) return@KeyEventDispatcher false
            if (!OpenCodeSettingsState.getInstance().enableChatFileDrop) return@KeyEventDispatcher false
            if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverManager.getServerUrl(), browser.cefBrowser.url)) return@KeyEventDispatcher false
            if (!isFocusInsideBrowser()) return@KeyEventDispatcher false
            if (!pasteClipboardData()) return@KeyEventDispatcher false

            suppressNextBrowserPasteAtMillis = System.currentTimeMillis()
            event.consume()
            true
        }
        focusManager.addKeyEventDispatcher(dispatcher)
        Disposer.register(parentDisposable) {
            focusManager.removeKeyEventDispatcher(dispatcher)
        }
    }

    private fun isPasteShortcut(event: KeyEvent): Boolean {
        return event.keyCode == KeyEvent.VK_V && (event.isMetaDown || event.isControlDown) && !event.isAltDown
    }

    private fun isFocusInsideBrowser(): Boolean {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
        return browser.component == focusOwner || browser.component.isAncestorOf(focusOwner)
    }

    private fun shouldSuppressBrowserPaste(): Boolean {
        val suppress = System.currentTimeMillis() - suppressNextBrowserPasteAtMillis <= BROWSER_PASTE_SUPPRESSION_MILLIS
        if (suppress) suppressNextBrowserPasteAtMillis = 0L
        return suppress
    }

    private fun pasteClipboardData(): Boolean {
        val transferables = clipboardTransferables()
        val files = transferables
            .flatMap { clipboardFiles(it) }
            .distinctBy { it.toPath().toAbsolutePath().normalize() }
        val imagePayloads = if (files.isEmpty()) clipboardImagePayloads(transferables) else emptyList()
        val text = transferables.firstNotNullOfOrNull { droppedTextPayload(it) }
        val fileReferenceText = text?.takeIf { it.startsWith("file:") }
        if (files.isEmpty() && imagePayloads.isEmpty() && fileReferenceText == null) return false
        // When a pasted image is forwarded, ignore any incidental text flavor that is not a file reference.
        val textToDispatch = if (imagePayloads.isNotEmpty()) fileReferenceText else text
        return dispatchDroppedData(files, textToDispatch, imagePayloads)
    }

    private fun dispatchDroppedData(
        files: List<File>,
        textPlain: String?,
        extraPayloads: List<OpenCodeServerProtocol.DroppedFilePayload> = emptyList(),
    ): Boolean {
        val projectDirectory = openCodeProjectDirectory()
        val fileTextDrops = files.mapNotNull { file -> OpenCodeServerProtocol.localFileDropText(file, projectDirectory) }
        val textDrops = fileTextDrops.ifEmpty { droppedTextPlainItems(files, textPlain) }
        val filesToForward = files.filter { file -> OpenCodeServerProtocol.localFileDropText(file, projectDirectory) == null }
        val selection = selectDroppedFiles(filesToForward)
        if (selection.rejectionMessages.isNotEmpty()) {
            showFileDropWarning(selection.rejectionMessages)
        }
        if (textDrops.isEmpty() && selection.acceptedFiles.isEmpty() && extraPayloads.isEmpty()) {
            return selection.rejectionMessages.isNotEmpty()
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            if (isDisposed()) return@executeOnPooledThread
            val payloads = selection.acceptedFiles.mapNotNull { droppedFilePayload(it) } + extraPayloads
            val script = OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
                payloads,
                textPlain = textDrops,
                enabled = OpenCodeSettingsState.getInstance().enableChatFileDrop,
            ) ?: return@executeOnPooledThread
            val rootUrl = serverManager.getServerUrl()?.let { OpenCodeServerProtocol.buildServerRootUrl(it) }
                ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                if (!isDisposed() && OpenCodeSettingsState.getInstance().enableChatFileDrop) {
                    browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
                }
            }
        }
        return true
    }

    private fun clipboardImagePayloads(transferables: List<Transferable>): List<OpenCodeServerProtocol.DroppedFilePayload> {
        return imagePayloads(transferables, fileNamePrefix = "pasted-image", warningDescription = "pasted image")
    }

    private fun droppedImagePayloads(
        transferable: Transferable,
        droppedFiles: List<File>,
    ): List<OpenCodeServerProtocol.DroppedFilePayload> {
        if (!transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) return emptyList()
        if (!shouldUseDroppedImageFlavor(droppedFiles, openCodeProjectDirectory())) return emptyList()
        return imagePayloads(listOf(transferable), fileNamePrefix = "dropped-image", warningDescription = "dropped image")
    }

    private fun imagePayloads(
        transferables: List<Transferable>,
        fileNamePrefix: String,
        warningDescription: String,
    ): List<OpenCodeServerProtocol.DroppedFilePayload> {
        val bytes = transferables.firstNotNullOfOrNull { imagePng(it) } ?: return emptyList()
        if (bytes.size > MAX_DROPPED_FILE_BYTES) {
            showFileDropWarning(listOf("The $warningDescription is larger than ${formatFileSize(MAX_DROPPED_FILE_BYTES)}."))
            return emptyList()
        }
        val timestamp = System.currentTimeMillis()
        return listOf(
            OpenCodeServerProtocol.DroppedFilePayload(
                name = "$fileNamePrefix-$timestamp.png",
                mime = "image/png",
                lastModified = timestamp,
                base64 = Base64.getEncoder().encodeToString(bytes),
            ),
        )
    }

    private fun imagePng(transferable: Transferable): ByteArray? {
        if (!transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) return null
        val image = runCatching { transferable.getTransferData(DataFlavor.imageFlavor) as? Image }.getOrNull() ?: return null
        return encodeImageToPng(image)
    }

    private fun clipboardFiles(transferable: Transferable): List<File> {
        return runCatching { FileCopyPasteUtil.getFileList(transferable).orEmpty() }
            .getOrDefault(emptyList())
    }

    private fun clipboardTransferables(): List<Transferable> {
        return listOfNotNull(
            runCatching { CopyPasteManager.getInstance().contents }.getOrNull(),
            runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }.getOrNull(),
        ).distinctBy { System.identityHashCode(it) }
    }

    private fun selectDroppedFiles(files: List<File>): DroppedFileSelection {
        val acceptedFiles = mutableListOf<File>()
        val rejectionMessages = mutableListOf<String>()
        var totalBytes = 0L
        files.forEach { file ->
            val path = file.toPath()
            if (!Files.isRegularFile(path)) {
                rejectionMessages += "${file.name} is not a regular file."
                return@forEach
            }
            val size = runCatching { Files.size(path) }.getOrNull()
            if (size == null) {
                rejectionMessages += "${file.name} could not be read."
                return@forEach
            }
            if (size > MAX_DROPPED_FILE_BYTES) {
                rejectionMessages += "${file.name} is larger than ${formatFileSize(MAX_DROPPED_FILE_BYTES)}."
                return@forEach
            }
            if (totalBytes + size > MAX_DROPPED_FILES_TOTAL_BYTES) {
                rejectionMessages += "${file.name} would exceed the total drop limit of ${formatFileSize(MAX_DROPPED_FILES_TOTAL_BYTES)}."
                return@forEach
            }
            acceptedFiles += file
            totalBytes += size
        }
        return DroppedFileSelection(acceptedFiles, rejectionMessages)
    }

    private fun droppedTextPlainItems(files: List<File>, textPlain: String?): List<String> {
        if (files.isNotEmpty()) return emptyList()
        val text = textPlain?.takeIf { it.isNotBlank() } ?: return emptyList()
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (lines.size > 1 && lines.all { it.isOpenCodeFileDropText() }) return lines
        return listOf(text)
    }

    private fun supportsText(transferable: Transferable): Boolean {
        return transferable.transferDataFlavors.any { it.isFlavorTextType }
    }

    private fun supportsImageDrop(support: TransferHandler.TransferSupport): Boolean {
        return support.isDrop && support.isDataFlavorSupported(DataFlavor.imageFlavor)
    }

    private fun droppedTextPayload(transferable: Transferable): String? {
        return transferable.transferDataFlavors
            .filter { it.isFlavorTextType }
            .firstNotNullOfOrNull { flavor ->
                runCatching {
                    flavor.getReaderForText(transferable).use { it.readText() }
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
    }

    private fun String.isOpenCodeFileDropText(): Boolean {
        return startsWith("file:")
    }

    private fun showFileDropWarning(rejectionMessages: List<String>) {
        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup(OpenCodeServerProtocol.NOTIFICATION_GROUP_ID)
            ?: return
        val visibleMessages = rejectionMessages.take(3)
        val remaining = rejectionMessages.size - visibleMessages.size
        val suffix = if (remaining > 0) "; $remaining more skipped" else ""
        group.createNotification(
            "Some files were not added to OpenCode",
            notificationText(visibleMessages.joinToString("; ") + suffix),
            NotificationType.WARNING,
        ).notify(project)
    }

    private fun formatFileSize(bytes: Long): String {
        return "${bytes / (1024L * 1024L)} MiB"
    }

    private data class DroppedFileSelection(
        val acceptedFiles: List<File>,
        val rejectionMessages: List<String>,
    )

    private fun droppedFilePayload(file: File): OpenCodeServerProtocol.DroppedFilePayload? {
        return runCatching {
            val path = file.toPath()
            OpenCodeServerProtocol.DroppedFilePayload(
                name = file.name,
                mime = Files.probeContentType(path) ?: "application/octet-stream",
                lastModified = file.lastModified(),
                base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(path)),
            )
        }.getOrNull()
    }

    private fun notificationText(value: String): String {
        return value
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(1000)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
