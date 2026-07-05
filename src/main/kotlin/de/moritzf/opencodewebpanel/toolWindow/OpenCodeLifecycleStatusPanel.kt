package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import javax.swing.JButton
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.formatOpenCodeServerLifecycleStatusText
import de.moritzf.opencodewebpanel.server.isOpenCodeServerLifecycleStatusVisible
import de.moritzf.opencodewebpanel.server.isOpenCodeServerRetryVisible
import de.moritzf.opencodewebpanel.server.openCodeServerRetryLabel

internal class OpenCodeLifecycleStatusPanel(onRetry: () -> Unit) {
    private val lifecycleStatusLabel = JBLabel()
    private val retryServerButton = JButton("Retry", AllIcons.Actions.Restart).apply {
        isVisible = false
        toolTipText = "Retry starting the OpenCode server"
        accessibleContext.accessibleName = "Retry starting OpenCode server"
        addActionListener { onRetry() }
    }

    val component = BorderLayoutPanel().apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)
        addToLeft(lifecycleStatusLabel)
        addToRight(retryServerButton)
    }

    fun update(state: OpenCodeServerLifecycleState) {
        lifecycleStatusLabel.text = formatOpenCodeServerLifecycleStatusText(state)
        lifecycleStatusLabel.toolTipText = "OpenCode server is ${state.displayLabel.lowercase()}"
        retryServerButton.isVisible = isOpenCodeServerRetryVisible(state)
        retryServerButton.isEnabled = isOpenCodeServerRetryVisible(state)
        retryServerButton.text = openCodeServerRetryLabel(state)
        component.isVisible = isOpenCodeServerLifecycleStatusVisible(state)
    }

    fun setRetryEnabled(enabled: Boolean) {
        retryServerButton.isEnabled = enabled
    }
}
