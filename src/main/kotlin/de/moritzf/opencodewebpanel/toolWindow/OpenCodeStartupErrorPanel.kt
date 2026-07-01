package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.opencodewebpanel.settings.OpenCodePortMode
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsConfigurable
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsListener
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import java.awt.Font
import java.nio.file.Path
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Native, theme-aware replacement for the previous HTML startup error page. Shows what failed,
 * offers direct recovery actions, and surfaces the tail of the server log so the user can see
 * why the server did not come up without digging through settings.
 */
internal class OpenCodeStartupErrorPanel(
    private val project: Project,
    private val onRetry: () -> Unit,
) {
    private val titleLabel = JBLabel("Failed to start the OpenCode server").apply {
        font = JBFont.label().asBold().biggerOn(2f)
    }
    private val messageLabel = JBLabel().apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }
    private val retryButton = JButton("Retry", AllIcons.Actions.Restart).apply {
        toolTipText = "Retry starting the OpenCode server"
        addActionListener { onRetry() }
    }
    private val openSettingsButton = JButton("Open Settings", AllIcons.General.Settings).apply {
        toolTipText = "Open the OpenCode Web Panel settings"
        addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, OpenCodeSettingsConfigurable::class.java)
        }
    }
    private val viewLogButton = JButton("View Full Log", AllIcons.Actions.Show).apply {
        toolTipText = "Open the OpenCode server log in the editor"
        isEnabled = false
    }
    private val useAutoPortButton = JButton("Use Automatic Port").apply {
        toolTipText = "Switch the server to automatic port selection and restart"
        isVisible = false
        addActionListener {
            OpenCodeSettingsState.getInstance().portMode = OpenCodePortMode.AUTO.name
            ApplicationManager.getApplication().messageBus
                .syncPublisher(OpenCodeSettingsListener.TOPIC)
                .serverRestartRequested()
        }
    }
    private val logArea = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().size)
    }
    private val logTitleLabel = JBLabel("Recent server output:").apply {
        border = JBUI.Borders.emptyTop(12)
        isVisible = false
    }
    private val logScrollPane = JBScrollPane(logArea).apply {
        isVisible = false
    }
    private var logFile: Path? = null

    val component: JComponent = BorderLayoutPanel().apply {
        border = JBUI.Borders.empty(16)
        addToTop(
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(titleLabel)
                add(Box.createVerticalStrut(JBUI.scale(8)))
                add(messageLabel)
                add(Box.createVerticalStrut(JBUI.scale(12)))
                add(
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.X_AXIS)
                        isOpaque = false
                        alignmentX = 0f
                        add(retryButton)
                        add(Box.createHorizontalStrut(JBUI.scale(8)))
                        add(useAutoPortButton)
                        add(Box.createHorizontalStrut(JBUI.scale(8)))
                        add(openSettingsButton)
                        add(Box.createHorizontalStrut(JBUI.scale(8)))
                        add(viewLogButton)
                    },
                )
                add(logTitleLabel)
                components.filterIsInstance<JComponent>().forEach { it.alignmentX = 0f }
            },
        )
        addToCenter(logScrollPane)
    }

    init {
        viewLogButton.addActionListener { openOpenCodeServerLogInEditor(project) }
    }

    /**
     * Populates the panel for a failed start. Executable detection and log reading happen off the
     * EDT; the resulting text distinguishes a missing executable from a server that started but
     * never became available.
     */
    fun showFailure(executable: String, serverLogFile: Path?) {
        logFile = serverLogFile
        messageLabel.text = "OpenCode was started as \u201C$executable\u201D but the server did not become available."
        ApplicationManager.getApplication().executeOnPooledThread {
            val executableFound = runCatching { OpenCodeServerProtocol.detectExecutablePath(executable) != null }
                .getOrDefault(true)
            val logTail = OpenCodeServerLogBuffer.tailLines(serverLogFile)
            val settings = OpenCodeSettingsState.getInstance()
            val fixedPort = if (settings.portModeValue() == OpenCodePortMode.FIXED) {
                OpenCodeSettingsState.sanitizePort(settings.fixedPort)
            } else {
                null
            }
            val portConflict = executableFound && fixedPort != null && OpenCodeServerProtocol.logIndicatesPortConflict(logTail)
            ApplicationManager.getApplication().invokeLater {
                messageLabel.text = when {
                    !executableFound ->
                        "The OpenCode executable \u201C$executable\u201D was not found. " +
                            "Configure its location in the settings or install OpenCode."
                    portConflict ->
                        "OpenCode could not start on the fixed port $fixedPort. " +
                            "The port appears to be in use by another application."
                    else -> "OpenCode was started as \u201C$executable\u201D but the server did not become available."
                }
                useAutoPortButton.isVisible = portConflict
                viewLogButton.isEnabled = serverLogFile != null
                val hasLog = logTail.isNotEmpty()
                logTitleLabel.isVisible = hasLog
                logScrollPane.isVisible = hasLog
                logArea.text = logTail.joinToString("\n")
                logArea.caretPosition = logArea.document.length
                component.revalidate()
                component.repaint()
            }
        }
    }
}
