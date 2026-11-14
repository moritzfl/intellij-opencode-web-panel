package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import de.moritzf.opencodewebpanel.features.OPEN_CODE_TOOL_WINDOW_TITLE
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry
import kotlin.jvm.JvmDefaultWithoutCompatibility

internal fun openCodeToolWindowHeading(project: Project?): String {
    val native =
        OpenCodeServerBackend.isNative(
            OpenCodeServerBackendRegistry.getInstance().backendFor(project).backendId,
        )
    return if (native) "OpenCode (Host CLI)" else "OpenCode (Sandbox)"
}

internal fun updateOpenCodeToolWindowHeading(toolWindow: ToolWindow) {
    if (toolWindow.isDisposed) return
    toolWindow.stripeTitle = openCodeToolWindowHeading(toolWindow.project)
}

@JvmDefaultWithoutCompatibility
class OpenCodeWebToolWindowFactoryImpl : ToolWindowFactory, DumbAware {
    // Runs at registration, before the content exists: the stripe and Activate action would
    // otherwise show the internal id.
    override fun init(toolWindow: ToolWindow) {
        toolWindow.stripeTitle = OPEN_CODE_TOOL_WINDOW_TITLE
        OpenCodeToolWindowPlacement.migrateOnce(toolWindow)
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val controller = OpenCodePanelController.getInstance(project)
        val content =
            ContentFactory.getInstance().createContent(controller.toolWindowComponent, null, false)
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
