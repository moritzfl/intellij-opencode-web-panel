package de.moritzf.opencodewebpanel.features

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsState
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import java.io.File

internal const val OPEN_CODE_TOOL_WINDOW_ID = "OpenCode Web Panel"

/**
 * Adds the selected project files to the OpenCode chat as `@path` references, using the same
 * text-drop mechanism as drag-and-drop and paste.
 */
internal class OpenCodeAddFileToChatAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val texts = fileReferenceTexts(e, project)
        if (texts.isEmpty()) return
        sendToOpenCodeChat(project, texts)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        // Only yield to the add-selection action inside the editor popup itself; other menus
        // (project view, editor tabs) should offer the file action even while text is selected.
        val yieldsToSelectionAction = e.place == ActionPlaces.EDITOR_POPUP &&
            e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
        e.presentation.isEnabledAndVisible = project != null &&
            !yieldsToSelectionAction &&
            OpenCodeSettingsState.getInstance().enableChatFileDrop &&
            fileReferenceTexts(e, project).isNotEmpty()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun fileReferenceTexts(e: AnActionEvent, project: Project): List<String> {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()
            ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))
        val projectDirectory = openCodeProjectDirectory(project) ?: return emptyList()
        return files
            .filter { it.isInLocalFileSystem && !it.isDirectory }
            .mapNotNull { OpenCodeServerProtocol.localFileDropText(File(it.path), projectDirectory) }
            .distinct()
    }
}

/**
 * Adds the current editor selection to the OpenCode chat: an `@path` reference for the file plus
 * the selected lines as a fenced snippet, so the question can refer to the exact code.
 */
internal class OpenCodeAddSelectionToChatAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val texts = selectionTexts(project, editor, file)
        if (texts.isEmpty()) return
        sendToOpenCodeChat(project, texts)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null &&
            OpenCodeSettingsState.getInstance().enableChatFileDrop &&
            e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun selectionTexts(project: Project, editor: Editor, file: VirtualFile?): List<String> {
        val selection = editor.selectionModel
        val selectedText = selection.selectedText?.takeIf { it.isNotBlank() } ?: return emptyList()
        val document = editor.document
        val startLine = document.getLineNumber(selection.selectionStart) + 1
        val endLine = document.getLineNumber((selection.selectionEnd - 1).coerceAtLeast(selection.selectionStart)) + 1
        val projectDirectory = openCodeProjectDirectory(project)
        val localFile = file?.takeIf { it.isInLocalFileSystem }?.let { File(it.path) }
        val reference = localFile?.let { OpenCodeServerProtocol.localFileDropText(it, projectDirectory) }
        val displayedPath = reference?.removePrefix("file:") ?: file?.name ?: "selection"
        val fenceLanguage = file?.extension.orEmpty()
        val snippet = buildString {
            if (reference != null) {
                append(reference)
                append('\n')
            }
            append(displayedPath)
            append(" lines ")
            append(startLine)
            if (endLine != startLine) {
                append('-')
                append(endLine)
            }
            append(":\n```")
            append(fenceLanguage)
            append('\n')
            append(selectedText)
            append("\n```")
        }
        return listOf(snippet)
    }
}

private fun openCodeProjectDirectory(project: Project): String? {
    return OpenCodeProjectSettingsState.getInstance(project).effectiveProjectDirectory(project.basePath)
}

private fun sendToOpenCodeChat(project: Project, texts: List<String>) {
    OpenCodeChatInputService.getInstance(project).send(texts)
    // Bring the panel forward; if the texts were queued because the page is not ready yet, the
    // activation triggers content creation and the panel flushes the queue once loaded.
    ToolWindowManager.getInstance(project).getToolWindow(OPEN_CODE_TOOL_WINDOW_ID)?.activate(null, true)
}
