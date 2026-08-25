package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager
import de.moritzf.opencodewebpanel.server.isOpenCodePageReloadEnabled
import de.moritzf.opencodewebpanel.server.isOpenCodeServerStopEnabled
import de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsConfigurable
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsConfigurable
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsListener
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState

/**
 * Tool-window title-bar and gear-menu actions. Title actions must stay few and icon-only:
 * IntelliJ clips them when the panel is narrow, which is why the gear menu duplicates them.
 */
private fun AnActionEvent.setOpenCodeHint(hint: String) {
    presentation.description = hint
    presentation.putClientProperty(ActionUtil.SECONDARY_TEXT, hint)
}
internal object OpenCodeZoom {
    const val STEP_PERCENT = 10

    fun zoomedIn(percent: Int): Int = OpenCodeSettingsState.sanitizeUiZoomPercent(percent + STEP_PERCENT)

    fun zoomedOut(percent: Int): Int = OpenCodeSettingsState.sanitizeUiZoomPercent(percent - STEP_PERCENT)

    fun apply(transform: (Int) -> Int) {
        val settings = OpenCodeSettingsState.getInstance()
        val next = transform(OpenCodeSettingsState.sanitizeUiZoomPercent(settings.uiZoomPercent))
        if (next == settings.uiZoomPercent) return
        settings.uiZoomPercent = next
        ApplicationManager.getApplication().messageBus
            .syncPublisher(OpenCodeSettingsListener.TOPIC)
            .uiZoomChanged(next)
    }
}

internal class OpenCodeNewSessionAction : DumbAwareAction(
    "New Session",
    "Start a new conversation",
    AllIcons.General.Add,
) {
    override fun actionPerformed(e: AnActionEvent) {
        openCodePanelContent(e)?.dispatchOpenCodeCommand(OpenCodeBrowserCommand.NEW_SESSION)
    }

    override fun update(e: AnActionEvent) {
        val content = openCodePanelContent(e)
        val serverUrl = SharedOpenCodeServerManager.getInstance().getServerUrl()
        e.presentation.isEnabled = content != null &&
            OpenCodeBrowserShortcutHandler.isCommandAvailable(
                OpenCodeBrowserCommand.NEW_SESSION,
                serverUrl,
                content.currentPageUrl(),
            )
        e.setOpenCodeHint("Start a new conversation")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

internal class OpenCodeOpenProjectSettingsAction : DumbAwareAction(
    "Project Directory…",
    "Choose which folder OpenCode uses for this IDE project",
    AllIcons.Nodes.Folder,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, OpenCodeProjectSettingsConfigurable::class.java)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
        e.setOpenCodeHint("Choose which folder OpenCode uses")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class OpenCodeOpenKeymapAction : DumbAwareAction(
    "Keyboard Shortcuts…",
    "Open Keymap settings for OpenCode actions",
    AllIcons.General.Keyboard,
) {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, "Keymap")
    }

    override fun update(e: AnActionEvent) {
        e.setOpenCodeHint("New Session, Close Tab, Choose Model…")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class OpenCodeZoomInAction : DumbAwareAction(
    "Zoom In",
    "Enlarge the panel.",
    AllIcons.General.ZoomIn,
) {
    override fun actionPerformed(e: AnActionEvent) = OpenCodeZoom.apply(OpenCodeZoom::zoomedIn)

    override fun update(e: AnActionEvent) {
        val current = OpenCodeSettingsState.sanitizeUiZoomPercent(OpenCodeSettingsState.getInstance().uiZoomPercent)
        e.presentation.isEnabled = current < OpenCodeSettingsState.MAX_UI_ZOOM_PERCENT
        e.setOpenCodeHint("Enlarge the panel. Now $current%.")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class OpenCodeZoomOutAction : DumbAwareAction(
    "Zoom Out",
    "Shrink the panel.",
    AllIcons.General.ZoomOut,
) {
    override fun actionPerformed(e: AnActionEvent) = OpenCodeZoom.apply(OpenCodeZoom::zoomedOut)

    override fun update(e: AnActionEvent) {
        val current = OpenCodeSettingsState.sanitizeUiZoomPercent(OpenCodeSettingsState.getInstance().uiZoomPercent)
        e.presentation.isEnabled = current > OpenCodeSettingsState.MIN_UI_ZOOM_PERCENT
        e.setOpenCodeHint("Shrink the panel. Now $current%.")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class OpenCodeResetZoomAction : DumbAwareAction(
    "Reset Zoom",
    "Reset panel zoom to ${OpenCodeSettingsState.DEFAULT_UI_ZOOM_PERCENT}%.",
    AllIcons.General.ActualZoom,
) {
    override fun actionPerformed(e: AnActionEvent) = OpenCodeZoom.apply { OpenCodeSettingsState.DEFAULT_UI_ZOOM_PERCENT }

    override fun update(e: AnActionEvent) {
        val current = OpenCodeSettingsState.sanitizeUiZoomPercent(OpenCodeSettingsState.getInstance().uiZoomPercent)
        val defaultZoom = OpenCodeSettingsState.DEFAULT_UI_ZOOM_PERCENT
        e.presentation.isEnabled = current != defaultZoom
        e.setOpenCodeHint("Reset panel zoom to $defaultZoom%. Now $current%.")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class OpenCodeRestartServerAction : DumbAwareAction(
    "Restart OpenCode Server",
    "Restart the shared server. Interrupts all OpenCode panels.",
    AllIcons.Actions.StopAndRestart,
) {
    override fun actionPerformed(e: AnActionEvent) {
        if (!confirmOpenCodeServerRestart(e.project)) return
        ApplicationManager.getApplication().messageBus
            .syncPublisher(OpenCodeSettingsListener.TOPIC)
            .serverRestartRequested()
    }

    override fun update(e: AnActionEvent) {
        val state = SharedOpenCodeServerManager.getInstance().getLifecycleState()
        e.presentation.isEnabled = state != OpenCodeServerLifecycleState.STARTING &&
            state != OpenCodeServerLifecycleState.RESTARTING
        e.setOpenCodeHint("Restart the shared server. Interrupts all panels.")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class OpenCodeStopServerAction : DumbAwareAction(
    "Stop OpenCode Server",
    "Stop the shared server. It will not auto-restart.",
    AllIcons.Actions.Suspend,
) {
    override fun actionPerformed(e: AnActionEvent) {
        if (!confirmOpenCodeServerStop(e.project)) return
        SharedOpenCodeServerManager.getInstance().stopServer()
    }

    override fun update(e: AnActionEvent) {
        val state = SharedOpenCodeServerManager.getInstance().getLifecycleState()
        e.presentation.isEnabled = isOpenCodeServerStopEnabled(state)
        e.setOpenCodeHint("Stop the shared server. It will not auto-restart.")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class OpenCodeReloadPageAction : DumbAwareAction(
    "Reload OpenCode Page",
    "Reload the page. The server stays running.",
    AllIcons.Actions.Refresh,
) {
    override fun actionPerformed(e: AnActionEvent) {
        openCodePanelContent(e)?.reloadOpenCodePage()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null &&
            isOpenCodePageReloadEnabled(SharedOpenCodeServerManager.getInstance().getLifecycleState())
        e.setOpenCodeHint("Reload the page. The server stays running.")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Gear-menu bridge for OpenCode's broken web auto-accept. Scoped to the displayed conversation
 * and its subagents; state is in-memory only.
 */
internal class OpenCodeAutoAcceptPermissionsAction : ToggleAction(
    "Auto-Accept Permissions",
    "Auto-allow tool prompts in this conversation. Not saved.",
    null,
), DumbAware {
    override fun isSelected(e: AnActionEvent): Boolean {
        return openCodePanelContent(e)?.isPermissionAutoAcceptEnabled() == true
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        openCodePanelContent(e)?.setPermissionAutoAcceptEnabled(state)
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val content = openCodePanelContent(e)
        e.presentation.isEnabled = content?.canTogglePermissionAutoAccept() == true
        e.setOpenCodeHint(
            if (content?.isPermissionAutoAcceptEnabled() == true) {
                "Auto-allow is on. Click to ask again."
            } else {
                "Auto-allow tool prompts in this conversation."
            },
        )
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Resolves the OpenCode panel content for the invoking tool window, so a reload targets only the
 * panel the user clicked instead of every project's panel. Prefers the tool window carried by the
 * action event (title-bar invocation) and falls back to a lookup by ID (gear menu).
 */
private fun openCodePanelContent(e: AnActionEvent): OpenCodeWebToolWindowContent? {
    val toolWindow = e.getData(PlatformDataKeys.TOOL_WINDOW)
        ?: e.project?.let { ToolWindowManager.getInstance(it).getToolWindow(OPEN_CODE_TOOL_WINDOW_ID) }
        ?: return null
    return toolWindow.contentManager.contents
        .firstNotNullOfOrNull { it.disposer as? OpenCodeWebToolWindowContent }
}

/**
 * Restarting interrupts everything in progress in every project sharing the server, so a running
 * server requires explicit confirmation. Restarting a stopped or failed server loses nothing and
 * proceeds without a prompt.
 */
internal fun confirmOpenCodeServerRestart(project: Project?): Boolean {
    if (SharedOpenCodeServerManager.getInstance().getLifecycleState() != OpenCodeServerLifecycleState.RUNNING) return true
    return MessageDialogBuilder.yesNo(
        "Restart OpenCode Server",
        "The OpenCode server is shared by all open projects. " +
            "Restarting interrupts everything currently in progress in every OpenCode panel.",
    )
        .yesText("Restart")
        .noText("Cancel")
        .icon(Messages.getWarningIcon())
        .ask(project)
}

internal fun confirmOpenCodeServerStop(project: Project?): Boolean {
    val state = SharedOpenCodeServerManager.getInstance().getLifecycleState()
    if (!isOpenCodeServerStopEnabled(state)) return true
    val consequence = when (state) {
        OpenCodeServerLifecycleState.RUNNING ->
            "Stopping it interrupts everything currently in progress in every OpenCode panel."
        OpenCodeServerLifecycleState.RESTARTING ->
            "Stopping cancels the restart that is currently in progress."
        else ->
            "Stopping cancels the start that is currently in progress."
    }
    return MessageDialogBuilder.yesNo(
        "Stop OpenCode Server",
        "The OpenCode server is shared by all open projects. $consequence",
    )
        .yesText("Stop")
        .noText("Cancel")
        .icon(Messages.getWarningIcon())
        .ask(project)
}

/**
 * Gear-only escape hatch: recovers from corrupted embedded web-app state (a bad mirrored
 * snapshot or seeded project state that is re-applied on every load) without requiring the
 * user to locate and wipe the JCEF profile manually.
 */
internal class OpenCodeResetWebStateAction : DumbAwareAction(
    "Reset OpenCode Web State",
    "Clear local web UI state and reload. Conversations stay.",
    AllIcons.General.Reset,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val content = openCodePanelContent(e) ?: return
        if (!confirmOpenCodeWebStateReset(e.project)) return
        content.resetOpenCodeWebState()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
        e.setOpenCodeHint("Clear local web UI state and reload.")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal fun confirmOpenCodeWebStateReset(project: Project?): Boolean {
    return MessageDialogBuilder.yesNo(
        "Reset OpenCode Web State",
        "This clears the embedded OpenCode web app's locally stored UI state (open tabs, drafts, " +
            "web-app settings) and the snapshot the IDE keeps of it. The browser state is shared, " +
            "so this affects the OpenCode panel in every open project. " +
            "Conversations stored on the OpenCode server are not affected.",
    )
        .yesText("Reset and Reload")
        .noText("Cancel")
        .icon(Messages.getWarningIcon())
        .ask(project)
}

/**
 * Gear-only debugging entry: opens Chromium's built-in DevTools (console, network, elements)
 * for the panel's browser via JBCef's own support — no plugin-side tooling to maintain.
 */
internal class OpenCodeOpenDevToolsAction : DumbAwareAction(
    "Open Browser DevTools",
    "Open Chromium DevTools for this panel.",
    AllIcons.Toolwindows.WebToolWindow,
) {
    override fun actionPerformed(e: AnActionEvent) {
        openCodePanelContent(e)?.openBrowserDevTools()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
        e.setOpenCodeHint("Open Chromium DevTools for this panel.")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class OpenCodeViewServerLogAction : DumbAwareAction(
    "View Server Log",
    "Open the server log in the editor.",
    AllIcons.Debugger.Console,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        openOpenCodeServerLogInEditor(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null &&
            SharedOpenCodeServerManager.getInstance().getServerLogFile() != null
        e.setOpenCodeHint("Open the server log in the editor.")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class OpenCodeOpenSettingsAction : DumbAwareAction(
    "OpenCode Web Panel Settings",
    "Open plugin settings.",
    AllIcons.General.Settings,
) {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, OpenCodeSettingsConfigurable::class.java)
    }

    override fun update(e: AnActionEvent) {
        e.setOpenCodeHint("Open plugin settings.")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal fun openOpenCodeServerLogInEditor(project: Project) {
    val file = SharedOpenCodeServerManager.getInstance().getServerLogFile() ?: return
    runCatching {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: return
        OpenFileDescriptor(project, virtualFile).navigate(true)
    }.onFailure { error ->
        Logger.getInstance(SharedOpenCodeServerManager::class.java)
            .warn("Could not open OpenCode server log: ${error.message}")
    }
}
