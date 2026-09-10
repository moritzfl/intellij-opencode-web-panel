package de.moritzf.opencodewebpanel.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsState
import de.moritzf.opencodewebpanel.settings.OpenCodeRuntimeMode
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState

/**
 * Application-wide map from canonical directory to the OpenCode backend that owns that origin.
 * Host CLI and Docker Sandbox are both one process per workspace.
 */
class OpenCodeServerBackendRegistry : Disposable {

    private val lock = Any()
    private val nativeBackends = linkedMapOf<String, SharedOpenCodeServerManager>()
    private val sbxBackends = linkedMapOf<String, SbxOpenCodeServerBackend>()

    companion object {
        fun getInstance(): OpenCodeServerBackendRegistry {
            return ApplicationManager.getApplication().getService(OpenCodeServerBackendRegistry::class.java)
        }
    }

    fun nativeBackend(): OpenCodeServerBackend {
        return backendForCanonicalDirectory(null)
    }

    fun runtimeMode(): OpenCodeRuntimeMode = OpenCodeSettingsState.getInstance().runtimeModeValue()

    fun backendFor(project: Project?): OpenCodeServerBackend {
        if (project == null || project.isDisposed) return nativeBackend()
        val directory = OpenCodeProjectSettingsState.getInstance(project)
            .effectiveProjectDirectory(project.basePath)
        return backendForCanonicalDirectory(directory)
    }

    fun backendForCanonicalDirectory(canonicalDirectory: String?): OpenCodeServerBackend {
        val directory = OpenCodeServerProtocol.canonicalOpenCodeDirectory(canonicalDirectory)
            ?: canonicalDirectory?.trim()?.takeIf { it.isNotBlank() }
        if (directory == null) {
            synchronized(lock) {
                return nativeBackends.getOrPut("") { SharedOpenCodeServerManager("") }
            }
        }
        if (SbxLaunchSpec.usesSandbox(directory)) {
            val key = OpenCodeServerProtocol.filesystemPathKey(directory) ?: directory
            synchronized(lock) {
                return sbxBackends.getOrPut(key) { SbxOpenCodeServerBackend(directory) }
            }
        }
        val key = OpenCodeServerProtocol.filesystemPathKey(directory) ?: directory
        synchronized(lock) {
            return nativeBackends.getOrPut(key) { SharedOpenCodeServerManager(directory) }
        }
    }

    fun backend(backendId: String): OpenCodeServerBackend? {
        synchronized(lock) {
            nativeBackends.values.firstOrNull { it.backendId == backendId }?.let { return it }
            return sbxBackends.values.firstOrNull { it.backendId == backendId }
        }
    }

    fun stopAllNativeBackends() {
        val backends = synchronized(lock) { nativeBackends.values.toList() }
        backends.forEach { it.stopServer() }
    }

    fun stopAllSbxBackends() {
        val backends = synchronized(lock) { sbxBackends.values.toList() }
        backends.forEach { it.stopServer() }
    }

    override fun dispose() {
        val natives: List<SharedOpenCodeServerManager>
        val sandboxes: List<SbxOpenCodeServerBackend>
        synchronized(lock) {
            natives = nativeBackends.values.toList().also { nativeBackends.clear() }
            sandboxes = sbxBackends.values.toList().also { sbxBackends.clear() }
        }
        natives.forEach { it.dispose() }
        sandboxes.forEach { it.dispose() }
    }
}
