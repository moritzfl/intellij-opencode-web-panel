package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.OpenCodeStartupProgress
import de.moritzf.opencodewebpanel.server.formatElapsedMillis
import de.moritzf.opencodewebpanel.server.formatStartupActivity
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar

/** Native startup view: explain the current work first, with a bounded live activity tail. */
internal class OpenCodeIdleCard(
    onCancel: () -> Unit = {},
    onViewLog: () -> Unit = {},
    onStart: () -> Unit,
) {
    private val titleLabel = JBLabel("OpenCode is stopped").apply {
        font = JBFont.label().asBold().biggerOn(3f)
    }
    private val stageLabel = JBLabel().apply { font = JBFont.label().asBold() }
    private val explanation = JBTextArea().apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        rows = 3
        font = JBFont.label()
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }
    private val progressBar = JProgressBar().apply { isIndeterminate = true }
    private val activityLabel = JBTextArea().apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        rows = 2
        font = JBFont.label()
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }
    private val logArea = JBTextArea(7, 1).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBFont.small().size)
    }
    private val logPane = JBScrollPane(logArea).apply {
        minimumSize = Dimension(0, JBUI.scale(70))
    }
    private val logTitle = JBLabel("Recent activity").apply { font = JBFont.label().asBold() }
    private val elapsedLabel = JBLabel().apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }
    private val startButton = JButton("Start", AllIcons.Actions.Execute).apply {
        addActionListener { onStart() }
    }
    private val cancelButton = JButton("Cancel", AllIcons.Actions.Cancel).apply {
        addActionListener { onCancel() }
    }
    private val logButton = JButton("Open full log", AllIcons.Actions.Show).apply {
        addActionListener { onViewLog() }
    }
    private val column = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        maximumSize = Dimension(JBUI.scale(700), Int.MAX_VALUE)
        minimumSize = Dimension(0, 0)
        fun row(part: JComponent, gap: Int = 10) {
            part.alignmentX = 0f
            add(part)
            add(Box.createVerticalStrut(JBUI.scale(gap)))
        }
        row(titleLabel, 18)
        row(stageLabel)
        row(explanation)
        row(progressBar)
        row(activityLabel)
        row(logTitle, 6)
        row(logPane)
        row(elapsedLabel, 6)
        row(JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
            isOpaque = false
            add(startButton)
            add(cancelButton)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(logButton)
        }, 0)
    }

    val component = object : JPanel(null) {
        init {
            isOpaque = true
            add(column)
        }

        override fun doLayout() {
            val gap = JBUI.scale(24)
            val availableHeight = (height - 2 * gap).coerceAtLeast(0)
            val cardWidth = (width - 2 * gap).coerceIn(0, JBUI.scale(700))
            column.setSize(cardWidth, availableHeight)
            column.invalidate()
            val cardHeight = column.preferredSize.height.coerceAtMost(availableHeight)
            column.setBounds((width - cardWidth) / 2, (height - cardHeight) / 2, cardWidth, cardHeight)
        }
    }

    fun show(
        state: OpenCodeServerLifecycleState,
        stage: String? = null,
        progress: OpenCodeStartupProgress? = null,
        logAvailable: Boolean = true,
    ) {
        val stopped = state == OpenCodeServerLifecycleState.STOPPED
        titleLabel.text = when (state) {
            OpenCodeServerLifecycleState.STOPPED -> "OpenCode is stopped"
            OpenCodeServerLifecycleState.STARTING -> "Starting OpenCode"
            else -> "Restarting OpenCode"
        }
        stageLabel.text = progress?.stage ?: stage ?: "Preparing OpenCode…"
        explanation.text = if (stopped) "Start the server to open this project." else progress?.explanation
            ?: "Preparing the server. The page will open automatically when it is ready."
        activityLabel.text = progress?.let(::formatStartupActivity) ?: "Waiting for the first update…"
        elapsedLabel.text = progress?.let {
            "Elapsed ${formatElapsedMillis(it.elapsedMillis)} · This step ${formatElapsedMillis(it.stageElapsedMillis)}"
        }.orEmpty()
        val lines = progress?.recentOutput.orEmpty().joinToString("\n").ifBlank {
            "Command output will appear here. Some startup steps are quiet."
        }
        if (logArea.text != lines) {
            val bar = logPane.verticalScrollBar
            val follow = bar.value + bar.visibleAmount >= bar.maximum - 4
            val oldCaret = logArea.caretPosition
            logArea.text = lines
            logArea.caretPosition = if (follow) logArea.document.length else oldCaret.coerceAtMost(logArea.document.length)
        }
        listOf(stageLabel, progressBar, activityLabel, logTitle, logPane, elapsedLabel, cancelButton, logButton)
            .forEach { it.isVisible = !stopped }
        progressBar.isIndeterminate = !stopped
        startButton.isVisible = stopped
        startButton.isEnabled = stopped
        logButton.isEnabled = logAvailable
        logButton.toolTipText = if (logAvailable) "Open the full server log in the editor"
            else "File logging is disabled. Recent activity is still shown above."
    }

    fun stopProgressAnimation() {
        progressBar.isIndeterminate = false
    }
}
