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

internal fun confirmOpenCodeSandboxImageUpdate(project: Project?): Boolean {
    val retention =
        project?.let {
            OpenCodeProjectSettingsState.getInstance(it)
                .effectiveProjectDirectory(it.basePath)
                ?.let(::sandboxSessionRetentionSummary)
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
                "\n\nRecreating removes the VM and creates a new one from the file. " +
                "Packages and other VM-only state are dropped. $retention",
        )
        .yesText("Recreate")
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
