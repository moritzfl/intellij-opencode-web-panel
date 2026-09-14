package de.moritzf.opencodewebpanel.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol

// Shareable .idea file for team-visible project settings (custom OpenCode directory, XML port
// fallback). Path-macro substitution keeps project-relative paths portable. getState() is null
// while those stay at defaults so a one-shot in-memory port-import flag cannot create the file.
@State(name = "OpenCodeWebPanelProjectSettings", storages = [Storage("opencode-web-panel-project.xml")])
@Service(Service.Level.PROJECT)
class OpenCodeProjectSettingsState : PersistentStateComponent<OpenCodeProjectSettingsState> {
    var projectDirectoryMode: String = OpenCodeProjectDirectoryMode.AUTO.name
    var openCodeProjectDirectory: String = ""
    var portMode: String = OpenCodePortMode.AUTO.name
    var fixedPort: Int = OpenCodeSettingsState.DEFAULT_FIXED_PORT
    var portImportedFromApplication: Boolean = false

    override fun getState(): OpenCodeProjectSettingsState? {
        return if (hasShareableSettings()) this else null
    }

    private fun hasShareableSettings(): Boolean {
        return projectDirectoryModeValue() != OpenCodeProjectDirectoryMode.AUTO ||
            portModeValue() != OpenCodePortMode.AUTO
    }

    override fun loadState(state: OpenCodeProjectSettingsState) {
        projectDirectoryMode = OpenCodeProjectDirectoryMode.fromStorageValue(state.projectDirectoryMode).name
        openCodeProjectDirectory = sanitizeProjectDirectory(state.openCodeProjectDirectory)
        portMode = OpenCodePortMode.fromStorageValue(state.portMode).name
        fixedPort = OpenCodeSettingsState.sanitizePort(state.fixedPort)
        portImportedFromApplication = state.portImportedFromApplication
    }

    fun projectDirectoryModeValue(): OpenCodeProjectDirectoryMode {
        return OpenCodeProjectDirectoryMode.fromStorageValue(projectDirectoryMode)
    }

    fun portModeValue(): OpenCodePortMode = OpenCodePortMode.fromStorageValue(portMode)

    fun portArgument(): String {
        return when (portModeValue()) {
            OpenCodePortMode.AUTO -> OpenCodeServerProtocol.DYNAMIC_PORT
            OpenCodePortMode.FIXED -> OpenCodeSettingsState.sanitizePort(fixedPort).toString()
        }
    }

    fun hostPortOrNull(): Int? {
        return if (portModeValue() == OpenCodePortMode.FIXED) {
            OpenCodeSettingsState.sanitizePort(fixedPort)
        } else {
            null
        }
    }

    private fun importLegacyApplicationPortIfNeeded() {
        if (portImportedFromApplication) return
        portImportedFromApplication = true
        if (portModeValue() != OpenCodePortMode.AUTO) return
        if (OpenCodeSettingsState.sanitizePort(fixedPort) != OpenCodeSettingsState.DEFAULT_FIXED_PORT) return
        val app = OpenCodeSettingsState.getInstance()
        portMode = app.portMode
        fixedPort = OpenCodeSettingsState.sanitizePort(app.fixedPort)
    }

    fun effectiveProjectDirectory(ideProjectBasePath: String?): String? {
        return when (projectDirectoryModeValue()) {
            OpenCodeProjectDirectoryMode.AUTO -> autoDetectedProjectDirectory(ideProjectBasePath)
            OpenCodeProjectDirectoryMode.CUSTOM -> openCodeProjectDirectory.ifBlank { autoDetectedProjectDirectory(ideProjectBasePath).orEmpty() }.ifBlank { null }
        }
    }

    companion object {
        fun getInstance(project: Project): OpenCodeProjectSettingsState {
            val state = project.getService(OpenCodeProjectSettingsState::class.java)
            state.importLegacyApplicationPortIfNeeded()
            return state
        }

        fun sanitizeProjectDirectory(directory: String?): String {
            return FileUtil.toSystemIndependentName(directory?.trim().orEmpty())
        }

        fun autoDetectedProjectDirectory(ideProjectBasePath: String?): String? {
            return ideProjectBasePath?.trim()?.ifBlank { null }
        }
    }
}

enum class OpenCodeProjectDirectoryMode {
    AUTO,
    CUSTOM,
    ;

    companion object {
        fun fromStorageValue(value: String?): OpenCodeProjectDirectoryMode {
            return entries.firstOrNull { it.name == value } ?: AUTO
        }
    }
}
