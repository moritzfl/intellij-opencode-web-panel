package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.ui.BadgeIconSupplier
import javax.swing.JComponent

/** Host-specific hooks for the shared OpenCode/JCEF panel implementation. */
internal interface OpenCodePanelHost {
    val project: Project

    fun isDisposed(): Boolean

    fun isActive(component: JComponent): Boolean = !isDisposed() && component.isShowing

    fun isPanelInView(component: JComponent): Boolean = isActive(component)

    fun activate(component: JComponent, action: () -> Unit) = action()

    fun replacePanel()

    fun showFailure()

    fun updateHeading() {}

    fun updateAgentStatus(state: String, icons: BadgeIconSupplier) {}
}
