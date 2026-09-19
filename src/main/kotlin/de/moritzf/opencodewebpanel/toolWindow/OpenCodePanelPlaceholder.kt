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

internal class OpenCodePanelPlaceholder(
    title: String,
    message: String,
    actionText: String,
    onRequest: () -> Unit,
) {
    private val titleLabel = JBLabel(title).apply {
        icon = AllIcons.General.Information
        font = JBFont.label().asBold()
        alignmentX = Component.CENTER_ALIGNMENT
        horizontalAlignment = SwingConstants.CENTER
    }
    private val messageLabel = JBLabel(message).apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        alignmentX = Component.CENTER_ALIGNMENT
        horizontalAlignment = SwingConstants.CENTER
    }
    private val requestButton = JButton(actionText).apply {
        alignmentX = Component.CENTER_ALIGNMENT
        addActionListener { onRequest() }
    }

    val component = JPanel(GridBagLayout()).apply {
        border = JBUI.Borders.empty(16)
        add(
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(titleLabel)
                add(Box.createVerticalStrut(JBUI.scale(8)))
                add(messageLabel)
                add(Box.createVerticalStrut(JBUI.scale(12)))
                add(requestButton)
            },
            GridBagConstraints(),
        )
    }
}
