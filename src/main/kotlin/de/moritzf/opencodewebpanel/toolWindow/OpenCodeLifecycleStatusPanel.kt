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
import de.moritzf.opencodewebpanel.server.formatElapsedMillis

internal class OpenCodeLifecycleStatusPanel(
    onRetry: () -> Unit,
    onViewLog: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    private val lifecycleStatusLabel = JBLabel().apply {
        border = JBUI.Borders.emptyRight(8)
    }
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
        addToCenter(lifecycleStatusLabel)
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
        lifecycleStatusLabel.toolTipText = buildString {
            append(model.progress?.explanation ?: "OpenCode server: ${model.state.displayLabel}")
            (model.progress?.elapsedMillis ?: model.elapsedMillis)?.let { append(" Elapsed ${formatElapsedMillis(it)}.") }
            model.recovery?.let { append(" Recovery reason: ${it.reason}") }
        }
        val starting = model.state == OpenCodeServerLifecycleState.STARTING ||
            model.state == OpenCodeServerLifecycleState.RESTARTING
        val retryVisible = isOpenCodeServerRetryVisible(model.state) || model.cancelled
        val startLabel = model.state == OpenCodeServerLifecycleState.STOPPED && !model.cancelled
        retryServerButton.isVisible = retryVisible
        retryServerButton.isEnabled = retryVisible
        retryServerButton.text = if (model.cancelled) "Retry" else openCodeServerRetryLabel(model.state)
        retryServerButton.icon = if (startLabel) AllIcons.Actions.Execute else AllIcons.Actions.Restart
        viewLogButton.isVisible = starting || model.state == OpenCodeServerLifecycleState.FAILED || model.cancelled
        viewLogButton.isEnabled = viewLogButton.isVisible && model.logAvailable
        viewLogButton.toolTipText = if (model.logAvailable) "Open the full server log in the editor"
            else "File logging is disabled. Recent activity is still available in the startup view."
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
