package de.moritzf.opencodewebpanel.settings

import de.moritzf.opencodewebpanel.configuration.OpenCodeRestartScope
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState

/**
 * Which servers an application-settings Apply restarts: the password reaches every server; an
 * executable or proxy change only the runtime that launches with it. Null: none.
 */
internal fun appRestartScope(
    passwordChanged: Boolean,
    hostChanged: Boolean,
    sandboxChanged: Boolean,
): OpenCodeRestartScope? =
    when {
        passwordChanged || (hostChanged && sandboxChanged) -> OpenCodeRestartScope.ALL
        hostChanged -> OpenCodeRestartScope.NATIVE
        sandboxChanged -> OpenCodeRestartScope.SBX
        else -> null
    }

internal fun OpenCodeRestartScope.covers(backendId: String): Boolean =
    when (this) {
        OpenCodeRestartScope.ALL -> true
        OpenCodeRestartScope.NATIVE -> OpenCodeServerBackend.isNative(backendId)
        OpenCodeRestartScope.SBX -> !OpenCodeServerBackend.isNative(backendId)
    }

/** Restarting in these states interrupts agent work or a start in progress. */
internal fun OpenCodeServerLifecycleState.isInterruptible(): Boolean =
    this == OpenCodeServerLifecycleState.RUNNING ||
        this == OpenCodeServerLifecycleState.STARTING ||
        this == OpenCodeServerLifecycleState.RESTARTING
