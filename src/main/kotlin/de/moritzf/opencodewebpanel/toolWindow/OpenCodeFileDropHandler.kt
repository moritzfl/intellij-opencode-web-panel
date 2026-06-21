package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.swing.JComponent
import javax.swing.TransferHandler

internal class OpenCodeFileDropHandler(
    private val project: Project,
    private val browser: JBCefBrowser,
    private val serverManager: SharedOpenCodeServerManager,
    private val openCodeProjectDirectory: () -> String?,
    private val isDisposed: () -> Boolean,
    private val parentDisposable: Disposable,
) {
    private companion object {
        private const val MAX_DROPPED_FILE_BYTES = 5L * 1024L * 1024L
        private const val MAX_DROPPED_FILES_TOTAL_BYTES = 10L * 1024L * 1024L
    }

    fun install() {
        val handler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                if (!OpenCodeSettingsState.getInstance().enableChatFileDrop) return false
                if (!OpenCodeServerProtocol.isOpenCodeServerPage(serverManager.getServerUrl(), browser.cefBrowser.url)) return false
                if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) && !supportsText(support.transferable)) return false
                if (support.isDrop) support.dropAction = COPY
                return true
            }

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                val droppedFiles = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                }.getOrNull().orEmpty()
                return dispatchDroppedData(droppedFiles, droppedTextPayload(support.transferable))
            }
        }
        installTransferHandler(browser.component, handler)
        (browser.browserComponent as? JComponent)?.let { installTransferHandler(it, handler) }
        installPasteDispatcher()
    }

    private fun installTransferHandler(component: JComponent, handler: TransferHandler) {
        component.transferHandler = handler
        component.components
            .filterIsInstance<JComponent>()
            .forEach { installTransferHandler(it, handler) }
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

            val transferable = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }
                .getOrNull()
                ?: return@KeyEventDispatcher false
            val files = clipboardFiles(transferable)
            val text = droppedTextPayload(transferable)
            if (files.isEmpty() && text?.startsWith("file:") != true) return@KeyEventDispatcher false
            dispatchDroppedData(files, text)
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

    private fun dispatchDroppedData(files: List<File>, textPlain: String?): Boolean {
        val projectDirectory = openCodeProjectDirectory()
        val fileTextDrops = files.mapNotNull { file -> OpenCodeServerProtocol.localFileDropText(file, projectDirectory) }
        val textDrops = fileTextDrops.ifEmpty { droppedTextPlainItems(files, textPlain) }
        val filesToForward = files.filter { file -> OpenCodeServerProtocol.localFileDropText(file, projectDirectory) == null }
        val selection = selectDroppedFiles(filesToForward)
        if (selection.rejectionMessages.isNotEmpty()) {
            showFileDropWarning(selection.rejectionMessages)
        }
        if (textDrops.isEmpty() && selection.acceptedFiles.isEmpty()) {
            return selection.rejectionMessages.isNotEmpty()
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            if (isDisposed()) return@executeOnPooledThread
            val payloads = selection.acceptedFiles.mapNotNull { droppedFilePayload(it) }
            val script = OpenCodeServerProtocol.buildDispatchDroppedFilesScript(
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

    private fun clipboardFiles(transferable: Transferable): List<File> {
        if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
        }.getOrNull().orEmpty()
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
