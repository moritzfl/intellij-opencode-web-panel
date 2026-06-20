package de.moritzf.opencodewebpanel.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import de.moritzf.opencodewebpanel.toolWindow.OpenCodeServerProtocol

@State(name = "OpenCodeWebPanelSettings", storages = [Storage("opencode-web-panel.xml")])
@Service(Service.Level.APP)
class OpenCodeSettingsState : PersistentStateComponent<OpenCodeSettingsState> {
    var portMode: String = OpenCodePortMode.AUTO.name
    var fixedPort: Int = DEFAULT_FIXED_PORT
    var binaryMode: String = OpenCodeBinaryMode.AUTO.name
    var binaryPath: String = ""
    var openMostRecentConversationOnStartup: Boolean = true
    var uiZoomPercent: Int = DEFAULT_UI_ZOOM_PERCENT
    var openFileLinksInIde: Boolean = true
    var enableCodeNavigation: Boolean = true
    var enableChatFileDrop: Boolean = true
    var forceCompactLayout: Boolean = true
    var suppressProjectSwitchPrompts: Boolean = true
    var openCodeLocalStorageSnapshot: String = "{}"

    override fun getState(): OpenCodeSettingsState = this

    override fun loadState(state: OpenCodeSettingsState) {
        portMode = OpenCodePortMode.fromStorageValue(state.portMode).name
        fixedPort = sanitizePort(state.fixedPort)
        binaryMode = OpenCodeBinaryMode.fromStorageValue(state.binaryMode).name
        binaryPath = state.binaryPath.trim()
        openMostRecentConversationOnStartup = state.openMostRecentConversationOnStartup
        uiZoomPercent = sanitizeUiZoomPercent(state.uiZoomPercent)
        openFileLinksInIde = state.openFileLinksInIde
        enableCodeNavigation = state.enableCodeNavigation
        enableChatFileDrop = state.enableChatFileDrop
        forceCompactLayout = state.forceCompactLayout
        suppressProjectSwitchPrompts = state.suppressProjectSwitchPrompts
        openCodeLocalStorageSnapshot = sanitizeOpenCodeLocalStorageSnapshot(state.openCodeLocalStorageSnapshot)
    }

    fun portModeValue(): OpenCodePortMode = OpenCodePortMode.fromStorageValue(portMode)

    fun binaryModeValue(): OpenCodeBinaryMode = OpenCodeBinaryMode.fromStorageValue(binaryMode)

    fun portArgument(): String {
        return when (portModeValue()) {
            OpenCodePortMode.AUTO -> OpenCodeServerProtocol.DYNAMIC_PORT
            OpenCodePortMode.FIXED -> sanitizePort(fixedPort).toString()
        }
    }

    fun executablePath(): String {
        return when (binaryModeValue()) {
            OpenCodeBinaryMode.AUTO -> OpenCodeServerProtocol.DEFAULT_EXECUTABLE
            OpenCodeBinaryMode.CUSTOM -> binaryPath.ifBlank { OpenCodeServerProtocol.DEFAULT_EXECUTABLE }
        }
    }

    companion object {
        const val DEFAULT_FIXED_PORT = 4096
        const val DEFAULT_UI_ZOOM_PERCENT = 100
        const val MIN_UI_ZOOM_PERCENT = 50
        const val MAX_UI_ZOOM_PERCENT = 200
        const val MAX_OPEN_CODE_LOCAL_STORAGE_SNAPSHOT_CHARS = 2_000_000

        fun getInstance(): OpenCodeSettingsState {
            return ApplicationManager.getApplication().getService(OpenCodeSettingsState::class.java)
        }

        fun sanitizePort(port: Int): Int {
            return port.takeIf { it in 1..65535 } ?: DEFAULT_FIXED_PORT
        }

        fun sanitizeUiZoomPercent(percent: Int): Int {
            return percent.coerceIn(MIN_UI_ZOOM_PERCENT, MAX_UI_ZOOM_PERCENT)
        }

        fun sanitizeOpenCodeLocalStorageSnapshot(snapshot: String?): String {
            val text = snapshot?.trim().orEmpty()
            if (text.isBlank() || text.length > MAX_OPEN_CODE_LOCAL_STORAGE_SNAPSHOT_CHARS) return "{}"
            if (!text.startsWith('{') || !text.endsWith('}')) return "{}"
            return text
        }

    }
}

enum class OpenCodeBinaryMode {
    AUTO,
    CUSTOM,
    ;

    companion object {
        fun fromStorageValue(value: String?): OpenCodeBinaryMode {
            return entries.firstOrNull { it.name == value } ?: AUTO
        }
    }
}

enum class OpenCodePortMode {
    AUTO,
    FIXED,
    ;

    companion object {
        fun fromStorageValue(value: String?): OpenCodePortMode {
            return entries.firstOrNull { it.name == value } ?: AUTO
        }
    }
}
