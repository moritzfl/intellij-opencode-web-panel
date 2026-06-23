package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import javax.swing.JButton

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
        retryServerButton.isEnabled = state == OpenCodeServerLifecycleState.FAILED
        component.isVisible = isOpenCodeServerLifecycleStatusVisible(state)
    }

    fun showProgress(message: String) {
        lifecycleStatusLabel.text = "<html><span style=\"color: #FFC107\">&#9679;</span>&nbsp;${escapeHtml(message)}</html>"
        lifecycleStatusLabel.toolTipText = message
        retryServerButton.isVisible = false
        component.isVisible = true
    }

    fun setRetryEnabled(enabled: Boolean) {
        retryServerButton.isEnabled = enabled
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
