package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.opencodewebpanel.configuration.OpenCodePortMode
import de.moritzf.opencodewebpanel.configuration.OpenCodeProjectSettingsState
import de.moritzf.opencodewebpanel.configuration.OpenCodeSettingsState
import de.moritzf.opencodewebpanel.server.OpenCodeProjectRuntime
import de.moritzf.opencodewebpanel.server.OpenCodeServerLogBuffer
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.SbxLaunchSpec
import de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsConfigurable
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsConfigurable
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsTab
import java.awt.FlowLayout
import java.awt.Font
import java.nio.file.Path
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Native, theme-aware replacement for the previous HTML startup error page. Shows what failed,
 * offers direct recovery actions, and surfaces the tail of the server log so the user can see why
 * the server did not come up without digging through settings.
 */
internal class OpenCodeStartupErrorPanel(
    private val project: Project,
    private val onRetry: () -> Unit,
) {
    private val titleLabel =
        JBLabel("Could not start OpenCode").apply { font = JBFont.label().asBold().biggerOn(2f) }
    private val messageLabel =
        JBLabel().apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }
    private val retryButton =
        JButton("Retry", AllIcons.Actions.Restart).apply {
            toolTipText = "Retry starting the OpenCode server"
            addActionListener { onRetry() }
        }
    private val openSettingsButton =
        JButton("OpenCode Settings", AllIcons.General.Settings).apply {
            toolTipText = "Executables, password, proxy, and Docker Sandbox setup"
            addActionListener {
                ShowSettingsUtil.getInstance().showSettingsDialog(
                    project,
                    OpenCodeSettingsConfigurable::class.java,
                ) {
                    it.selectTab(applicationTab)
                }
            }
        }
    private val openProjectSettingsButton =
        JButton("Project Settings", AllIcons.Actions.Properties).apply {
            toolTipText = "This project's directory, runtime, sandbox, and port"
            addActionListener {
                ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, OpenCodeProjectSettingsConfigurable::class.java)
            }
        }
    private val viewLogButton =
        JButton("View Full Log", AllIcons.Actions.Show).apply {
            toolTipText = "Open the OpenCode server log in the editor"
            isEnabled = false
        }
    private val useAutoPortButton =
        JButton("Use Automatic Port").apply {
            toolTipText = "Switch the server to automatic port selection and restart"
            isVisible = false
            addActionListener {
                val settings = OpenCodeProjectSettingsState.getInstance(project)
                settings.portMode = OpenCodePortMode.AUTO.name
                settings.portImportedFromApplication = true
                val directory = settings.effectiveProjectDirectory(project.basePath)
                val spec = SbxLaunchSpec.load(directory)
                if (spec != null) SbxLaunchSpec.persist(spec.copy(hostPort = null))
                OpenCodeProjectRuntime.getInstance(project).restart()
            }
        }
    private val adoptSandboxButton =
        JButton("Adopt Sandbox").apply {
            toolTipText = "Take ownership of the existing sandbox at this workspace"
            isVisible = false
        }
    private val createNewSandboxButton =
        JButton("Create New Sandbox").apply {
            toolTipText = "Remove the unmatched sandbox and create one owned by this panel"
            isVisible = false
        }
    private val recoveryButton = JButton().apply { isVisible = false }
    private val logArea =
        JBTextArea().apply {
            isEditable = false
            lineWrap = false
            font = Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().size)
        }
    private val logTitleLabel =
        JBLabel("Recent server output:").apply {
            border = JBUI.Borders.emptyTop(12)
            isVisible = false
        }
    private val logScrollPane = JBScrollPane(logArea).apply { isVisible = false }
    private var logFile: Path? = null
    /** The application settings tab that can fix the current failure. */
    private var applicationTab = OpenCodeSettingsTab.SERVER

    // Wraps instead of clipping: the tool window is often narrower than all actions together.
    private val actionsPanel =
        JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
            isOpaque = false
            alignmentX = 0f
        }

    val component: JComponent =
        BorderLayoutPanel().apply {
            border = JBUI.Borders.empty(16)
            addToTop(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(titleLabel)
                    add(Box.createVerticalStrut(JBUI.scale(8)))
                    add(messageLabel)
                    add(Box.createVerticalStrut(JBUI.scale(8)))
                    add(actionsPanel)
                    add(logTitleLabel)
                    components.filterIsInstance<JComponent>().forEach { it.alignmentX = 0f }
                },
            )
            addToCenter(logScrollPane)
        }

    init {
        arrangeActions(applicationSettingsFirst = false)
    }

    /** The settings page that can fix the failure comes right after the recovery actions. */
    private fun arrangeActions(applicationSettingsFirst: Boolean) {
        actionsPanel.removeAll()
        val settings =
            if (applicationSettingsFirst) listOf(openSettingsButton, openProjectSettingsButton)
            else listOf(openProjectSettingsButton, openSettingsButton)
        (listOf(
                retryButton,
                recoveryButton,
                useAutoPortButton,
                adoptSandboxButton,
                createNewSandboxButton,
            ) + settings + viewLogButton)
            .forEach(actionsPanel::add)
        actionsPanel.revalidate()
    }

    init {
        viewLogButton.addActionListener { openOpenCodeServerLogInEditor(project) }
    }

    /**
     * Populates the panel for a failed start. Executable detection and log reading happen off the
     * EDT; the resulting text distinguishes a missing executable from a server that started but
     * never became available.
     */
    fun showFailure(
        executable: String,
        serverLogFile: Path?,
        offerAutomaticPort: Boolean = true,
        failureMessage: String? = null,
        failureDetail: String? = null,
        recentOutput: List<String> = emptyList(),
        onAdoptForeign: (() -> Unit)? = null,
        onCreateNewSandbox: (() -> Unit)? = null,
        recoveryAction: OpenCodeStartupRecoveryAction? = null,
        applicationSettingsTab: OpenCodeSettingsTab? = null,
    ) {
        logFile = serverLogFile
        applicationTab = applicationSettingsTab ?: OpenCodeSettingsTab.SERVER
        arrangeActions(applicationSettingsFirst = applicationSettingsTab != null)
        val cancelled = failureMessage?.contains("cancelled", ignoreCase = true) == true
        titleLabel.text = if (cancelled) "Start cancelled" else "Could not start OpenCode"
        messageLabel.text =
            (failureMessage ?: failureDetail)?.let(::messageHtml)
                ?: "OpenCode was started as \u201C$executable\u201D but the server did not become available."
        adoptSandboxButton.isVisible = onAdoptForeign != null
        createNewSandboxButton.isVisible = onCreateNewSandbox != null
        adoptSandboxButton.actionListeners.forEach { adoptSandboxButton.removeActionListener(it) }
        createNewSandboxButton.actionListeners.forEach {
            createNewSandboxButton.removeActionListener(it)
        }
        recoveryButton.actionListeners.forEach { recoveryButton.removeActionListener(it) }
        if (onAdoptForeign != null) {
            adoptSandboxButton.addActionListener { onAdoptForeign() }
        }
        if (onCreateNewSandbox != null) {
            createNewSandboxButton.addActionListener { onCreateNewSandbox() }
        }
        recoveryButton.isVisible = recoveryAction != null
        if (recoveryAction != null) {
            recoveryButton.text = recoveryAction.label
            recoveryButton.toolTipText = recoveryAction.tooltip
            recoveryButton.addActionListener { recoveryAction.run() }
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val executableFound = runCatching {
                OpenCodeServerProtocol.detectExecutablePath(executable) != null
            }
                .getOrDefault(true)
            // With server logs off the newest file on disk is from an older run; this attempt's
            // output only exists in memory.
            val logTail =
                if (OpenCodeSettingsState.getInstance().enableServerLogs) {
                    OpenCodeServerLogBuffer.tailLines(serverLogFile).ifEmpty { recentOutput }
                } else {
                    recentOutput
                }
            val settings = OpenCodeProjectSettingsState.getInstance(project)
            val directory = settings.effectiveProjectDirectory(project.basePath)
            val yamlPort = SbxLaunchSpec.load(directory)?.hostPort?.takeIf { it in 1..65535 }
            val fixedPort = yamlPort ?: settings.hostPortOrNull()
            val portConflict =
                executableFound &&
                    fixedPort != null &&
                    OpenCodeServerProtocol.logIndicatesPortConflict(logTail)
            ApplicationManager.getApplication().invokeLater {
                messageLabel.text =
                    when {
                        !failureMessage.isNullOrBlank() -> messageHtml(failureMessage)
                        !executableFound ->
                            "The OpenCode executable \u201C$executable\u201D was not found. " +
                                "Configure its location in the settings or install OpenCode."
                        portConflict ->
                            "OpenCode could not start on the fixed port $fixedPort. " +
                                "The port appears to be in use by another application."
                        !failureDetail.isNullOrBlank() -> messageHtml(failureDetail)
                        else ->
                            "OpenCode was started as \u201C$executable\u201D but the server did not become available."
                    }
                // The Host CLI executable is chosen on the application page.
                if (failureMessage.isNullOrBlank() && !executableFound) {
                    applicationTab = OpenCodeSettingsTab.SERVER
                    arrangeActions(applicationSettingsFirst = true)
                }
                useAutoPortButton.isVisible = portConflict && offerAutomaticPort
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

    private fun messageHtml(message: String): String {
        if ('\n' !in message) return message
        return "<html>" + StringUtil.escapeXmlEntities(message).replace("\n", "<br>") + "</html>"
    }
}

internal data class OpenCodeStartupRecoveryAction(
    val label: String,
    val tooltip: String,
    val run: () -> Unit,
)
