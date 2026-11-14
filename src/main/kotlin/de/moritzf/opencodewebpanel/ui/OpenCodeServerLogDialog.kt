package de.moritzf.opencodewebpanel.ui

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import de.moritzf.opencodewebpanel.configuration.OpenCodeSettingsState
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.OpenCodeServerLogBuffer
import java.awt.Font
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Action
import javax.swing.JComponent

/**
 * The end of a server's log, shown from Settings: an editor tab would open behind the modal dialog.
 * Without this server's own log file (logging off, or nothing written yet) it shows the output of
 * the current start, which is kept in memory; never the newest file in the shared log directory,
 * which can be an older run or another project's server.
 */
internal class OpenCodeServerLogDialog
private constructor(
    parent: JComponent,
    private val file: Path?,
    private val lines: List<String>,
) : DialogWrapper(parent, true) {
    init {
        title = "OpenCode Server Log"
        setOKButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val area =
            JBTextArea(lines.joinToString("\n").ifEmpty { "No output yet." }).apply {
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
                    comment(
                        StringUtil.escapeXmlEntities("The last ${lines.size} lines of $file"),
                    )
                    if (RevealFileAction.isSupported()) {
                        link(RevealFileAction.getActionName()) { RevealFileAction.openFile(file) }
                    }
                } else {
                    comment(
                        "Output of the current start. Turn on Write server logs to disk " +
                            "(OpenCode Settings, Server tab) to keep it after OpenCode stops.",
                    )
                }
            }
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    companion object {
        private const val MAX_LINES = 400

        private fun logsEnabled(): Boolean = OpenCodeSettingsState.getInstance().enableServerLogs

        /** Cheap check for enabling a button: a non-empty log file or output of this start. */
        fun hasOutput(
            backend: OpenCodeServerBackend,
            logsEnabled: Boolean = logsEnabled(),
        ): Boolean {
            val file = backend.getCurrentServerLogFile()?.takeIf { logsEnabled }
            val fileHasContent =
                file != null && runCatching { Files.size(file) > 0 }.getOrDefault(false)
            return fileHasContent ||
                backend.getStartupProgress()?.recentOutput?.isNotEmpty() == true
        }

        /**
         * What [show] displays: this server's log file while logs are on, else this start's output.
         */
        internal fun linesOf(
            backend: OpenCodeServerBackend,
            logsEnabled: Boolean = logsEnabled(),
        ): Pair<Path?, List<String>> {
            val file = backend.getCurrentServerLogFile()?.takeIf { logsEnabled }
            val fromFile = OpenCodeServerLogBuffer.tailLines(file, MAX_LINES)
            if (fromFile.isNotEmpty()) return file to fromFile
            return null to backend.getStartupProgress()?.recentOutput.orEmpty()
        }

        /** Reads the log off the EDT, then opens the dialog over [parent]. */
        fun show(parent: JComponent, backend: OpenCodeServerBackend) {
            val modality = ModalityState.stateForComponent(parent)
            val application = ApplicationManager.getApplication()
            application.executeOnPooledThread {
                val (file, lines) = linesOf(backend)
                application.invokeLater(
                    { if (parent.isShowing) OpenCodeServerLogDialog(parent, file, lines).show() },
                    modality,
                )
            }
        }
    }
}
