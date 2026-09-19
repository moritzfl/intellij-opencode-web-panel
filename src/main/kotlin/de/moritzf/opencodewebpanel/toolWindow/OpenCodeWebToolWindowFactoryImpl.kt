package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.components.BorderLayoutPanel
import javax.swing.JPanel
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
        // Build content + disposer on this call (typically EDT) so a fast project close cannot
        // orphan JCEF before an invokeLater runs. Only the initial server/page load is deferred.
        val toolWindowContent = installOpenCodeToolWindowContent(toolWindow)
        updateOpenCodeToolWindowHeading(toolWindow)
        installTitleActions(toolWindow)
        if (toolWindowContent == null) return
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
                OpenCodeOpenInEditorAction(),
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
                add(OpenCodeOpenInEditorAction())
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

/**
 * Installs a fresh panel, or the failure card when JCEF refuses to create one. Never throws:
 * an exception here would leave the tool window without content until the IDE restarts, because
 * [ToolWindowFactory.createToolWindowContent] runs once per tool window.
 */
internal fun installOpenCodeToolWindowContent(
    toolWindow: ToolWindow,
): OpenCodeWebToolWindowContent? {
    val coordinator = OpenCodePanelCoordinator.getInstance(toolWindow.project)
    val host = OpenCodeToolWindowHost(toolWindow, coordinator)
    val shell = BorderLayoutPanel()
    val placementId = toolWindowPlacementId(toolWindow.project)
    coordinator.registerPlacement(placementId, shell, JPanel(), host)
    val panel = coordinator.panelFor(host, sessionId = null)
    coordinator.place(placementId)
    addOpenCodeToolWindowContent(toolWindow, shell)
    if (panel == null) coordinator.showFailure()
    return panel
}

/**
 * Recovery hammer for a stuck or crashed JCEF panel: install a fresh browser and dispose the
 * current one. Generic replacement starts at OpenCode Home so session selection remains owned by
 * the web application.
 * Stop→Start must not use this — that path keeps the existing document (Windows).
 *
 * The replacement's remote browser is created *before* the current content is dropped. Disposing first tears the
 * old browser down inside the CEF server while the new browser and its message routers are being
 * created, which is exactly when out-of-process JCEF answers with no remote object
 * (`RemoteMessageRouterImpl` NPE) — and a failure at that point used to leave the tool window
 * permanently empty.
 */
internal fun replaceOpenCodeToolWindowContent(toolWindow: ToolWindow) {
    if (toolWindow.isDisposed || toolWindow.project.isDisposed) return
    OpenCodePanelCoordinator.getInstance(toolWindow.project).replacePanel()
}

internal fun installOpenCodePanelFailureCard(toolWindow: ToolWindow) {
    if (toolWindow.isDisposed || toolWindow.project.isDisposed) return
    OpenCodePanelCoordinator.getInstance(toolWindow.project).showFailure()
}

private fun addOpenCodeToolWindowContent(
    toolWindow: ToolWindow,
    component: JPanel,
): Content {
    val content = ContentFactory.getInstance().createContent(component, null, false)
    toolWindow.contentManager.addContent(content)
    updateOpenCodeToolWindowHeading(toolWindow)
    return content
}

private fun toolWindowPlacementId(project: Project): String = "tool-window:${System.identityHashCode(project)}"
