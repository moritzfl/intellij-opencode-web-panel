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
internal const val OPEN_CODE_TOOL_WINDOW_ID = "OpenCode"

@JvmDefaultWithoutCompatibility
class OpenCodeWebToolWindowFactoryImpl : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Build content + disposer on this call (typically EDT) so a fast project close cannot
        // orphan JCEF before an invokeLater runs. Only the initial server/page load is deferred.
        val toolWindowContent = OpenCodeWebToolWindowContent(toolWindow)
        val content = ContentFactory.getInstance().createContent(toolWindowContent.getContent(), null, false)
        content.setDisposer(toolWindowContent)
        toolWindow.contentManager.addContent(content)
        installTitleActions(toolWindow)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || toolWindow.isDisposed || project != toolWindow.project) {
                return@invokeLater
            }
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
            DefaultActionGroup().apply {
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
