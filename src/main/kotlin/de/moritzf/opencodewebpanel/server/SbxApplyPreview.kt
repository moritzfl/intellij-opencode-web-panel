package de.moritzf.opencodewebpanel.server

internal enum class SbxApplyEffect {
    NONE,
    LIVE,
    RESTART,
    RECREATE,
}

internal data class SbxApplyChange(
    val summary: String,
    val effect: SbxApplyEffect,
)

internal data class SbxApplyPreview(
    val directory: String,
    val runtimeLabel: String,
    val changes: List<SbxApplyChange>,
    val historyNote: String,
) {
    val effect: SbxApplyEffect
        get() = changes.maxByOrNull { it.effect.ordinal }?.effect ?: SbxApplyEffect.NONE

    fun confirmTitle(): String = when (effect) {
        SbxApplyEffect.RECREATE -> "Recreate sandbox"
        SbxApplyEffect.RESTART -> "Restart OpenCode"
        SbxApplyEffect.LIVE -> "Apply OpenCode settings"
        SbxApplyEffect.NONE -> "Apply OpenCode settings"
    }

    fun message(): String {
        val lines = ArrayList<String>()
        lines += "Directory: $directory"
        lines += "Runtime: $runtimeLabel"
        if (changes.isEmpty()) {
            lines += "No process changes."
        } else {
            lines += "Changes:"
            changes.forEach { change ->
                lines += "• ${change.summary} (${effectLabel(change.effect)})"
            }
        }
        if (historyNote.isNotBlank()) lines += historyNote
        if (effect == SbxApplyEffect.RECREATE) {
            lines += "Cancel leaves settings and the VM unchanged."
        }
        return lines.joinToString("\n")
    }

    companion object {
        /**
         * [oldSpec] is the destination's stored spec, or the defaults the runtime currently uses
         * when none exists yet. [hasVm] says whether this directory owns a sandbox VM: create-time
         * options only need a recreate once one exists.
         */
        fun build(
            directory: String,
            oldSpec: SbxLaunchSpec,
            newSpec: SbxLaunchSpec,
            directoryChanged: Boolean,
            portChanged: Boolean,
            historyNote: String,
            hasVm: Boolean = true,
        ): SbxApplyPreview {
            val changes = ArrayList<SbxApplyChange>()
            if (directoryChanged) changes += SbxApplyChange("OpenCode directory", SbxApplyEffect.RESTART)
            if (oldSpec.useSandbox != newSpec.useSandbox) {
                changes += SbxApplyChange(
                    if (newSpec.useSandbox) "Switch to Docker Sandbox" else "Switch to Host CLI",
                    SbxApplyEffect.RESTART,
                )
            }
            if (portChanged) {
                changes += SbxApplyChange(
                    "Server port",
                    if (newSpec.useSandbox && oldSpec.useSandbox) SbxApplyEffect.LIVE else SbxApplyEffect.RESTART,
                )
            }
            // Sandbox-only options do not affect the Host CLI; they apply once the sandbox is used.
            if (!newSpec.useSandbox) return SbxApplyPreview(directory, runtimeLabel(newSpec), changes, historyNote)
            if (oldSpec.enableIntellijMcp != newSpec.enableIntellijMcp) {
                changes += SbxApplyChange("IntelliJ MCP overlay", SbxApplyEffect.RESTART)
            }
            if (oldSpec.openCodeVersion != newSpec.openCodeVersion) {
                changes += SbxApplyChange(
                    "OpenCode version ${newSpec.openCodeVersion.yamlValue()}",
                    SbxApplyEffect.RESTART,
                )
            }
            if (oldSpec.workingDirectory != newSpec.workingDirectory) {
                changes += SbxApplyChange("OpenCode working directory", SbxApplyEffect.RESTART)
            }
            val recreate = if (hasVm) SbxApplyEffect.RECREATE else SbxApplyEffect.NONE
            val atCreate = if (hasVm) "" else " (applies when the sandbox is created)"
            if (oldSpec.shareHostOpencodeConfig != newSpec.shareHostOpencodeConfig) {
                changes += SbxApplyChange("Host OpenCode config sharing$atCreate", recreate)
            }
            if (newSpec.kits.size < oldSpec.kits.size || newSpec.kits.take(oldSpec.kits.size) != oldSpec.kits) {
                changes += SbxApplyChange("Kits removed or reordered$atCreate", recreate)
            } else if (newSpec.kits.size > oldSpec.kits.size) {
                changes += SbxApplyChange(
                    "Append sandbox kit$atCreate",
                    if (hasVm) SbxApplyEffect.LIVE else SbxApplyEffect.NONE,
                )
            }
            fun sameHost(a: SbxExtraMount, b: SbxExtraMount) = OpenCodeServerProtocol.isSameFilesystemPath(a.hostPath, b.hostPath)
            if (newSpec.extraMounts.any { extra -> oldSpec.extraMounts.none { sameHost(it, extra) } }) {
                changes += SbxApplyChange("New extra mount$atCreate", recreate)
            }
            if (oldSpec.extraMounts.any { old -> newSpec.extraMounts.none { sameHost(it, old) } }) {
                changes += SbxApplyChange("Extra mount removed$atCreate", recreate)
            }
            val kept = newSpec.extraMounts.mapNotNull { extra -> oldSpec.extraMounts.firstOrNull { sameHost(it, extra) }?.let { it to extra } }
            if (kept.any { (old, new) -> old.readOnly != new.readOnly }) {
                changes += SbxApplyChange("Extra mount read-only setting$atCreate", recreate)
            }
            if (kept.any { (old, new) -> old.sandboxPath != new.sandboxPath }) {
                changes += SbxApplyChange("Extra mount sandbox path", SbxApplyEffect.RESTART)
            }
            val atReset = if (hasVm) " (applies at Reset)" else " (applies when the sandbox is created)"
            if (oldSpec.memory != newSpec.memory) {
                changes += SbxApplyChange("Memory ${oldSpec.memory} → ${newSpec.memory}$atReset", SbxApplyEffect.NONE)
            }
            if (oldSpec.cpus != newSpec.cpus) {
                changes += SbxApplyChange("CPUs ${oldSpec.cpus} → ${newSpec.cpus}$atReset", SbxApplyEffect.NONE)
            }
            if (oldSpec.protectSandboxFiles != newSpec.protectSandboxFiles) {
                changes += SbxApplyChange("Protect sandbox files$atReset", SbxApplyEffect.NONE)
            }
            if (oldSpec.persistSandboxSessions != newSpec.persistSandboxSessions) {
                changes += SbxApplyChange("Persist sandbox sessions$atReset", SbxApplyEffect.NONE)
            }
            return SbxApplyPreview(directory, runtimeLabel(newSpec), changes, historyNote)
        }

        fun runtimeLabel(spec: SbxLaunchSpec): String {
            return if (spec.useSandbox) "Docker Sandbox (sbx)" else "Host (native CLI)"
        }

        fun effectLabel(effect: SbxApplyEffect): String = when (effect) {
            SbxApplyEffect.LIVE -> "now"
            SbxApplyEffect.RESTART -> "restart"
            SbxApplyEffect.RECREATE -> "recreate VM"
            SbxApplyEffect.NONE -> "settings"
        }
    }
}
