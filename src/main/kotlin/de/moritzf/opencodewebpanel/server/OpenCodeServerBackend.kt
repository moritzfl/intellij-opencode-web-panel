package de.moritzf.opencodewebpanel.server

import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Process/URL owner behind one OpenCode web origin. Native Host mode is a single
 * application-wide instance; Docker Sandbox mode is one instance per canonical project
 * directory.
 */
interface OpenCodeServerBackend {
    val backendId: String

    /** False when this backend publishes a dynamic host port (SBX) and must not offer Auto/Fixed. */
    val offersHostPortControls: Boolean get() = true

    fun ensureStarted(
        project: Project,
        projectBasePath: String?,
        callbackActive: () -> Boolean = { true },
        onStarted: () -> Unit,
        onFailed: () -> Unit,
    )

    fun stopServer()

    fun restartServer(
        project: Project,
        projectBasePath: String?,
        callbackActive: () -> Boolean = { true },
        onStarted: () -> Unit,
        onFailed: () -> Unit,
    )

    fun getServerUrl(): String?
    fun getServerPassword(): String?
    fun getServerVersion(): String?
    fun getLifecycleState(): OpenCodeServerLifecycleState
    fun getServerGeneration(): Long
    fun getServerGenerationStartedAtMillis(): Long
    fun isServerReadyForAuth(): Boolean
    fun verifyServerNow(callbackActive: () -> Boolean = { true }, onHealthy: () -> Unit)
    fun getServerLogFile(): Path?
    fun consumeUnsupportedServerVersionWarning(): String?
    fun consumeV2ProtocolWarning(): Boolean

    companion object {
        const val NATIVE_ID = "native"
    }
}
