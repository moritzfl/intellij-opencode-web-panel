package de.moritzf.opencodewebpanel.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil

@State(name = "OpenCodeWebPanelProjectSettings", storages = [Storage("opencode-web-panel-project.xml")])
@Service(Service.Level.PROJECT)
class OpenCodeProjectSettingsState : PersistentStateComponent<OpenCodeProjectSettingsState> {
    var projectDirectoryMode: String = OpenCodeProjectDirectoryMode.AUTO.name
    var openCodeProjectDirectory: String = ""

    override fun getState(): OpenCodeProjectSettingsState = this

    override fun loadState(state: OpenCodeProjectSettingsState) {
        projectDirectoryMode = OpenCodeProjectDirectoryMode.fromStorageValue(state.projectDirectoryMode).name
        openCodeProjectDirectory = sanitizeProjectDirectory(state.openCodeProjectDirectory)
    }

    fun projectDirectoryModeValue(): OpenCodeProjectDirectoryMode {
        return OpenCodeProjectDirectoryMode.fromStorageValue(projectDirectoryMode)
    }

    fun effectiveProjectDirectory(ideProjectBasePath: String?): String? {
        return when (projectDirectoryModeValue()) {
            OpenCodeProjectDirectoryMode.AUTO -> autoDetectedProjectDirectory(ideProjectBasePath)
            OpenCodeProjectDirectoryMode.CUSTOM -> openCodeProjectDirectory.ifBlank { autoDetectedProjectDirectory(ideProjectBasePath).orEmpty() }.ifBlank { null }
        }
    }

    companion object {
        fun getInstance(project: Project): OpenCodeProjectSettingsState {
            return project.getService(OpenCodeProjectSettingsState::class.java)
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
