package de.moritzf.opencodewebpanel.features

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.callback.CefFileDialogCallback
import org.cef.handler.CefDialogHandler
import java.io.File
import java.util.Vector

/**
 * Chromium's file picker is owned by the CEF window. On Windows that dialog often opens
 * behind the IDE. Handle the CEF file-dialog hook with IntelliJ's chooser instead, which
 * is owned by the IDE frame.
 */
internal class OpenCodeCefFileDialogHandler(
    private val project: Project,
    private val browser: JBCefBrowser,
    parentDisposable: Disposable,
) : CefDialogHandler {
    init {
        browser.jbCefClient.addDialogHandler(this, browser.cefBrowser)
        Disposer.register(parentDisposable) {
            browser.jbCefClient.removeDialogHandler(this, browser.cefBrowser)
        }
    }

    override fun onFileDialog(
        cefBrowser: CefBrowser?,
        mode: CefDialogHandler.FileDialogMode?,
        title: String?,
        defaultFilePath: String?,
        acceptFilters: Vector<String>?,
        acceptExtensions: Vector<String>?,
        acceptDescriptions: Vector<String>?,
        callback: CefFileDialogCallback?,
    ): Boolean {
        if (callback == null || !shouldHandle(mode)) return false
        val multiple = mode == CefDialogHandler.FileDialogMode.FILE_DIALOG_OPEN_MULTIPLE
        ApplicationManager.getApplication().invokeLater(
            {
                if (project.isDisposed) {
                    callback.Cancel()
                    return@invokeLater
                }
                try {
                    val descriptor = if (multiple) {
                        FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
                    } else {
                        FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                    }
                    title?.trim()?.takeIf { it.isNotEmpty() }?.let { descriptor.title = it }
                    val selected = FileChooser.chooseFiles(
                        descriptor,
                        browser.component,
                        project,
                        startDirectory(defaultFilePath),
                    )
                    if (selected.isEmpty()) {
                        callback.Cancel()
                    } else {
                        callback.Continue(Vector(selected.map { it.toNioPath().toAbsolutePath().toString() }))
                    }
                } catch (_: Exception) {
                    callback.Cancel()
                }
            },
            ModalityState.defaultModalityState(),
        )
        return true
    }

    private fun startDirectory(defaultFilePath: String?): VirtualFile? {
        val raw = defaultFilePath?.trim()?.takeIf { it.isNotBlank() } ?: project.basePath ?: return null
        val io = File(raw)
        val dir = if (io.isFile) io.parentFile else io
        if (dir == null || !dir.exists()) return null
        return LocalFileSystem.getInstance().findFileByIoFile(dir)
    }

    companion object {
        internal fun shouldHandle(mode: CefDialogHandler.FileDialogMode?): Boolean {
            return mode == CefDialogHandler.FileDialogMode.FILE_DIALOG_OPEN ||
                mode == CefDialogHandler.FileDialogMode.FILE_DIALOG_OPEN_MULTIPLE
        }
    }
}
