package de.moritzf.opencodewebpanel.features

import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.Alarm
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserSnippets
import de.moritzf.opencodewebpanel.browser.OpenCodeJsQuery
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackend
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import java.awt.Image
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.TransferHandler
import org.cef.handler.CefDragHandler

internal fun createOpenCodeDropPreparationExecutor() = AppExecutorUtil.createBoundedApplicationPoolExecutor(
    "OpenCode File Drop Preparation",
    1,
)

internal class OpenCodeFileDropHandler(
    private val project: Project,
    private val browser: JBCefBrowser,
    private val serverManager: OpenCodeServerBackend,
    private val openCodeProjectDirectory: () -> String?,
    private val browserDocumentRevision: () -> Long,
    private val isDisposed: () -> Boolean,
    private val parentDisposable: Disposable,
) {
    private val dropResultQuery = OpenCodeJsQuery.create(browser as JBCefBrowserBase)
    private val preparationExecutor = createOpenCodeDropPreparationExecutor()
    private val nextDropID = AtomicLong()
    private val pasteAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)
    private val pendingPastes = mutableMapOf<String, PendingPaste>()

    internal companion object {
        private const val MAX_DROPPED_FILE_BYTES = 5L * 1024L * 1024L
        private const val MAX_DROPPED_FILES_TOTAL_BYTES = 10L * 1024L * 1024L

        // Bound for clipboard images before decoding into an ARGB buffer (4 bytes per pixel):
        // covers even large retina screenshots while keeping the transient buffer ~100 MB.
        internal const val MAX_IMAGE_PIXELS = 25_000_000L

        internal fun shouldReadNonFileDropFlavors(droppedFiles: List<File>): Boolean = droppedFiles.isEmpty()

        internal fun afterDropShouldRestoreBrowserFocus(
            focusInsideBrowser: Boolean,
            focusOwnerMissing: Boolean,
        ): Boolean = focusOwnerMissing || focusInsideBrowser

        internal fun shouldUseDroppedImageFlavor(droppedFiles: List<File>, projectDirectory: String?): Boolean {
            if (droppedFiles.isEmpty()) return true
            if (droppedFiles.any { OpenCodeServerProtocol.localFileDropText(it, projectDirectory) != null }) return false
            return droppedFiles.none { Files.isRegularFile(it.toPath()) }
        }

        internal fun dispatchContextMatches(
            initialDocumentRevision: Long,
            currentDocumentRevision: Long,
            initialServerGeneration: Long,
            currentServerGeneration: Long,
            initialServerUrl: String,
            currentServerUrl: String?,
            initialDirectory: String?,
            currentDirectory: String?,
            browserUrl: String?,
        ): Boolean {
            val sameDirectory = (initialDirectory == null && currentDirectory == null) ||
                OpenCodeServerProtocol.isSameFilesystemPath(initialDirectory, currentDirectory)
            return initialDocumentRevision == currentDocumentRevision &&
                initialServerGeneration == currentServerGeneration &&
                initialServerUrl == currentServerUrl &&
                sameDirectory &&
                OpenCodeServerProtocol.isOpenCodeServerPage(initialServerUrl, browserUrl)
        }

        internal fun encodeImageToPng(image: Image): ByteArray? {
            // Reject absurd dimensions before allocating the ARGB buffer / PNG encoder input;
            // the compressed-size check downstream comes far too late for that.
            val width = image.getWidth(null)
            val height = image.getHeight(null)
            if (width <= 0 || height <= 0) return null
            if (width.toLong() * height.toLong() > MAX_IMAGE_PIXELS) return null
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

    init {
        Disposer.register(parentDisposable) {
            preparationExecutor.shutdownNow()
            pendingPastes.clear()
        }
        dropResultQuery.addHandler { payload ->
            val fields = payload.split('\n')
            ApplicationManager.getApplication().invokeLater {
                if (isDisposed()) return@invokeLater
                val id = fields.firstOrNull().orEmpty()
                val result = fields.getOrNull(1)
                if (id.startsWith("paste-")) {
                    val pending = pendingPastes.remove(id) ?: return@invokeLater
                    pasteAlarm.cancelRequest(pending.timeout)
                    if (!pending.isCurrent()) return@invokeLater
                    when (result) {
                        "native" -> nativePaste()
                        "stale" -> showFileDropWarning(listOf("The paste destination changed before the clipboard was ready."))
                        "rejected" -> showFileDropWarning(listOf("The clipboard could not be inserted into this field."))
                    }
                } else if (result != "1") {
                    showFileDropWarning(listOf("Open a conversation and close any dialog before adding files."))
                }
            }
            null
        }
    }

    /**
     * False when the drop-acknowledgement channel could not be created (see [OpenCodeJsQuery]).
     * Drops still work, but the page cannot report a rejected batch back to the IDE.
     */
    fun isResultChannelAvailable(): Boolean = dropResultQuery.isAvailable

    fun install() {
        installDragHandler()
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
                try {
                    if (!canImport(support)) return false
                    val droppedFiles = runCatching {
                        @Suppress("UNCHECKED_CAST")
                        support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                    }.getOrNull().orEmpty()
                    // File drops already carry a usable javaFileList. Reading the extra image/text
                    // flavors Finder and Project View attach happens inside the native drop
                    // callback and is enough to leave macOS AWT without a key window — typing
                    // then dies in every IDE text field until focus is fully reset.
                    val pendingImages = if (shouldReadNonFileDropFlavors(droppedFiles)) {
                        droppedImages(support.transferable)
                    } else {
                        emptyList()
                    }
                    val text = if (shouldReadNonFileDropFlavors(droppedFiles)) {
                        droppedTextPayload(support.transferable)
                    } else {
                        null
                    }
                    val fileReferenceText = text?.takeIf { it.startsWith("file:") }
                    val textToDispatch = if (pendingImages.isNotEmpty()) fileReferenceText else text
                    return dispatchDroppedData(droppedFiles, textToDispatch, pendingImages)
                } finally {
                    scheduleRestoreInputAfterExternalDrop()
                }
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

    private fun installDragHandler() {
        // macOS screenshot drags sometimes arrive as a CEF-native drag (file
        // promise) instead of going through the Swing TransferHandler. A native
        // OSR drop leaves Chromium holding focus while macOS has no key window ->
        // IDE typing dies. Returning `true` forwards the drag to the embedded
        // component's Swing TransferHandler so the existing restore path always
        // runs.
        val handler = CefDragHandler { _, _, _ -> true }
        browser.jbCefClient.addDragHandler(handler, browser.cefBrowser)
        Disposer.register(parentDisposable) {
            browser.jbCefClient.removeDragHandler(handler, browser.cefBrowser)
        }
    }

    private fun isFocusInsideBrowser(): Boolean {
        val focusOwner = currentFocusOwner() ?: return false
        return isBrowserFocusOwner(focusOwner)
    }

    private fun currentFocusOwner(): java.awt.Component? {
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        return manager.permanentFocusOwner ?: manager.focusOwner
    }

    private fun isBrowserFocusOwner(focusOwner: java.awt.Component): Boolean {
        return browser.component == focusOwner || browser.component.isAncestorOf(focusOwner)
    }

    private fun scheduleRestoreInputAfterExternalDrop() {
        ApplicationManager.getApplication().invokeLater {
            restoreInputAfterExternalDrop()
        }
    }

    private fun restoreInputAfterExternalDrop(onRestored: () -> Unit = {}) {
        if (isDisposed()) return
        // Always detach JCEF IME first. Reading image/text flavors from a screenshot
        // drop or paste can leave macOS without a key window; a still-bound IME then
        // swallows typing in every IDE editor.
        browser.cefBrowser.setFocus(false)
        val owner = currentFocusOwner()
        if (afterDropShouldRestoreBrowserFocus(owner != null && isBrowserFocusOwner(owner), owner == null)) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner()
            // Briefly route focus to a non-browser Swing ancestor before returning to the
            // browser. A pure `requestFocusInWindow()` on the browser component is a no-op
            // when JCEF's OSR component still owns Swing focus (the transition never fires),
            // which leaves Chromium's IME bound while macOS lost the key window. Moving
            // focus away once and back produces a real transition and lets JBCef re-attach
            // its IME peer.
            val awayTarget = findFocusRoundTripTarget()
            if (awayTarget != null) {
                awayTarget.requestFocusInWindow()
                ApplicationManager.getApplication().invokeLater {
                    if (isDisposed()) return@invokeLater
                    browser.component.requestFocusInWindow()
                    browser.cefBrowser.setFocus(true)
                    onRestored()
                }
            } else {
                browser.component.requestFocusInWindow()
                browser.cefBrowser.setFocus(true)
                onRestored()
            }
        } else onRestored()
    }

    /**
     * Returns a nearby non-browser Swing component that can receive focus so the browser
     * component experiences a real focus transition. Skipping the round trip leaves a
     * stale IME peer attached after a macOS screenshot drop (IDE-wide typing freeze). The
     * tool-window root panel is used when available; otherwise the top-level ancestor's
     * focus traversal root.
     */
    private fun findFocusRoundTripTarget(): java.awt.Component? {
        val parent = browser.component.parent
        if (parent != null && parent.isShowing) return parent
        return browser.component.topLevelAncestor?.takeIf { it.isShowing }
    }

    /** Single entry point for the IDE Paste action and Chromium's context-menu Paste. EDT only. */
    fun paste() {
        if (isDisposed()) return
        if (!canBridgePaste() || !pasteClipboardData()) nativePaste()
    }

    fun canBridgePaste(): Boolean = !isDisposed() && OpenCodeSettingsState.getInstance().enableChatFileDrop &&
        dropResultQuery.isAvailable && OpenCodeServerProtocol.isOpenCodeServerPage(serverManager.getServerUrl(), browser.cefBrowser.url)

    private fun nativePaste() {
        if (!isDisposed()) (browser.cefBrowser.focusedFrame ?: browser.cefBrowser.mainFrame)?.paste()
    }

    private fun pasteClipboardData(): Boolean {
        val transferables = clipboardTransferables()
        val files = transferables
            .flatMap { clipboardFiles(it) }
            .distinctBy { it.toPath().toAbsolutePath().normalize() }
        val pendingImages = if (files.isEmpty()) {
            pendingImages(transferables, fileNamePrefix = "pasted-image", warningDescription = "pasted image")
        } else {
            emptyList()
        }
        // File/image owners advertise incidental text (paths, HTML captions). Read only one payload.
        val text = if (files.isEmpty() && pendingImages.isEmpty()) {
            transferables.firstNotNullOfOrNull { droppedTextPayload(it) }
        } else null
        if (files.isEmpty() && pendingImages.isEmpty() && text == null) return false
        return dispatchDroppedData(files, text, pendingImages, clipboardPaste = true)
    }

    private fun dispatchDroppedData(
        files: List<File>,
        textPlain: String?,
        pendingImages: List<PendingDroppedImage> = emptyList(),
        clipboardPaste: Boolean = false,
    ): Boolean {
        if (files.isEmpty() && textPlain.isNullOrEmpty() && pendingImages.isEmpty()) return false
        val projectDirectory = openCodeProjectDirectory()
        val serverUrl = serverManager.getServerUrl() ?: return false
        val serverGeneration = serverManager.getServerGeneration()
        val documentRevision = browserDocumentRevision()
        val batchID = "${if (clipboardPaste) "paste" else "drop"}-${nextDropID.incrementAndGet()}"
        val rootUrl = OpenCodeServerProtocol.buildServerRootUrl(serverUrl)
        val contextIsCurrent = {
            !isDisposed() && OpenCodeSettingsState.getInstance().enableChatFileDrop && dispatchContextMatches(
                initialDocumentRevision = documentRevision,
                currentDocumentRevision = browserDocumentRevision(),
                initialServerGeneration = serverGeneration,
                currentServerGeneration = serverManager.getServerGeneration(),
                initialServerUrl = serverUrl,
                currentServerUrl = serverManager.getServerUrl(),
                initialDirectory = projectDirectory,
                currentDirectory = openCodeProjectDirectory(),
                browserUrl = browser.cefBrowser.url,
            )
        }
        if (clipboardPaste) {
            val timeout = Runnable {
                if (pendingPastes.remove(batchID) != null && contextIsCurrent()) {
                    // Never retry a timed-out acknowledgement: the page may already have inserted it.
                    showFileDropWarning(listOf("The paste could not be confirmed. Check the field before trying again."))
                }
            }
            pendingPastes[batchID] = PendingPaste(contextIsCurrent, timeout)
            pasteAlarm.addRequest(timeout, 30_000)
            browser.cefBrowser.executeJavaScript(
                OpenCodeBrowserSnippets.buildCaptureClipboardPasteScript(batchID, enabled = true)!!, rootUrl, 0,
            )
        }

        preparationExecutor.execute {
            if (isDisposed()) return@execute
            val classifiedFiles = files.map { file ->
                file to OpenCodeServerProtocol.localFileDropText(file, projectDirectory)
            }
            val fileTextDrops = classifiedFiles.mapNotNull { it.second }
            val textDrops = if (clipboardPaste) fileTextDrops else fileTextDrops.ifEmpty { droppedTextPlainItems(files, textPlain) }
            val filesToForward = classifiedFiles.filter { it.second == null }.map { it.first }
            val selection = selectDroppedFiles(filesToForward)
            val preparedImages = if (pendingImages.isNotEmpty() && shouldUseDroppedImageFlavor(files, projectDirectory)) {
                prepareDroppedImages(pendingImages)
            } else {
                PreparedDroppedImages(emptyList(), emptyList())
            }
            val warnings = selection.rejectionMessages + preparedImages.rejectionMessages
            if (warnings.isNotEmpty()) showFileDropWarning(warnings)
            val payloads = selection.acceptedFiles.mapNotNull {
                droppedFilePayload(it).also { payload ->
                    if (payload == null) showFileDropWarning(listOf("${it.name} could not be read or exceeds the file size limit."))
                }
            } + preparedImages.payloads
            if (!clipboardPaste && textDrops.isEmpty() && payloads.isEmpty()) return@execute
            ApplicationManager.getApplication().invokeLater {
                if (clipboardPaste && batchID !in pendingPastes) return@invokeLater
                if (contextIsCurrent()) {
                    val script = if (clipboardPaste) OpenCodeBrowserSnippets.buildClipboardPasteScript(
                        files = payloads,
                        text = textPlain,
                        fileReferences = textDrops,
                        batchId = batchID,
                        resultCallback = dropResultQuery.inject("batchId + '\\n' + result"),
                        enabled = true,
                    ) else OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
                        payloads,
                        textPlain = textDrops,
                        enabled = OpenCodeSettingsState.getInstance().enableChatFileDrop,
                        batchId = batchID,
                        resultCallback = dropResultQuery.inject("batchId + '\\n' + (accepted ? '1' : '0')"),
                        focusPrompt = isFocusInsideBrowser(),
                    )
                    if (script != null) {
                        val dispatch = {
                            if (contextIsCurrent()) browser.cefBrowser.executeJavaScript(script, rootUrl, 0)
                        }
                        // Keep the screenshot IME workaround, but finish its focus round trip before
                        // dispatch. Ordinary text and Linux/Wayland pastes must not reset focus.
                        if (clipboardPaste && SystemInfo.isMac && (files.isNotEmpty() || pendingImages.isNotEmpty())) {
                            restoreInputAfterExternalDrop(dispatch)
                        } else dispatch()
                    }
                } else if (!isDisposed()) {
                    pendingPastes.remove(batchID)?.let { pasteAlarm.cancelRequest(it.timeout) }
                    showFileDropWarning(listOf("The OpenCode page changed before the files were ready."))
                }
            }
        }
        return true
    }

    private fun droppedImages(transferable: Transferable): List<PendingDroppedImage> {
        if (!transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) return emptyList()
        // The file checks that decide whether this image flavor is incidental run later on the
        // pooled preparation thread; only capture the Transferable's image on the EDT here.
        return pendingImages(listOf(transferable), fileNamePrefix = "dropped-image", warningDescription = "dropped image")
    }

    private fun pendingImages(
        transferables: List<Transferable>,
        fileNamePrefix: String,
        warningDescription: String,
    ): List<PendingDroppedImage> {
        val image = transferables.firstNotNullOfOrNull(::transferableImage) ?: return emptyList()
        return listOf(PendingDroppedImage(image, fileNamePrefix, warningDescription))
    }

    private fun transferableImage(transferable: Transferable): Image? {
        return runCatching {
            if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                transferable.getTransferData(DataFlavor.imageFlavor) as? Image
            } else null
        }.getOrNull()
    }

    private fun prepareDroppedImages(images: List<PendingDroppedImage>): PreparedDroppedImages {
        val payloads = mutableListOf<OpenCodeServerProtocol.DroppedFilePayload>()
        val rejectionMessages = mutableListOf<String>()
        for (pending in images) {
            val bytes = encodeImageToPng(pending.image)
            if (bytes == null) {
                rejectionMessages += "The ${pending.warningDescription} could not be read."
                continue
            }
            if (bytes.size > MAX_DROPPED_FILE_BYTES) {
                rejectionMessages += "The ${pending.warningDescription} is larger than ${formatFileSize(MAX_DROPPED_FILE_BYTES)}."
                continue
            }
            val timestamp = System.currentTimeMillis()
            payloads += OpenCodeServerProtocol.DroppedFilePayload(
                name = "${pending.fileNamePrefix}-$timestamp.png",
                mime = "image/png",
                lastModified = timestamp,
                base64 = Base64.getEncoder().encodeToString(bytes),
            )
        }
        return PreparedDroppedImages(payloads, rejectionMessages)
    }

    private fun clipboardFiles(transferable: Transferable): List<File> {
        return runCatching { FileCopyPasteUtil.getFileList(transferable).orEmpty() }
            .getOrDefault(emptyList())
    }

    private fun clipboardTransferables(): List<Transferable> {
        val ideClipboard = runCatching { CopyPasteManager.getInstance().contents }.getOrNull()
        if (ideClipboard != null) return listOf(ideClipboard)
        return listOfNotNull(runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }.getOrNull())
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
        if (lines.size > 1 && lines.all { it.startsWith("file:") }) return lines
        return listOf(text)
    }

    private fun supportsText(transferable: Transferable): Boolean {
        return OpenCodeClipboardText.supports(transferable)
    }

    private fun supportsImageDrop(support: TransferHandler.TransferSupport): Boolean {
        return support.isDrop && support.isDataFlavorSupported(DataFlavor.imageFlavor)
    }

    private fun droppedTextPayload(transferable: Transferable): String? {
        return OpenCodeClipboardText.read(transferable)
    }

    private fun showFileDropWarning(rejectionMessages: List<String>) {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater {
                if (!isDisposed()) showFileDropWarning(rejectionMessages)
            }
            return
        }
        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup(OpenCodeServerProtocol.NOTIFICATION_GROUP_ID)
            ?: return
        val visibleMessages = rejectionMessages.take(3)
        val remaining = rejectionMessages.size - visibleMessages.size
        val suffix = if (remaining > 0) "; $remaining more skipped" else ""
        group.createNotification(
            "Could not add clipboard or dropped content to OpenCode",
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

    private data class PendingPaste(val isCurrent: () -> Boolean, val timeout: Runnable)

    private data class PendingDroppedImage(
        val image: Image,
        val fileNamePrefix: String,
        val warningDescription: String,
    )

    private data class PreparedDroppedImages(
        val payloads: List<OpenCodeServerProtocol.DroppedFilePayload>,
        val rejectionMessages: List<String>,
    )

    private fun droppedFilePayload(file: File): OpenCodeServerProtocol.DroppedFilePayload? {
        return runCatching {
            val path = file.toPath()
            // Re-check the limit while reading: the file may have grown (e.g. an active log
            // file) between the size pre-check in selectDroppedFiles and this read.
            val bytes = Files.newInputStream(path).use { stream ->
                stream.readNBytes(MAX_DROPPED_FILE_BYTES.toInt() + 1)
            }
            if (bytes.size > MAX_DROPPED_FILE_BYTES) return null
            OpenCodeServerProtocol.DroppedFilePayload(
                name = file.name,
                mime = Files.probeContentType(path) ?: "application/octet-stream",
                lastModified = file.lastModified(),
                base64 = Base64.getEncoder().encodeToString(bytes),
            )
        }.getOrNull()
    }
}
