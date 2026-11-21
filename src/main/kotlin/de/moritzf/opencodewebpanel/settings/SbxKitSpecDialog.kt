package de.moritzf.opencodewebpanel.settings

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.EditorTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import de.moritzf.opencodewebpanel.server.SbxCli
import de.moritzf.opencodewebpanel.server.SbxLaunchSpec
import java.awt.event.ActionEvent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent

/** Reads and writes a local kit's descriptor the way the IDE's own editors would. */
internal object SbxKitSpecFile {
    /** Text as an editor shows it: `\n` lines, and unsaved edits of an open editor tab win. */
    fun read(path: Path): String {
        val open = cachedDocument(path)?.text
        return open ?: StringUtil.convertLineSeparators(Files.readString(path))
    }

    fun write(project: Project?, path: Path, text: String) {
        val document = cachedDocument(path)
        if (document != null) {
            // An open editor tab holds this file: change its document, or the tab would report
            // a conflict between its memory and the disk.
            WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
            FileDocumentManager.getInstance().saveDocument(document)
            return
        }
        val windows =
            Files.isRegularFile(path) &&
                Files.readString(path, StandardCharsets.UTF_8).contains("\r\n")
        Files.writeString(
            path,
            if (windows) StringUtil.convertLineSeparators(text, "\r\n") else text,
            StandardCharsets.UTF_8,
        )
        SbxLaunchSpec.refreshIdePaths(listOf(path), recursive = false)
    }

    /** sbx refuses to combine Docker's v3 OpenCode kits with v1/v2 mixins. */
    fun schemaProblem(text: String): String? =
        if (SCHEMA_V3.containsMatchIn(text)) null
        else "Docker's OpenCode kits only combine with kits of schemaVersion: \"3\"."

    private fun cachedDocument(path: Path) = runCatching {
        LocalFileSystem.getInstance().findFileByNioFile(path)?.let {
            FileDocumentManager.getInstance().getCachedDocument(it)
        }
    }
        .getOrNull()

    private val SCHEMA_V3 = Regex("""(?m)^schemaVersion:\s*["']?3["']?\s*(#.*)?$""")
}

/**
 * Edits a kit's `spec.yaml` without leaving Settings: the Settings dialog is modal, so the file
 * cannot be edited in an editor tab before Apply installs the kit.
 */
internal class SbxKitSpecDialog(
    private val project: Project,
    parent: JComponent,
    private val specFile: Path,
    /** The file as the kit table names it, e.g. `opencode-sbx/opencode-network-kit/spec.yaml`. */
    private val shownPath: String,
    /** Offered as Restore Template for the kit the plugin writes itself. */
    private val template: String? = null,
) : DialogWrapper(parent, true) {
    private val editor =
        EditorTextField(
                EditorFactory.getInstance().createDocument(SbxKitSpecFile.read(specFile)),
                project,
                fileType(specFile),
                false,
                false,
            )
            .apply {
                setFontInheritedFromLAF(false)
                addSettingsProvider { editor ->
                    editor.setVerticalScrollbarVisible(true)
                    editor.setHorizontalScrollbarVisible(true)
                    editor.settings.isLineNumbersShown = true
                    editor.settings.isUseSoftWraps = false
                }
                preferredSize = JBUI.size(760, 460)
            }

    init {
        title = "Edit Kit: ${specFile.parent?.fileName ?: specFile.fileName}"
        setOKButtonText("Save")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            comment(StringUtil.escapeXmlEntities(shownPath)).applyToComponent {
                toolTipText = SbxCli.posixPath(specFile.toString())
            }
        }
        row { cell(editor).align(Align.FILL) }.resizableRow()
        row {
            comment(
                "Lines starting with # are off. A sandbox that already installed this kit keeps " +
                    "its copy until Reset Sandbox.",
            )
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = editor

    override fun getDimensionServiceKey(): String = "OpenCodeWebPanel.SbxKitSpecDialog"

    override fun createLeftSideActions(): Array<Action> =
        template?.let { text ->
            arrayOf(
                object : AbstractAction("Restore Template") {
                    override fun actionPerformed(event: ActionEvent) {
                        editor.text = StringUtil.convertLineSeparators(text)
                    }
                },
            )
        } ?: emptyArray()

    override fun doValidate(): ValidationInfo? =
        SbxKitSpecFile.schemaProblem(editor.text)?.let {
            ValidationInfo(it, editor).asWarning().withOKEnabled()
        }

    override fun doOKAction() {
        runCatching { SbxKitSpecFile.write(project, specFile, editor.text) }
            .onFailure { error ->
                setErrorText(
                    "Could not save ${specFile.fileName}: ${error.message ?: error.javaClass.simpleName}",
                    editor,
                )
                return
            }
        super.doOKAction()
    }

    private companion object {
        fun fileType(path: Path): FileType =
            FileTypeManager.getInstance()
                .getFileTypeByFileName(path.fileName.toString())
                .takeUnless { it.isBinary } ?: PlainTextFileType.INSTANCE
    }
}
