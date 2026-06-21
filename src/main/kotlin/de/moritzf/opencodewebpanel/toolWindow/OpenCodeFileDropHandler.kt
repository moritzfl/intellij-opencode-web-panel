package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.swing.JComponent
import javax.swing.TransferHandler

internal class OpenCodeFileDropHandler(
    private val project: Project,
    private val browser: JBCefBrowser,
    private val serverManager: SharedOpenCodeServerManager,
    private val isDisposed: () -> Boolean,
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
                if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                if (support.isDrop) support.dropAction = COPY
                return true
            }

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                val droppedFiles = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                }.getOrNull().orEmpty()
                return dispatchDroppedFiles(droppedFiles)
            }
        }
        installTransferHandler(browser.component, handler)
        (browser.browserComponent as? JComponent)?.let { installTransferHandler(it, handler) }
    }

    private fun installTransferHandler(component: JComponent, handler: TransferHandler) {
        component.transferHandler = handler
        component.components
            .filterIsInstance<JComponent>()
            .forEach { installTransferHandler(it, handler) }
    }

    private fun dispatchDroppedFiles(files: List<File>): Boolean {
        val selection = selectDroppedFiles(files)
        if (selection.rejectionMessages.isNotEmpty()) {
            showFileDropWarning(selection.rejectionMessages)
        }
        if (selection.acceptedFiles.isEmpty()) return selection.rejectionMessages.isNotEmpty()

        ApplicationManager.getApplication().executeOnPooledThread {
            if (isDisposed()) return@executeOnPooledThread
            val payloads = selection.acceptedFiles.mapNotNull { droppedFilePayload(it) }
            val script = OpenCodeServerProtocol.buildDispatchDroppedFilesScript(
                payloads,
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
