package de.moritzf.opencodewebpanel.settings

import de.moritzf.opencodewebpanel.server.SbxApplyEffect
import de.moritzf.opencodewebpanel.server.SbxApplyPreview
import de.moritzf.opencodewebpanel.server.SbxLaunchSpec

internal data class OpenCodeProjectSettingsValues(
    val directoryMode: OpenCodeProjectDirectoryMode,
    val customDirectory: String,
    val effectiveDirectory: String?,
    val portMode: OpenCodePortMode,
    val fixedPort: Int,
) {
    fun saveTo(settings: OpenCodeProjectSettingsState) {
        settings.projectDirectoryMode = directoryMode.name
        settings.openCodeProjectDirectory = customDirectory
        settings.portMode = portMode.name
        settings.fixedPort = fixedPort
    }
}

/** Immutable decision made before confirmation or persistence; no Swing or backend access. */
internal data class OpenCodeProjectSettingsApplyPlan(
    val values: OpenCodeProjectSettingsValues,
    val spec: SbxLaunchSpec,
    val preview: SbxApplyPreview,
    val backendChanged: Boolean,
) {
    val recreate: Boolean
        get() = spec.useSandbox && preview.effect == SbxApplyEffect.RECREATE

    val requiresStop: Boolean
        get() = backendChanged || preview.effect >= SbxApplyEffect.RESTART

    /** Stop completion, not STOPPED publication, releases a fixed port for the next backend. */
    fun applyRuntime(
        leavingSharedBackend: Boolean,
        stop: (() -> Unit) -> Unit,
        restart: () -> Unit,
        resetSandbox: () -> Unit,
        applyLive: () -> Unit,
    ) {
        val afterStop = if (recreate) resetSandbox else restart
        when {
            requiresStop && !leavingSharedBackend -> stop(afterStop)
            requiresStop -> afterStop()
            preview.effect == SbxApplyEffect.LIVE -> applyLive()
        }
    }

    companion object {
        fun build(
            values: OpenCodeProjectSettingsValues,
            spec: SbxLaunchSpec,
            baselineSpec: SbxLaunchSpec,
            oldDirectory: String?,
            canonicalOldDirectory: String?,
            oldUsesSandbox: Boolean,
            hasVm: Boolean,
            historyNote: String,
        ): OpenCodeProjectSettingsApplyPlan =
            OpenCodeProjectSettingsApplyPlan(
                values,
                spec,
                SbxApplyPreview.build(
                    directory = spec.canonicalDirectory,
                    oldSpec = baselineSpec,
                    newSpec = spec,
                    directoryChanged =
                        canonicalOldDirectory != null &&
                            canonicalOldDirectory != spec.canonicalDirectory,
                    portChanged = baselineSpec.hostPort != spec.hostPort,
                    historyNote = historyNote,
                    hasVm = hasVm,
                ),
                backendChanged =
                    oldDirectory != values.effectiveDirectory || oldUsesSandbox != spec.useSandbox,
            )
    }
}
