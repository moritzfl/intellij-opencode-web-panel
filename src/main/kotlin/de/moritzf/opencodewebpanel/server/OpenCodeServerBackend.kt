package de.moritzf.opencodewebpanel.server

import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Process/URL owner behind one OpenCode web origin. One instance per canonical
 * project directory for both Host CLI and Docker Sandbox.
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

    /** [onStopped] runs after process cleanup, not merely after publishing STOPPED. */
    fun stopServer(onStopped: () -> Unit = {})

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
    fun consumeCreateStaleWarning(): List<String> = emptyList()
    fun startFailureMessage(): String? = null

    companion object {
        const val NATIVE_ID = "native"
        const val NATIVE_ID_PREFIX = "native:"

        fun isNative(backendId: String): Boolean {
            return backendId == NATIVE_ID || backendId.startsWith(NATIVE_ID_PREFIX)
        }

        fun nativeBackendId(canonicalDirectory: String): String {
            val directory = canonicalDirectory.trim().ifBlank { "unbound" }
            return NATIVE_ID_PREFIX + SbxCli.sandboxName(directory)
        }
    }
}
