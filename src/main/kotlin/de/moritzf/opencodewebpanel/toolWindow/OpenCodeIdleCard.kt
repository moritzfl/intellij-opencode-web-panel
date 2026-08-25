package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

internal class OpenCodeIdleCard(onStart: () -> Unit) {
    private val titleLabel = JBLabel("OpenCode is stopped").apply {
        font = JBFont.label().asBold().biggerOn(2f)
        alignmentX = 0f
    }
    private val messageLabel = JBLabel("Start the server to open this project.").apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        alignmentX = 0f
    }
    private val startButton = JButton("Start", AllIcons.Actions.Execute).apply {
        alignmentX = 0f
        addActionListener { onStart() }
    }

    val component = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(16)
        add(
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(titleLabel)
                add(Box.createVerticalStrut(JBUI.scale(8)))
                add(messageLabel)
                add(Box.createVerticalStrut(JBUI.scale(12)))
                add(startButton)
            },
            BorderLayout.NORTH,
        )
    }

    fun show(state: OpenCodeServerLifecycleState) {
        val stopped = state == OpenCodeServerLifecycleState.STOPPED
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
