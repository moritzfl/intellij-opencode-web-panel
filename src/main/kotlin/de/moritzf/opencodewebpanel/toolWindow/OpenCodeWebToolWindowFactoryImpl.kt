package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import kotlin.jvm.JvmDefaultWithoutCompatibility

/** Must match the `toolWindow id` declared in plugin.xml. */
internal const val OPEN_CODE_TOOL_WINDOW_ID = "OpenCode Web Panel"

@JvmDefaultWithoutCompatibility
class OpenCodeWebToolWindowFactoryImpl : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val toolWindowContent = OpenCodeWebToolWindowContent(toolWindow)
        ApplicationManager.getApplication().invokeLater {
            val content = ContentFactory.getInstance().createContent(toolWindowContent.getContent(), null, false)
            content.setDisposer(toolWindowContent)
            toolWindow.contentManager.addContent(content)
            installTitleActions(toolWindow)
            toolWindowContent.checkAndLoadContent()
        }
    }

    private fun installTitleActions(toolWindow: ToolWindow) {
        // Icon-only actions in the existing title bar; IntelliJ clips them on narrow panels,
        // so the gear menu below duplicates everything.
        toolWindow.setTitleActions(
            listOf(
                OpenCodeZoomOutAction(),
                OpenCodeZoomInAction(),
                OpenCodeReloadPageAction(),
                OpenCodeRestartServerAction(),
            ),
        )
        toolWindow.setAdditionalGearActions(
            DefaultActionGroup(
                OpenCodeZoomOutAction(),
                OpenCodeZoomInAction(),
                OpenCodeResetZoomAction(),
                OpenCodeReloadPageAction(),
                OpenCodeRestartServerAction(),
                OpenCodeResetWebStateAction(),
                OpenCodeViewServerLogAction(),
                OpenCodeOpenSettingsAction(),
            ),
        )
    }
}
