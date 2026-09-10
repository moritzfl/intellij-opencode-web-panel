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
        fun build(
            directory: String,
            oldSpec: SbxLaunchSpec?,
            newSpec: SbxLaunchSpec,
            directoryChanged: Boolean,
            portChanged: Boolean,
            historyNote: String,
        ): SbxApplyPreview {
            val changes = ArrayList<SbxApplyChange>()
            if (directoryChanged) changes += SbxApplyChange("OpenCode directory", SbxApplyEffect.RESTART)
            if (oldSpec == null) {
                if (newSpec.useSandbox) changes += SbxApplyChange("Enable Docker Sandbox", SbxApplyEffect.RESTART)
                return SbxApplyPreview(directory, runtimeLabel(newSpec), changes, historyNote)
            }
            if (oldSpec.useSandbox != newSpec.useSandbox) {
                changes += SbxApplyChange(
                    if (newSpec.useSandbox) "Switch to Docker Sandbox" else "Switch to Host CLI",
                    SbxApplyEffect.RESTART,
                )
            }
            if (portChanged) {
                changes += SbxApplyChange(
                    "Server port",
                    if (newSpec.useSandbox) SbxApplyEffect.LIVE else SbxApplyEffect.RESTART,
                )
            }
            if (oldSpec.enableIntellijMcp != newSpec.enableIntellijMcp) {
                changes += SbxApplyChange("IntelliJ MCP overlay", SbxApplyEffect.RESTART)
            }
            if (oldSpec.shareHostOpencodeConfig != newSpec.shareHostOpencodeConfig) {
                changes += SbxApplyChange("Host OpenCode config sharing", SbxApplyEffect.RECREATE)
            }
            if (newSpec.kits.size < oldSpec.kits.size || newSpec.kits.take(oldSpec.kits.size) != oldSpec.kits) {
                changes += SbxApplyChange("Kits removed or reordered", SbxApplyEffect.RECREATE)
            } else if (newSpec.kits.size > oldSpec.kits.size) {
                changes += SbxApplyChange("Append sandbox kit", SbxApplyEffect.LIVE)
            }
            if (newSpec.extraMounts.any { extra ->
                    oldSpec.extraMounts.none { OpenCodeServerProtocol.isSameFilesystemPath(it.hostPath, extra.hostPath) }
                }
            ) {
                changes += SbxApplyChange("New extra mount", SbxApplyEffect.RECREATE)
            }
            if (oldSpec.memory != newSpec.memory) {
                changes += SbxApplyChange("Memory ${oldSpec.memory} → ${newSpec.memory} (applies at Reset)", SbxApplyEffect.NONE)
            }
            if (oldSpec.cpus != newSpec.cpus) {
                changes += SbxApplyChange("CPUs ${oldSpec.cpus} → ${newSpec.cpus} (applies at Reset)", SbxApplyEffect.NONE)
            }
            if (oldSpec.protectSandboxFiles != newSpec.protectSandboxFiles) {
                changes += SbxApplyChange("Protect sandbox files (applies at Reset)", SbxApplyEffect.NONE)
            }
            if (oldSpec.persistSandboxSessions != newSpec.persistSandboxSessions) {
                changes += SbxApplyChange("Persist sandbox sessions (applies at Reset)", SbxApplyEffect.NONE)
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
