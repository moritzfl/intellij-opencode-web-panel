package de.moritzf.opencodewebpanel.features

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.LineCol
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.util.diff.Diff
import com.intellij.util.diff.FilesTooBigForDiffException
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.OpenCodeUnifiedDiff

internal fun createOpenCodeDiffRequest(
    project: Project,
    diff: OpenCodeServerProtocol.SnapshotFileDiff,
    highlightFile: VirtualFile?,
): SimpleDiffRequest? {
    val sides = OpenCodeUnifiedDiff.sides(diff.patch) ?: return null
    val name = diff.file?.takeIf { it.isNotBlank() } ?: "diff"
    val factory = DiffContentFactory.getInstance()
    val file = highlightFile?.takeIf { it.isValid && !it.isDirectory }
    val after = if (file != null) {
        factory.create(project, sides.after, file)
    } else {
        val fileName = name.substringAfterLast('/').substringAfterLast('\\')
        factory.create(project, sides.after, FileTypeManager.getInstance().getFileTypeByFileName(fileName))
    }
    val before = factory.create(project, sides.before, after)
    return SimpleDiffRequest(
        name,
        if (file != null) OpenCodeDiffContent(project, before, file) else before,
        if (file != null) OpenCodeDiffContent(project, after, file) else after,
        "Before",
        "After",
    )
}

/** Keeps snapshot text while making Jump to Source select the workspace's text editor. */
private class OpenCodeDiffContent(
    private val project: Project,
    content: DocumentContent,
    private val file: VirtualFile,
) : DocumentContent by content {
    private fun canNavigate(): Boolean = !project.isDisposed && file.isValid && !file.isDirectory

    override fun getNavigatable(): Navigatable? = getNavigatable(LineCol(0))

    override fun getNavigatable(position: LineCol): Navigatable? {
        if (!canNavigate()) return null
        return object : Navigatable {
            override fun canNavigate(): Boolean = this@OpenCodeDiffContent.canNavigate()
            override fun canNavigateToSource(): Boolean = canNavigate()
            override fun navigate(requestFocus: Boolean) {
                // Translate only when F4 is invoked, against the current (possibly unsaved) document.
                val descriptor = sourceDescriptor(position) ?: return
                // Generic navigation may select Project View or an already selected preview provider.
                val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, requestFocus) ?: return
                // A navigatable preview may have consumed the descriptor before text was selected.
                descriptor.navigateIn(editor)
            }
        }
    }

    private fun sourceDescriptor(position: LineCol): OpenFileDescriptor? {
        if (!canNavigate()) return null
        val target = FileDocumentManager.getInstance().getDocument(file) ?: return null
        // Match the platform's DocumentContentBase translation: preview lines omit inter-hunk gaps.
        val line = try {
            Diff.translateLine(document.charsSequence, target.charsSequence, position.line, true)
        } catch (_: FilesTooBigForDiffException) {
            position.line
        }
        return OpenFileDescriptor(project, file, line, position.column)
    }
}
