package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

internal class OpenCodeIdleCard(onStart: () -> Unit) {
    private val iconLabel = JBLabel(AllIcons.Actions.Suspend).apply {
        alignmentX = java.awt.Component.CENTER_ALIGNMENT
        horizontalAlignment = SwingConstants.CENTER
    }
    private val titleLabel = JBLabel("OpenCode is stopped").apply {
        font = JBFont.label().asBold().biggerOn(3f)
        alignmentX = java.awt.Component.CENTER_ALIGNMENT
        horizontalAlignment = SwingConstants.CENTER
    }
    private val messageLabel = JBLabel("Start the server to open this project.").apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        alignmentX = java.awt.Component.CENTER_ALIGNMENT
        horizontalAlignment = SwingConstants.CENTER
    }
    private val startButton = JButton("Start", AllIcons.Actions.Execute).apply {
        alignmentX = java.awt.Component.CENTER_ALIGNMENT
        addActionListener { onStart() }
    }

    val component = JPanel(GridBagLayout()).apply {
        isOpaque = true
        add(
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(iconLabel)
                add(Box.createVerticalStrut(JBUI.scale(16)))
                add(titleLabel)
                add(Box.createVerticalStrut(JBUI.scale(8)))
                add(messageLabel)
                add(Box.createVerticalStrut(JBUI.scale(16)))
                add(startButton)
            },
            GridBagConstraints(),
        )
    }

    fun show(state: OpenCodeServerLifecycleState) {
        val stopped = state == OpenCodeServerLifecycleState.STOPPED
        iconLabel.icon = if (stopped) AllIcons.Actions.Suspend else AllIcons.Actions.Refresh
        titleLabel.text = if (stopped) "OpenCode is stopped" else "Restarting OpenCode…"
        messageLabel.text = if (stopped) {
            "Start the server to open this project."
        } else {
            "The panel will reopen when the server is ready."
        }
        startButton.isVisible = stopped
        startButton.isEnabled = stopped
    }
}
