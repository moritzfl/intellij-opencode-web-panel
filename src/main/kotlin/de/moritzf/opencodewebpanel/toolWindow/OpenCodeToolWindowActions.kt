package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer
import de.moritzf.opencodewebpanel.features.OpenCodeReleaseUpdates
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.SbxExposure
import de.moritzf.opencodewebpanel.server.SbxOpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.SbxSandboxRecord
import de.moritzf.opencodewebpanel.server.SbxSandboxRecordStore
import de.moritzf.opencodewebpanel.server.isOpenCodePageReloadEnabled
import de.moritzf.opencodewebpanel.server.isOpenCodeServerStopEnabled
import de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsConfigurable
import de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsState
import de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsListener
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsConfigurable
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsListener
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState

internal const val OPEN_CODE_MOVE_PANEL_ACTION_ID = "OpenCodeWebPanel.MovePanel"

internal fun openCodeTitleActions() = listOf(
    OpenCodeUpdateAvailableAction(),
    ActionManager.getInstance().getAction(OPEN_CODE_MOVE_PANEL_ACTION_ID),
    OpenCodeZoomOutAction(),
    OpenCodeZoomInAction(),
    OpenCodeReloadPageAction(),
    OpenCodeRestartServerAction(),
)

internal fun openCodeGearActions() = DefaultActionGroup().apply {
    add(OpenCodeUpdateAvailableAction())
    add(OpenCodeNewSessionAction())
    add(ActionManager.getInstance().getAction(OPEN_CODE_MOVE_PANEL_ACTION_ID))
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
}

internal class OpenCodeMovePanelAction : DumbAwareAction(
    "Move OpenCode Panel",
    "Move the live OpenCode panel between the tool window and an editor tab",
    AllIcons.Actions.OpenNewTab,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val controller = e.project?.getServiceIfCreated(OpenCodePanelController::class.java) ?: return
        if (controller.isInEditor) controller.moveToToolWindow() else controller.moveToEditor()
    }

    override fun update(e: AnActionEvent) {
        val controller = e.project?.getServiceIfCreated(OpenCodePanelController::class.java)
        val inEditor = controller?.isInEditor == true
        e.presentation.isEnabled = controller != null && !controller.isDisposed
        e.presentation.text = when {
            e.place == ActionPlaces.ACTION_SEARCH -> "Move OpenCode Panel"
            inEditor -> "Move to Tool Window"
            else -> "Move to Editor"
        }
        e.presentation.icon = if (inEditor) AllIcons.General.OpenInToolWindow else AllIcons.Actions.OpenNewTab
        e.presentation.description = "Move the live panel without reloading or losing your draft"
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

/**
 * Lightning on the tool-window title when a same-major OpenCode release is newer.
 * Hover shows the version. Click upgrades the sandbox (confirm) or shows Host CLI steps.
 */
internal class OpenCodeUpdateAvailableAction : DumbAwareAction(
    "OpenCode Update",
    "A newer OpenCode release is available",
    AllIcons.Actions.Lightning,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val notice = OpenCodeReleaseUpdates.pendingNotice(project, openCodeBackend(project).getServerVersion()) ?: return
        offerOpenCodeUpdate(project, notice)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val backend = project?.let { openCodeBackend(it) }
        val notice = project?.let { OpenCodeReleaseUpdates.pendingNotice(it, backend?.getServerVersion()) }
        val sandbox = backend != null && !OpenCodeServerBackend.isNative(backend.backendId)
        e.presentation.isEnabledAndVisible = notice != null
        e.presentation.description = notice?.let { OpenCodeReleaseUpdates.tooltip(it, sandbox) }
            ?: "A newer OpenCode release is available"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal fun offerOpenCodeUpdate(project: Project, notice: OpenCodeReleaseUpdates.Notice) {
    val backend = openCodeBackend(project)
    if (OpenCodeServerBackend.isNative(backend.backendId)) {
        OpenCodeHostUpgradeDialog(project, notice).show()
        return
    }
    val sandbox = backend as? SbxOpenCodeServerBackend ?: return
    val state = sandbox.getLifecycleState()
    if (state == OpenCodeServerLifecycleState.STARTING || state == OpenCodeServerLifecycleState.RESTARTING) {
        return
    }
    if (ownedSandboxRecord(project) == null) {
        Messages.showErrorDialog(
            project,
            "No owned sandbox for this project. Create or start the sandbox first.",
            "OpenCode update",
        )
        return
    }
    if (!confirmOpenCodeSandboxBinaryUpgrade(project)) return
    sandbox.upgradeOpenCodeBinary(project)
}

private class OpenCodeHostUpgradeDialog(
    project: Project,
    private val notice: OpenCodeReleaseUpdates.Notice,
) : DialogWrapper(project) {
    init {
        title = "OpenCode update available"
        isModal = true
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(16))).apply {
        val introduction = JPanel(GridLayout(0, 1, 0, JBUI.scale(12))).apply {
            add(JBLabel(OpenCodeReleaseUpdates.hostUpgradeIntro(notice)))
            add(JBLabel("This plugin does not upgrade Host CLI. In a terminal run:"))
        }
        // Keep the block's outer edge aligned with the prose, independent of form-field visual padding.
        add(introduction, BorderLayout.NORTH)
        add(copyableCommandBlock(OpenCodeReleaseUpdates.HOST_UPGRADE_COMMAND), BorderLayout.CENTER)
        add(JBLabel("Then Restart OpenCode Server in this panel."), BorderLayout.SOUTH)
    }
}

private fun copyableCommandBlock(command: String): JComponent {
    val commandField = JBTextField(command).apply {
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        font = Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().size)
        foreground = UIUtil.getLabelForeground()
        caret.isVisible = false
    }
    val copyButton = JButton(AllIcons.Actions.Copy).apply {
        toolTipText = "Copy command"
        accessibleContext.accessibleName = "Copy command"
        putClientProperty("JButton.buttonType", "toolBarButton")
        border = JBUI.Borders.empty(4)
        isBorderPainted = false
        isContentAreaFilled = false
        isOpaque = false
        isFocusable = false
        preferredSize = JBUI.size(28, 28)
        val feedbackTimer = Timer(1_500) {
            icon = AllIcons.Actions.Copy
            toolTipText = "Copy command"
            accessibleContext.accessibleName = "Copy command"
        }.apply { isRepeats = false }
        addActionListener {
            CopyPasteManager.getInstance().setContents(StringSelection(command))
            icon = AllIcons.Actions.Checked
            toolTipText = "Copied"
            accessibleContext.accessibleName = "Copied"
            feedbackTimer.restart()
        }
    }
    return object : JPanel(BorderLayout(JBUI.scale(16), 0)) {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val graphics = g.create() as Graphics2D
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                graphics.color = background
                val arc = JBUI.scale(8)
                graphics.fillRoundRect(0, 0, width, height, arc, arc)
            } finally {
                graphics.dispose()
            }
        }
    }.apply {
        isOpaque = false
        // Shade relative to the current theme so the block stays distinct in light and dark modes.
        background = JBColor.lazy { ColorUtil.mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.06) }
        border = JBUI.Borders.empty(16)
        add(commandField, BorderLayout.CENTER)
        add(copyButton, BorderLayout.EAST)
    }
}

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
        e.presentation.isEnabled = content != null &&
            OpenCodeBrowserShortcutHandler.isCommandAvailable(
                OpenCodeBrowserCommand.NEW_SESSION,
                content.openCodeServerUrl(),
                content.currentPageUrl(),
            )
        e.presentation.description = "Start a new conversation"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

internal class OpenCodeOpenProjectSettingsAction : DumbAwareAction(
    "Project Settings…",
    "Open OpenCode Web Panel project settings",
    AllIcons.Actions.Properties,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, OpenCodeProjectSettingsConfigurable::class.java)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
        e.presentation.description = "Directory, sandbox, and this project's server"
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
        e.presentation.description = "New session, close tab, choose model…"
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
        e.presentation.description = "Enlarge the panel. Now $current%."
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
        e.presentation.description = "Shrink the panel. Now $current%."
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
        e.presentation.description = "Reset panel zoom to $defaultZoom%. Now $current%."
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class OpenCodeRestartServerAction : DumbAwareAction(
    "Restart OpenCode Server",
    "Restart OpenCode and recover a stuck panel.",
    AllIcons.Actions.StopAndRestart,
) {
    override fun actionPerformed(e: AnActionEvent) {
        if (!confirmOpenCodeServerRestart(e.project)) return
        requestOpenCodeServerRestart(e.project)
    }

    override fun update(e: AnActionEvent) {
        val backend = openCodeBackend(e.project)
        val state = backend.getLifecycleState()
        e.presentation.isEnabled = state != OpenCodeServerLifecycleState.STARTING &&
            state != OpenCodeServerLifecycleState.RESTARTING
        e.presentation.description = if (OpenCodeServerBackend.isNative(backend.backendId)) {
            "Restart this project's OpenCode server and recover a stuck panel."
        } else {
            "Restart OpenCode in this project's sandbox and recover a stuck panel."
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class OpenCodeStopServerAction : DumbAwareAction(
    "Stop OpenCode Server",
    "Stop this project's OpenCode server. It will not auto-restart.",
    AllIcons.Actions.Suspend,
) {
    override fun actionPerformed(e: AnActionEvent) {
        if (!confirmOpenCodeServerStop(e.project)) return
        openCodeBackend(e.project).stopServer()
    }

    override fun update(e: AnActionEvent) {
        val state = openCodeBackend(e.project).getLifecycleState()
        e.presentation.isEnabled = isOpenCodeServerStopEnabled(state)
        e.presentation.description = "Stop this project's OpenCode server. It will not auto-restart."
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
            isOpenCodePageReloadEnabled(openCodeBackend(e.project).getLifecycleState())
        e.presentation.description = "Reload the page. The server stays running."
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
        e.presentation.description = if (content?.isPermissionAutoAcceptEnabled() == true) {
            "Auto-allow is on. Click to ask again."
        } else {
            "Auto-allow tool prompts in this conversation."
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Resolves the invoking project's panel without constructing a browser from action updates.
 */
private fun openCodePanelContent(e: AnActionEvent): OpenCodeWebToolWindowContent? {
    val project = e.getData(PlatformDataKeys.TOOL_WINDOW)?.project ?: e.project ?: return null
    return project.getServiceIfCreated(OpenCodePanelController::class.java)?.content()
}

internal fun requestOpenCodeServerRestart(project: Project?) {
    if (project == null || project.isDisposed) return
    // Only a live panel listens for this topic. Without one (tool window never opened, or the
    // panel failure card) Restart must still reach the server. Unit tests only see the topic.
    val hasPanel = project.getServiceIfCreated(OpenCodePanelController::class.java)?.content() != null
    if (!hasPanel && !ApplicationManager.getApplication().isUnitTestMode) {
        openCodeBackend(project).restartServer(project, project.basePath, { !project.isDisposed }, {}, {})
        return
    }
    project.messageBus
        .syncPublisher(OpenCodeProjectSettingsListener.TOPIC)
        .serverRestartRequested()
}

internal fun requestOpenCodeSandboxReset(project: Project, dropGuestOpenCode: Boolean = true) {
    val backend = openCodeBackend(project) as? SbxOpenCodeServerBackend ?: return
    backend.resetSandbox(
        project,
        callbackActive = { !project.isDisposed },
        onStarted = {},
        onFailed = {},
        dropGuestOpenCode = dropGuestOpenCode,
    )
    requestOpenCodeServerRestart(project)
}

/**
 * Restarting interrupts work on this project's server, so a running server requires
 * explicit confirmation. Restarting a stopped or failed server loses nothing and
 * proceeds without a prompt.
 */
internal fun confirmOpenCodeServerRestart(project: Project?): Boolean {
    val backend = openCodeBackend(project)
    if (backend.getLifecycleState() != OpenCodeServerLifecycleState.RUNNING) return true
    return MessageDialogBuilder.yesNo(
        "Restart OpenCode Server",
        "Restarting interrupts OpenCode work in this project only.",
    )
        .yesText("Restart")
        .noText("Cancel")
        .icon(Messages.getWarningIcon())
        .ask(project)
}

internal fun confirmOpenCodeServerStop(project: Project?): Boolean {
    val state = openCodeBackend(project).getLifecycleState()
    if (!isOpenCodeServerStopEnabled(state)) return true
    val consequence = when (state) {
        OpenCodeServerLifecycleState.RUNNING ->
            "Stopping it interrupts OpenCode work in this project only."
        OpenCodeServerLifecycleState.RESTARTING ->
            "Stopping cancels the restart that is currently in progress."
        else ->
            "Stopping cancels the start that is currently in progress."
    }
    return MessageDialogBuilder.yesNo(
        "Stop OpenCode Server",
        consequence,
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
internal class OpenCodeResetSandboxAction : DumbAwareAction(
    "Reset Sandbox",
    "Delete this project's Docker Sandbox and create a new one. Sessions in it are dropped.",
    AllIcons.Actions.GC,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (openCodeBackend(project) !is SbxOpenCodeServerBackend) return
        if (!confirmOpenCodeSandboxReset(project)) return
        requestOpenCodeSandboxReset(project)
    }

    override fun update(e: AnActionEvent) {
        val sandbox = e.project != null &&
            !OpenCodeServerBackend.isNative(openCodeBackend(e.project).backendId)
        e.presentation.isEnabledAndVisible = sandbox
        e.presentation.description = "Delete this project's Docker Sandbox and create a new one."
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class OpenCodeUpgradeSandboxBinaryAction : DumbAwareAction(
    "Upgrade OpenCode in Sandbox",
    "Run opencode upgrade inside this sandbox. The VM and sessions stay.",
    AllIcons.Actions.Lightning,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val backend = openCodeBackend(project) as? SbxOpenCodeServerBackend ?: return
        if (!confirmOpenCodeSandboxBinaryUpgrade(project)) return
        backend.upgradeOpenCodeBinary(project)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val sandbox = project != null &&
            !OpenCodeServerBackend.isNative(openCodeBackend(project).backendId)
        val state = project?.let { openCodeBackend(it).getLifecycleState() }
        e.presentation.isVisible = sandbox
        e.presentation.isEnabled = sandbox &&
            ownedSandboxRecord(project) != null &&
            state != OpenCodeServerLifecycleState.STARTING &&
            state != OpenCodeServerLifecycleState.RESTARTING
        e.presentation.description = "Upgrade the OpenCode binary inside this sandbox without recreating it."
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal fun confirmOpenCodeSandboxBinaryUpgrade(project: Project?): Boolean {
    return MessageDialogBuilder.yesNo(
        "Upgrade OpenCode in Sandbox",
        "This runs opencode upgrade inside the existing sandbox and restarts serve. " +
            "The VM and sessions stay. Needs sandbox network access.",
    )
        .yesText("Upgrade")
        .noText("Cancel")
        .icon(Messages.getInformationIcon())
        .ask(project)
}

internal class OpenCodeUpdateSandboxImageAction : DumbAwareAction(
    "Update OpenCode Sandbox Image",
    "Drop the cached official OpenCode template and recreate this sandbox so it pulls the latest image.",
    AllIcons.Actions.Download,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val backend = openCodeBackend(project) as? SbxOpenCodeServerBackend ?: return
        if (!confirmOpenCodeSandboxImageUpdate(project)) return
        backend.dropCachedOfficialOpencodeTemplates().whenComplete { ok, _ ->
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                if (ok == true) {
                    requestOpenCodeSandboxReset(project)
                } else {
                    Messages.showErrorDialog(
                        project,
                        backend.startFailureMessage() ?: "Could not drop the cached OpenCode sandbox image.",
                        "Update OpenCode Sandbox Image",
                    )
                }
            }, ModalityState.nonModal())
        }
    }

    override fun update(e: AnActionEvent) {
        val sandbox = e.project != null &&
            !OpenCodeServerBackend.isNative(openCodeBackend(e.project).backendId)
        e.presentation.isEnabledAndVisible = sandbox
        e.presentation.description =
            "Drop the cached official OpenCode template and recreate this sandbox."
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal fun confirmOpenCodeSandboxImageUpdate(project: Project?): Boolean {
    val retention = project?.let {
        OpenCodeProjectSettingsState.getInstance(it).effectiveProjectDirectory(it.basePath)
            ?.let(OpenCodeProjectSettingsConfigurable::sandboxSessionRetentionSummary)
    } ?: "Conversation history retention is unknown until the VM is inspected."
    return MessageDialogBuilder.yesNo(
        "Update OpenCode Sandbox Image",
        "This removes the cached official OpenCode Docker Sandbox image and recreates this project's sandbox " +
            "so the next start pulls the latest template. $retention " +
            "Host OpenCode history is not affected. Other sandboxes that still use the old image are unchanged " +
            "until they are recreated.",
    )
        .yesText("Update and Recreate")
        .noText("Cancel")
        .icon(Messages.getWarningIcon())
        .ask(project)
}

internal fun confirmOpenCodeSandboxExposure(project: Project?, exposure: SbxExposure?): Boolean {
    val items = exposure?.items.orEmpty()
    if (items.isEmpty()) return true
    return MessageDialogBuilder.yesNo(
        "Allow Sandbox Access",
        "This project's opencode-sbx.yaml gives its Docker Sandbox access beyond the project:\n\n" +
            items.joinToString("\n") { "• $it" } +
            "\n\nKits can grant network access and run setup inside the VM. " +
            "Allow only if you trust the source of this file. You are asked again when these grants change.",
    )
        .yesText("Allow and Start")
        .noText("Cancel")
        .icon(Messages.getWarningIcon())
        .ask(project)
}

internal fun confirmDiscardForeignSandbox(project: Project?): Boolean {
    return MessageDialogBuilder.yesNo(
        "Create new sandbox",
        "This force-removes the unmatched sandbox at this name or workspace, then creates one owned by this panel. " +
            "Sessions in that VM are dropped.",
    )
        .yesText("Create new")
        .noText("Cancel")
        .icon(Messages.getWarningIcon())
        .ask(project)
}

internal fun confirmOpenCodeSandboxRecreate(project: Project?, reasons: List<String>): Boolean {
    val retention = project?.let {
        OpenCodeProjectSettingsState.getInstance(it).effectiveProjectDirectory(it.basePath)
            ?.let(OpenCodeProjectSettingsConfigurable::sandboxSessionRetentionSummary)
    } ?: "Conversation history retention is unknown until the VM is inspected."
    return MessageDialogBuilder.yesNo(
        "Recreate Sandbox",
        "The sandbox differs from opencode-sbx.yaml:\n" + reasons.joinToString("\n") { "• $it" } +
            "\n\nRecreating removes the VM and creates a new one from the file. " +
            "Packages and other VM-only state are dropped. $retention",
    )
        .yesText("Recreate")
        .noText("Cancel")
        .icon(Messages.getWarningIcon())
        .ask(project)
}

internal fun confirmOpenCodeSandboxReset(project: Project?): Boolean {
    val retention = project?.let {
        OpenCodeProjectSettingsState.getInstance(it).effectiveProjectDirectory(it.basePath)
            ?.let(OpenCodeProjectSettingsConfigurable::sandboxSessionRetentionSummary)
    } ?: "Conversation history retention is unknown until the VM is inspected."
    return MessageDialogBuilder.yesNo(
        "Reset Sandbox",
        "This force-removes the plugin-owned Docker Sandbox VM and creates a new one, then starts OpenCode. " +
            "Packages and other VM-only state are dropped. $retention " +
            "An OpenCode 2.x binary kept for this sandbox is deleted and reinstalled on the next start (needs network). " +
            "Host OpenCode history is not affected.",
    )
        .yesText("Reset Sandbox")
        .noText("Cancel")
        .icon(Messages.getWarningIcon())
        .ask(project)
}

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
        e.presentation.description = "Clear local web UI state and reload."
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal fun confirmOpenCodeWebStateReset(project: Project?): Boolean {
    val sandbox = project != null &&
        !OpenCodeServerBackend.isNative(openCodeBackend(project).backendId)
    val scope = if (sandbox) {
        "This affects only this project's sandbox origin."
    } else {
        "Host CLI panels share one browser profile, so this can affect other Host OpenCode panels."
    }
    return MessageDialogBuilder.yesNo(
        "Reset OpenCode Web State",
        "This clears the embedded OpenCode web app's locally stored UI state (open tabs, drafts, " +
            "web-app settings) and the snapshot the IDE keeps of it. $scope " +
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
        e.presentation.description = "Open Chromium DevTools for this panel."
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
            openCodeBackend(e.project).getServerLogFile() != null
        e.presentation.description = "Open the server log in the editor."
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
        e.presentation.description = "Open plugin settings."
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal fun openCodeBackend(project: Project?): OpenCodeServerBackend {
    return OpenCodeServerBackendRegistry.getInstance().backendFor(project)
}

internal fun ownedSandboxRecord(project: Project?): SbxSandboxRecord? {
    val directory = project?.let {
        OpenCodeProjectSettingsState.getInstance(it).effectiveProjectDirectory(it.basePath)
    } ?: return null
    return SbxSandboxRecordStore.getInstance().recordFor(directory)
}

internal fun openOpenCodeServerLogInEditor(project: Project) {
    val file = openCodeBackend(project).getServerLogFile() ?: return
    runCatching {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: return
        OpenFileDescriptor(project, virtualFile).navigate(true)
    }.onFailure { error ->
        Logger.getInstance(OpenCodeServerBackendRegistry::class.java)
            .warn("Could not open OpenCode server log: ${error.message}")
    }
}
