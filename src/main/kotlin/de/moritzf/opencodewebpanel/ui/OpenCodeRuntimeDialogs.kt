package de.moritzf.opencodewebpanel.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import de.moritzf.opencodewebpanel.configuration.OpenCodeProjectSettingsState
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.SbxCli
import de.moritzf.opencodewebpanel.server.SbxExposure
import de.moritzf.opencodewebpanel.server.isOpenCodeServerStopEnabled
import java.awt.Component

/**
 * Restarting interrupts work on this project's server, so a running server requires explicit
 * confirmation. Restarting a stopped or failed server loses nothing and proceeds without a prompt.
 */
internal fun confirmOpenCodeServerRestart(project: Project?): Boolean {
    val backend = OpenCodeServerBackendRegistry.getInstance().backendFor(project)
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

/**
 * Application settings change how every server of a runtime starts, so Apply restarts the running
 * ones in all open projects. Nothing is running: no prompt.
 */
internal fun confirmOpenCodeServersRestart(
    parent: Component?,
    hostServers: Int,
    sandboxServers: Int,
): Boolean {
    val message = openCodeServersRestartMessage(hostServers, sandboxServers) ?: return true
    return MessageDialogBuilder.yesNo("Restart OpenCode Servers", message)
        .yesText("Apply and Restart")
        .noText("Cancel")
        .icon(Messages.getWarningIcon())
        .ask(parent)
}

internal fun openCodeServersRestartMessage(hostServers: Int, sandboxServers: Int): String? {
    val total = hostServers + sandboxServers
    if (total <= 0) return null
    val runtimes =
        listOfNotNull(
                hostServers.takeIf { it > 0 }?.let { "$it Host CLI" },
                sandboxServers.takeIf { it > 0 }?.let { "$it Docker Sandbox" },
            )
            .joinToString(", ")
    val servers =
        if (total == 1) "the running OpenCode server" else "$total running OpenCode servers"
    return "Applying restarts $servers ($runtimes) in all open projects. " +
        "Agent work in progress is interrupted; conversations are kept."
}

internal fun confirmOpenCodeServerStop(project: Project?): Boolean {
    val state = OpenCodeServerBackendRegistry.getInstance().backendFor(project).getLifecycleState()
    if (!isOpenCodeServerStopEnabled(state)) return true
    val consequence =
        when (state) {
            OpenCodeServerLifecycleState.RUNNING ->
                "Stopping it interrupts OpenCode work in this project only."
            OpenCodeServerLifecycleState.RESTARTING ->
                "Stopping cancels the restart that is currently in progress."
            else -> "Stopping cancels the start that is currently in progress."
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

/** [dockerKit]: a 2.x sandbox, whose OpenCode comes from Docker's image rather than npm. */
internal fun confirmOpenCodeSandboxBinaryUpgrade(
    project: Project?,
    dockerKit: Boolean = false,
): Boolean {
    val message =
        if (dockerKit) {
            "This installs the latest OpenCode 2 release inside the existing sandbox with " +
                "OpenCode's own installer and restarts OpenCode. The VM and sessions stay. " +
                "Reset Sandbox later returns to Docker's OpenCode image. Needs sandbox network " +
                "access to opencode.ai and registry.npmjs.org, which Docker's OpenCode kit allows."
        } else {
            "This runs opencode upgrade inside the existing sandbox and restarts serve. " +
                "The VM and sessions stay. Needs sandbox network access."
        }
    return MessageDialogBuilder.yesNo("Upgrade OpenCode in Sandbox", message)
        .yesText("Upgrade")
        .noText("Cancel")
        .icon(Messages.getInformationIcon())
        .ask(project)
}

internal enum class OpenCodeSandboxUpdateChoice {
    UPGRADE_IN_SANDBOX,
    RESET_SANDBOX,
}

private const val UPGRADE_IN_SANDBOX = "Upgrade in Sandbox"
private const val RESET_SANDBOX = "Reset Sandbox"

/**
 * A 2.x sandbox can install [latest] inside the VM, or be recreated from Docker's image when that
 * image ([dockerImage]) is newer than [installed]. [pinnedImage] is the project's Docker image pin;
 * Reset returns to it, so it is never offered as an update. Choosing Reset is the destructive
 * confirmation; the text states what is dropped. Null when cancelled.
 */
internal fun chooseOpenCodeSandboxUpdate(
    project: Project?,
    installed: String,
    latest: String,
    dockerImage: String?,
    pinnedImage: String? = null,
): OpenCodeSandboxUpdateChoice? {
    val retention =
        project?.let {
            OpenCodeProjectSettingsState.getInstance(it)
                .effectiveProjectDirectory(it.basePath)
                ?.let(::sandboxSessionRetentionSummary)
        } ?: "Conversation history retention is unknown until the VM is inspected."
    val buttons =
        if (dockerImage != null)
            arrayOf(UPGRADE_IN_SANDBOX, RESET_SANDBOX, Messages.getCancelButton())
        else arrayOf(UPGRADE_IN_SANDBOX, Messages.getCancelButton())
    // Messages.showDialog rather than MessageDialogBuilder.Message, which is experimental API.
    val chosen =
        Messages.showDialog(
            project,
            openCodeSandboxUpdateMessage(installed, latest, dockerImage, retention, pinnedImage),
            "OpenCode Update",
            buttons,
            0,
            Messages.getInformationIcon(),
        )
    return when (buttons.getOrNull(chosen)) {
        UPGRADE_IN_SANDBOX -> OpenCodeSandboxUpdateChoice.UPGRADE_IN_SANDBOX
        RESET_SANDBOX -> OpenCodeSandboxUpdateChoice.RESET_SANDBOX
        else -> null
    }
}

internal fun openCodeSandboxUpdateMessage(
    installed: String,
    latest: String,
    dockerImage: String?,
    retention: String,
    pinnedImage: String? = null,
): String {
    val upgrade =
        "$UPGRADE_IN_SANDBOX installs OpenCode $latest inside this VM with OpenCode's own " +
            "installer and restarts OpenCode. The VM and sessions stay; a later Reset Sandbox " +
            "returns to Docker's image."
    val reset =
        when {
            pinnedImage != null ->
                "This project pins Docker's OpenCode image to $pinnedImage, so Reset Sandbox " +
                    "would not update OpenCode."
            dockerImage != null ->
                "$RESET_SANDBOX recreates the VM from Docker's OpenCode image, which has " +
                    "OpenCode $dockerImage. Packages and other VM-only state are dropped. $retention"
            else ->
                "Docker's OpenCode image has no newer release yet, so Reset Sandbox would not " +
                    "update OpenCode."
        }
    return "OpenCode $latest is available. This sandbox runs $installed.\n\n$upgrade\n\n$reset"
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
            "Create New Sandbox",
            "This force-removes the unmatched sandbox at this name or workspace, then creates one owned by this panel. " +
                "Sessions in that VM are dropped.",
        )
        .yesText("Create New Sandbox")
        .noText("Cancel")
        .icon(Messages.getWarningIcon())
        .ask(project)
}

internal fun confirmOpenCodeSandboxRecreate(project: Project?, reasons: List<String>): Boolean {
    val retention =
        project?.let {
            OpenCodeProjectSettingsState.getInstance(it)
                .effectiveProjectDirectory(it.basePath)
                ?.let(::sandboxSessionRetentionSummary)
        } ?: "Conversation history retention is unknown until the VM is inspected."
    return MessageDialogBuilder.yesNo(
            "Recreate Sandbox",
            "The sandbox differs from opencode-sbx.yaml:\n" +
                reasons.joinToString("\n") { "• $it" } +
                "\n\nRecreating removes the VM and creates a new one from the file, like Reset Sandbox. " +
                "Packages and other VM-only state are dropped. $retention",
        )
        .yesText("Recreate Sandbox")
        .noText("Cancel")
        .icon(Messages.getWarningIcon())
        .ask(project)
}

internal fun confirmOpenCodeSandboxReset(project: Project?): Boolean {
    val retention =
        project?.let {
            OpenCodeProjectSettingsState.getInstance(it)
                .effectiveProjectDirectory(it.basePath)
                ?.let(::sandboxSessionRetentionSummary)
        } ?: "Conversation history retention is unknown until the VM is inspected."
    return MessageDialogBuilder.yesNo(
            "Reset Sandbox",
            "This force-removes the plugin-owned Docker Sandbox VM and creates a new one from Docker's " +
                "selected OpenCode sandbox kit (2.x: the latest or pinned image), then starts OpenCode. " +
                "Packages, in-VM OpenCode upgrades and other VM-only state are dropped. $retention " +
                "Host OpenCode history is not affected.",
        )
        .yesText("Reset Sandbox")
        .noText("Cancel")
        .icon(Messages.getWarningIcon())
        .ask(project)
}

internal fun sandboxSessionRetentionSummary(canonicalDirectory: String): String {
    val record =
        de.moritzf.opencodewebpanel.server.SbxSandboxRecordStore.getInstance()
            .recordFor(canonicalDirectory) ?: return "There is no plugin-owned sandbox yet."
    val persistHost = SbxCli.sandboxPersistDataHome(record.name)
    return if (SbxCli.recordHasPersistMount(record, persistHost)) {
        "Conversation history is on the host persist mount and should survive recreation."
    } else {
        "Conversation history in this VM will be dropped."
    }
}
