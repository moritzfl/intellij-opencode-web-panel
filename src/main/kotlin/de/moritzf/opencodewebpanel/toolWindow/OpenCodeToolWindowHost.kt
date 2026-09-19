package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.BadgeIconSupplier
import de.moritzf.opencodewebpanel.features.OpenCodeAgentStatusState
import javax.swing.JComponent

internal class OpenCodeToolWindowHost(
    private val toolWindow: ToolWindow,
) : OpenCodePanelHost {
    override val project = toolWindow.project

    override fun isDisposed(): Boolean = toolWindow.isDisposed || project.isDisposed

    override fun isPanelInView(component: JComponent): Boolean {
        return !isDisposed() && toolWindow.isVisible && component.isShowing &&
            WindowManager.getInstance().getFrame(project)?.isActive == true
    }

    override fun activate(component: JComponent, action: () -> Unit) {
        toolWindow.activate({
            action()
        }, true)
    }

    override fun replacePanel() {
        replaceOpenCodeToolWindowContent(toolWindow)
    }

    override fun showFailure() {
        installOpenCodePanelFailureCard(toolWindow)
    }

    override fun updateHeading() {
        updateOpenCodeToolWindowHeading(toolWindow)
    }

    override fun updateAgentStatus(state: String, icons: BadgeIconSupplier) {
        toolWindow.setIcon(
            when (state) {
                OpenCodeAgentStatusState.ATTENTION -> icons.warningIcon
                OpenCodeAgentStatusState.BUSY -> icons.liveIndicatorIcon
                else -> icons.originalIcon
            },
        )
    }
}
