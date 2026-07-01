package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsConfigurable
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsListener
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState

/**
 * Tool-window title-bar and gear-menu actions. Title actions must stay few and icon-only:
 * IntelliJ clips them when the panel is narrow, which is why the gear menu duplicates them.
 */
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

internal class OpenCodeZoomInAction : DumbAwareAction(
    "Zoom In",
    "Increase the OpenCode panel zoom",
    AllIcons.General.ZoomIn,
) {
    override fun actionPerformed(e: AnActionEvent) = OpenCodeZoom.apply(OpenCodeZoom::zoomedIn)

    override fun update(e: AnActionEvent) {
        val current = OpenCodeSettingsState.sanitizeUiZoomPercent(OpenCodeSettingsState.getInstance().uiZoomPercent)
        e.presentation.isEnabled = current < OpenCodeSettingsState.MAX_UI_ZOOM_PERCENT
        e.presentation.description = "Increase the OpenCode panel zoom (currently $current%)"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class OpenCodeZoomOutAction : DumbAwareAction(
    "Zoom Out",
    "Decrease the OpenCode panel zoom",
    AllIcons.General.ZoomOut,
) {
    override fun actionPerformed(e: AnActionEvent) = OpenCodeZoom.apply(OpenCodeZoom::zoomedOut)

    override fun update(e: AnActionEvent) {
        val current = OpenCodeSettingsState.sanitizeUiZoomPercent(OpenCodeSettingsState.getInstance().uiZoomPercent)
        e.presentation.isEnabled = current > OpenCodeSettingsState.MIN_UI_ZOOM_PERCENT
        e.presentation.description = "Decrease the OpenCode panel zoom (currently $current%)"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class OpenCodeResetZoomAction : DumbAwareAction(
    "Reset Zoom",
    "Reset the OpenCode panel zoom to ${OpenCodeSettingsState.DEFAULT_UI_ZOOM_PERCENT}%",
    null,
) {
    override fun actionPerformed(e: AnActionEvent) = OpenCodeZoom.apply { OpenCodeSettingsState.DEFAULT_UI_ZOOM_PERCENT }

    override fun update(e: AnActionEvent) {
        val current = OpenCodeSettingsState.sanitizeUiZoomPercent(OpenCodeSettingsState.getInstance().uiZoomPercent)
        e.presentation.isEnabled = current != OpenCodeSettingsState.DEFAULT_UI_ZOOM_PERCENT
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class OpenCodeRestartServerAction : DumbAwareAction(
    "Restart OpenCode Server",
    "Stop and restart the shared OpenCode server",
    AllIcons.Actions.Restart,
) {
    override fun actionPerformed(e: AnActionEvent) {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(OpenCodeSettingsListener.TOPIC)
            .serverRestartRequested()
    }

    override fun update(e: AnActionEvent) {
        val state = SharedOpenCodeServerManager.getInstance().getLifecycleState()
        e.presentation.isEnabled = state != OpenCodeServerLifecycleState.STARTING &&
            state != OpenCodeServerLifecycleState.RESTARTING
        e.presentation.description = "Stop and restart the shared OpenCode server (currently ${state.displayLabel.lowercase()})"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class OpenCodeViewServerLogAction : DumbAwareAction(
    "View Server Log",
    "Open the OpenCode server log in the editor",
    null,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        openOpenCodeServerLogInEditor(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null &&
            SharedOpenCodeServerManager.getInstance().getServerLogFile() != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class OpenCodeOpenSettingsAction : DumbAwareAction(
    "OpenCode Web Panel Settings",
    "Open the OpenCode Web Panel settings",
    null,
) {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, OpenCodeSettingsConfigurable::class.java)
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
