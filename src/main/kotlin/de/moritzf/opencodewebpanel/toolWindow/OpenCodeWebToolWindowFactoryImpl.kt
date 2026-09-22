package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry
import com.intellij.ui.content.ContentFactory
import kotlin.jvm.JvmDefaultWithoutCompatibility

internal fun openCodeToolWindowHeading(project: Project?): String {
    val native = OpenCodeServerBackend.isNative(
        OpenCodeServerBackendRegistry.getInstance().backendFor(project).backendId,
    )
    return if (native) "OpenCode (native CLI)" else "OpenCode (sbx)"
}

internal fun updateOpenCodeToolWindowHeading(toolWindow: ToolWindow) {
    if (toolWindow.isDisposed) return
    toolWindow.stripeTitle = openCodeToolWindowHeading(toolWindow.project)
}

@JvmDefaultWithoutCompatibility
class OpenCodeWebToolWindowFactoryImpl : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val controller = OpenCodePanelController.getInstance(project)
        val content = ContentFactory.getInstance().createContent(controller.toolWindowComponent, null, false)
        toolWindow.contentManager.addContent(content)
        controller.attachToolWindow(toolWindow)
        updateOpenCodeToolWindowHeading(toolWindow)
        installTitleActions(toolWindow)
    }

    private fun installTitleActions(toolWindow: ToolWindow) {
        // Icon-only actions in the existing title bar; IntelliJ clips them on narrow panels,
        // so the gear menu below duplicates everything.
        toolWindow.setTitleActions(openCodeTitleActions())
        toolWindow.setAdditionalGearActions(openCodeGearActions())
    }
}
