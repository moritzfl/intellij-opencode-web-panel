package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import de.moritzf.opencodewebpanel.server.OpenCodeLifecycleStripModel
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.formatOpenCodeLifecycleStrip
import de.moritzf.opencodewebpanel.server.isOpenCodeLifecycleStripVisible
import de.moritzf.opencodewebpanel.server.isOpenCodeServerRetryVisible
import de.moritzf.opencodewebpanel.server.openCodeServerRetryLabel

internal class OpenCodeLifecycleStatusPanel(
    onRetry: () -> Unit,
    onViewLog: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    private val lifecycleStatusLabel = JBLabel()
    private val retryServerButton = JButton("Retry", AllIcons.Actions.Restart).apply {
        isVisible = false
        addActionListener { onRetry() }
    }
    private val viewLogButton = JButton("View log", AllIcons.Actions.Show).apply {
        isVisible = false
        addActionListener { onViewLog() }
    }
    private val cancelButton = JButton("Cancel", AllIcons.Actions.Cancel).apply {
        isVisible = false
        addActionListener { onCancel() }
    }
    private val buttons = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(cancelButton)
        add(Box.createHorizontalStrut(JBUI.scale(4)))
        add(viewLogButton)
        add(Box.createHorizontalStrut(JBUI.scale(4)))
        add(retryServerButton)
    }

    val component = BorderLayoutPanel().apply {
        isOpaque = false
        isVisible = false
        border = JBUI.Borders.empty(4, 8)
        addToLeft(lifecycleStatusLabel)
        addToRight(buttons)
    }

    fun update(
        state: OpenCodeServerLifecycleState,
        pageOpening: Boolean = false,
        cancelled: Boolean = false,
        stage: String? = null,
        elapsedMillis: Long? = null,
        recoveryReason: String? = null,
        recoveryAtMillis: Long? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val model = OpenCodeLifecycleStripModel(
            state = state,
            pageOpening = pageOpening,
            cancelled = cancelled,
            stage = stage,
            elapsedMillis = elapsedMillis,
            recovery = if (recoveryReason.isNullOrBlank() || recoveryAtMillis == null) {
                null
            } else {
                de.moritzf.opencodewebpanel.server.OpenCodeRecoveryNotice(recoveryReason, recoveryAtMillis)
            },
        )
        return update(model, nowMillis)
    }

    fun update(model: OpenCodeLifecycleStripModel, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val visibleBefore = component.isVisible
        val retryBefore = retryServerButton.isVisible
        val logBefore = viewLogButton.isVisible
        val cancelBefore = cancelButton.isVisible
        lifecycleStatusLabel.text = formatOpenCodeLifecycleStrip(model, nowMillis)
        lifecycleStatusLabel.toolTipText = lifecycleStatusLabel.text.replace(Regex("<[^>]+>"), "")
        val starting = model.state == OpenCodeServerLifecycleState.STARTING ||
            model.state == OpenCodeServerLifecycleState.RESTARTING
        val retryVisible = isOpenCodeServerRetryVisible(model.state) || model.cancelled
        val startLabel = model.state == OpenCodeServerLifecycleState.STOPPED && !model.cancelled
        retryServerButton.isVisible = retryVisible
        retryServerButton.isEnabled = retryVisible
        retryServerButton.text = if (model.cancelled) "Retry" else openCodeServerRetryLabel(model.state)
        retryServerButton.icon = if (startLabel) AllIcons.Actions.Execute else AllIcons.Actions.Restart
        viewLogButton.isVisible = starting || model.state == OpenCodeServerLifecycleState.FAILED || model.cancelled
        viewLogButton.isEnabled = viewLogButton.isVisible
        cancelButton.isVisible = starting && !model.cancelled
        cancelButton.isEnabled = cancelButton.isVisible
        component.isVisible = isOpenCodeLifecycleStripVisible(model)
        return component.isVisible != visibleBefore ||
            retryServerButton.isVisible != retryBefore ||
            viewLogButton.isVisible != logBefore ||
            cancelButton.isVisible != cancelBefore
    }

    fun setRetryEnabled(enabled: Boolean) {
        retryServerButton.isEnabled = enabled
    }
}
