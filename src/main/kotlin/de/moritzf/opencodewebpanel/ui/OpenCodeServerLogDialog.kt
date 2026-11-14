package de.moritzf.opencodewebpanel.ui

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.OpenCodeServerLogBuffer
import java.awt.Font
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Action
import javax.swing.JComponent

/**
 * The end of a server's log, shown from Settings: an editor tab would open behind the modal dialog.
 * Without a log file (logging off, or nothing written yet) it shows the output of the current
 * start, which is kept in memory.
 */
internal class OpenCodeServerLogDialog
private constructor(
    project: Project?,
    private val file: Path?,
    private val lines: List<String>,
) : DialogWrapper(project, true) {
    init {
        title = "OpenCode Server Log"
        setOKButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val area =
            JBTextArea(lines.joinToString("\n")).apply {
                isEditable = false
                font = Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().size)
                caretPosition = document.length
            }
        return panel {
            row {
                cell(JBScrollPane(area).apply { preferredSize = JBUI.size(760, 420) })
                    .align(Align.FILL)
            }
                .resizableRow()
            row {
                if (file != null) {
                    comment("The last ${lines.size} lines of $file")
                    if (RevealFileAction.isSupported()) {
                        link(RevealFileAction.getActionName()) { RevealFileAction.openFile(file) }
                    }
                } else {
                    comment(
                        "Output of the current start. Write server logs to disk (OpenCode Settings | Server) keeps it after OpenCode stops.",
                    )
                }
            }
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    companion object {
        private const val MAX_LINES = 400

        /** Cheap check for enabling a button: a non-empty log file or output of this start. */
        fun hasOutput(backend: OpenCodeServerBackend): Boolean {
            val file = backend.getServerLogFile()
            val fileHasContent =
                file != null && runCatching { Files.size(file) > 0 }.getOrDefault(false)
            return fileHasContent ||
                backend.getStartupProgress()?.recentOutput?.isNotEmpty() == true
        }

        /** What [show] would display; empty means there is nothing to show yet. */
        private fun linesOf(backend: OpenCodeServerBackend): Pair<Path?, List<String>> {
            val file = backend.getServerLogFile()
            val fromFile = OpenCodeServerLogBuffer.tailLines(file, MAX_LINES)
            if (fromFile.isNotEmpty()) return file to fromFile
            return null to backend.getStartupProgress()?.recentOutput.orEmpty()
        }

        /** False when there is nothing to show yet. */
        fun show(project: Project?, backend: OpenCodeServerBackend): Boolean {
            val (file, lines) = linesOf(backend)
            if (lines.isEmpty()) return false
            OpenCodeServerLogDialog(project, file, lines).show()
            return true
        }
    }
}
