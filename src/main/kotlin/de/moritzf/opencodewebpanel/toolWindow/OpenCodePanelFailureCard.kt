package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Shown instead of the embedded browser when JCEF could not create the panel at all — on Windows
 * the out-of-process CEF server occasionally refuses a fresh browser or message router. Without
 * this card the tool window would stay completely empty until the IDE is restarted, because
 * `createToolWindowContent` runs only once.
 */
internal class OpenCodePanelFailureCard(onRetry: () -> Unit) {
    private val iconLabel = JBLabel(AllIcons.General.Warning).apply {
        alignmentX = Component.CENTER_ALIGNMENT
        horizontalAlignment = SwingConstants.CENTER
    }
    private val titleLabel = JBLabel("The OpenCode panel could not be opened").apply {
        font = JBFont.label().asBold().biggerOn(3f)
        alignmentX = Component.CENTER_ALIGNMENT
        horizontalAlignment = SwingConstants.CENTER
    }
    private val messageLabel = JBLabel("The embedded browser failed to start. Try again.").apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        alignmentX = Component.CENTER_ALIGNMENT
        horizontalAlignment = SwingConstants.CENTER
    }
    private val retryButton = JButton("Retry", AllIcons.Actions.Refresh).apply {
        alignmentX = Component.CENTER_ALIGNMENT
        addActionListener { onRetry() }
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
                add(retryButton)
            },
            GridBagConstraints(),
        )
    }
}
