package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.actionSystem.DefaultActionGroup
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
        val content = ContentFactory.getInstance().createContent(controller.component, null, false)
        toolWindow.contentManager.addContent(content)
        controller.attachToolWindow(toolWindow)
        updateOpenCodeToolWindowHeading(toolWindow)
        installTitleActions(toolWindow)
    }

    private fun installTitleActions(toolWindow: ToolWindow) {
        // Icon-only actions in the existing title bar; IntelliJ clips them on narrow panels,
        // so the gear menu below duplicates everything.
        toolWindow.setTitleActions(
            listOf(
                OpenCodeUpdateAvailableAction(),
                OpenCodeZoomOutAction(),
                OpenCodeZoomInAction(),
                OpenCodeReloadPageAction(),
                OpenCodeRestartServerAction(),
            ),
        )
        toolWindow.setAdditionalGearActions(
            DefaultActionGroup().apply {
                add(OpenCodeUpdateAvailableAction())
                add(OpenCodeNewSessionAction())
                addSeparator()
                add(OpenCodeZoomOutAction())
                add(OpenCodeZoomInAction())
                add(OpenCodeResetZoomAction())
                addSeparator()
                add(OpenCodeAutoAcceptPermissionsAction())
                addSeparator()
                add(OpenCodeReloadPageAction())
                add(OpenCodeRestartServerAction())
                add(OpenCodeStopServerAction())
                addSeparator()
                add(OpenCodeResetWebStateAction())
                add(OpenCodeResetSandboxAction())
                add(OpenCodeUpgradeSandboxBinaryAction())
                add(OpenCodeUpdateSandboxImageAction())
                add(OpenCodeOpenDevToolsAction())
                add(OpenCodeViewServerLogAction())
                addSeparator()
                add(OpenCodeOpenProjectSettingsAction())
                add(OpenCodeOpenSettingsAction())
                add(OpenCodeOpenKeymapAction())
            },
        )
    }
}
