package de.moritzf.opencodewebpanel.server

import com.intellij.openapi.application.ApplicationManager

/** A single lock-consistent view of one running process. Never log its credentials. */
data class OpenCodeServerConnection(
    val url: String,
    val password: String,
    val version: String?,
    val wireProtocol: OpenCodeWireProtocol,
    val generation: Long,
    val startedAtMillis: Long,
) {
    fun sameProcess(other: OpenCodeServerConnection?): Boolean =
        other != null &&
            url == other.url &&
            password == other.password &&
            generation == other.generation

    override fun toString(): String =
        "OpenCodeServerConnection(url=$url, version=$version, wireProtocol=$wireProtocol, generation=$generation)"
}

/** Backend-local warning history. Call under the backend's existing lifecycle lock. */
internal class OpenCodeBackendWarnings {
    private var unsupportedVersion: String? = null
    private var embeddedV2Shown = false

    fun consumeUnsupportedVersion(reportedVersion: String?): String? {
        val version = reportedVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (
            !OpenCodeServerProtocol.isOpenCodeVersionUnsupported(version) ||
                unsupportedVersion == version
        )
            return null
        unsupportedVersion = version
        return version
    }

    fun consumeEmbeddedV2(protocol: OpenCodeWireProtocol): Boolean {
        if (embeddedV2Shown || protocol != OpenCodeWireProtocol.V1_18_EMBEDDED_V2) return false
        embeddedV2Shown = true
        return true
    }
}

internal data class OpenCodeStartCallback(
    val isActive: () -> Boolean,
    val onStarted: () -> Unit,
    val onFailed: () -> Unit,
)

/**
 * Recheck eligibility at delivery time: a panel may close while its completion waits on the EDT.
 */
internal fun notifyOpenCodeStartCallbacks(
    callbacks: List<OpenCodeStartCallback>,
    success: Boolean,
    dispatch: (() -> Unit) -> Unit = { ApplicationManager.getApplication().invokeLater(it) },
) {
    if (callbacks.isEmpty()) return
    dispatch {
        callbacks.forEach { callback ->
            if (callback.isActive()) {
                if (success) callback.onStarted() else callback.onFailed()
            }
        }
    }
}
