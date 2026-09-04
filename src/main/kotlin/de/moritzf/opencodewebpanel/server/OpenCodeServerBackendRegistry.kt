package de.moritzf.opencodewebpanel.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import de.moritzf.opencodewebpanel.settings.OpenCodeProjectSettingsState

/**
 * Application-wide map from IDE project / canonical directory to the OpenCode backend that
 * owns that origin. Host runtime always returns the native singleton; SBX runtime will
 * return one backend per workspace.
 */
class OpenCodeServerBackendRegistry : Disposable {

    companion object {
        fun getInstance(): OpenCodeServerBackendRegistry {
            return ApplicationManager.getApplication().getService(OpenCodeServerBackendRegistry::class.java)
        }
    }

    fun nativeBackend(): OpenCodeServerBackend = SharedOpenCodeServerManager.getInstance()

    fun backendFor(project: Project?): OpenCodeServerBackend {
        if (project == null || project.isDisposed) return nativeBackend()
        val directory = OpenCodeProjectSettingsState.getInstance(project)
            .effectiveProjectDirectory(project.basePath)
        return backendForCanonicalDirectory(directory)
    }

    fun backendForCanonicalDirectory(canonicalDirectory: String?): OpenCodeServerBackend {
        return nativeBackend()
    }

    fun backend(backendId: String): OpenCodeServerBackend? {
        return if (backendId == OpenCodeServerBackend.NATIVE_ID) nativeBackend() else null
    }

    override fun dispose() = Unit
}
