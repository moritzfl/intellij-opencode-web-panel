package de.moritzf.opencodewebpanel.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.SbxCli

// Roaming is disabled deliberately: the state mixes machine-specific values (binary path,
// fixed port) and the mirrored OpenCode settings snapshot (theme/language/model/`settings.v3`),
// none of which belong in Settings Sync or exported settings.
@State(name = "OpenCodeWebPanelSettings", storages = [Storage("opencode-web-panel.xml", roamingType = RoamingType.DISABLED)])
@Service(Service.Level.APP)
class OpenCodeSettingsState : PersistentStateComponent<OpenCodeSettingsState> {
    var runtimeMode: String = OpenCodeRuntimeMode.HOST.name
    var portMode: String = OpenCodePortMode.AUTO.name
    var fixedPort: Int = DEFAULT_FIXED_PORT
    var binaryMode: String = OpenCodeBinaryMode.AUTO.name
    var binaryPath: String = ""
    var sbxBinaryMode: String = OpenCodeBinaryMode.AUTO.name
    var sbxBinaryPath: String = ""
    var sbxNetworkPolicyConsent: Boolean = false
    var sbxMemory: String = SbxCli.DEFAULT_MEMORY
    var sbxCpus: String = SbxCli.DEFAULT_CPUS
    var sbxShareHostOpencodeConfig: Boolean = false
    var sbxEnableIntellijMcp: Boolean = true
    var sbxExtraWorkspaces: String = ""
    var sbxExtraKits: String = ""
    var proxyMode: String = OpenCodeProxyMode.IDE.name
    var uiZoomPercent: Int = DEFAULT_UI_ZOOM_PERCENT
    var openFileLinksInIde: Boolean = true
    var openExternalLinksInBrowser: Boolean = true
    var enableCodeNavigation: Boolean = true
    var openDiffsInIde: Boolean = true
    var enableChatFileDrop: Boolean = true
    var forceCompactLayout: Boolean = true
    var hideWebsiteButton: Boolean = true
    var fasterPathHoverPreview: Boolean = true
    var syncThemeWithIde: Boolean = true
    var suppressProjectSwitchPrompts: Boolean = true
    var mirrorBrowserCursor: Boolean = true
    var recoverStalledEventStream: Boolean = true
    var recoverFailedChunkLoads: Boolean = true
    var recoverStalledRenderer: Boolean = true
    var notifyOpenCodeUpdates: Boolean = true
    var enableSystemNotifications: Boolean = true
    var enablePermissionNotificationActions: Boolean = true
    var showAgentStatusBadge: Boolean = true
    /** Off until the user opts in. Warn when a selected conversation's directory is not this workspace. */
    var warnForeignSession: Boolean = false
    var autoContinueInterruptedSessions: Boolean = true
    var waitForIntellijMcpServer: Boolean = true
    var enableServerLogs: Boolean = true
    var openCodeLocalStorageSnapshot: String = "{}"
    var openCodeLocalStorageSnapshotsByBackend: MutableMap<String, String> = HashMap()

    override fun getState(): OpenCodeSettingsState = this

    override fun loadState(state: OpenCodeSettingsState) {
        runtimeMode = OpenCodeRuntimeMode.fromStorageValue(state.runtimeMode).name
        portMode = OpenCodePortMode.fromStorageValue(state.portMode).name
        fixedPort = sanitizePort(state.fixedPort)
        binaryMode = OpenCodeBinaryMode.fromStorageValue(state.binaryMode).name
        binaryPath = state.binaryPath.trim()
        sbxBinaryMode = OpenCodeBinaryMode.fromStorageValue(state.sbxBinaryMode).name
        sbxBinaryPath = state.sbxBinaryPath.trim()
        sbxNetworkPolicyConsent = state.sbxNetworkPolicyConsent
        sbxMemory = SbxCli.sanitizeMemory(state.sbxMemory)
        sbxCpus = SbxCli.sanitizeCpus(state.sbxCpus)
        sbxShareHostOpencodeConfig = state.sbxShareHostOpencodeConfig
        sbxEnableIntellijMcp = state.sbxEnableIntellijMcp
        sbxExtraWorkspaces = SbxCli.normalizeExtraMountText(state.sbxExtraWorkspaces)
        sbxExtraKits = SbxCli.normalizeLineList(state.sbxExtraKits)
        proxyMode = OpenCodeProxyMode.fromStorageValue(state.proxyMode).name
        uiZoomPercent = sanitizeUiZoomPercent(state.uiZoomPercent)
        openFileLinksInIde = state.openFileLinksInIde
        openExternalLinksInBrowser = state.openExternalLinksInBrowser
        enableCodeNavigation = state.enableCodeNavigation
        openDiffsInIde = state.openDiffsInIde
        enableChatFileDrop = state.enableChatFileDrop
        forceCompactLayout = state.forceCompactLayout
        hideWebsiteButton = state.hideWebsiteButton
        fasterPathHoverPreview = state.fasterPathHoverPreview
        syncThemeWithIde = state.syncThemeWithIde
        suppressProjectSwitchPrompts = state.suppressProjectSwitchPrompts
        mirrorBrowserCursor = state.mirrorBrowserCursor
        recoverStalledEventStream = state.recoverStalledEventStream
        recoverFailedChunkLoads = state.recoverFailedChunkLoads
        recoverStalledRenderer = state.recoverStalledRenderer
        notifyOpenCodeUpdates = state.notifyOpenCodeUpdates
        enableSystemNotifications = state.enableSystemNotifications
        enablePermissionNotificationActions = state.enablePermissionNotificationActions
        showAgentStatusBadge = state.showAgentStatusBadge
        warnForeignSession = state.warnForeignSession
        autoContinueInterruptedSessions = state.autoContinueInterruptedSessions
        waitForIntellijMcpServer = state.waitForIntellijMcpServer
        enableServerLogs = state.enableServerLogs
        openCodeLocalStorageSnapshot = sanitizeOpenCodeLocalStorageSnapshot(state.openCodeLocalStorageSnapshot)
        val legacySnapshots = sanitizeLocalStorageSnapshotsByBackend(
            state.openCodeLocalStorageSnapshotsByBackend,
        )
        if (openCodeLocalStorageSnapshot == "{}") {
            openCodeLocalStorageSnapshot = firstNonEmptySnapshot(legacySnapshots.values)
        }
        openCodeLocalStorageSnapshotsByBackend = HashMap()
    }

    fun localStorageSnapshot(backendId: String = OpenCodeServerBackend.NATIVE_ID): String {
        val shared = sanitizeOpenCodeLocalStorageSnapshot(openCodeLocalStorageSnapshot)
        if (shared != "{}") return shared
        val keyed = sanitizeOpenCodeLocalStorageSnapshot(openCodeLocalStorageSnapshotsByBackend[backendId])
        if (keyed != "{}") return keyed
        return firstNonEmptySnapshot(openCodeLocalStorageSnapshotsByBackend.values)
    }

    @Suppress("UNUSED_PARAMETER")
    fun setLocalStorageSnapshot(backendId: String, snapshot: String) {
        openCodeLocalStorageSnapshot = sanitizeOpenCodeLocalStorageSnapshot(snapshot)
        openCodeLocalStorageSnapshotsByBackend.clear()
    }

    fun runtimeModeValue(): OpenCodeRuntimeMode = OpenCodeRuntimeMode.fromStorageValue(runtimeMode)

    fun portModeValue(): OpenCodePortMode = OpenCodePortMode.fromStorageValue(portMode)

    fun binaryModeValue(): OpenCodeBinaryMode = OpenCodeBinaryMode.fromStorageValue(binaryMode)

    fun sbxBinaryModeValue(): OpenCodeBinaryMode = OpenCodeBinaryMode.fromStorageValue(sbxBinaryMode)

    fun proxyModeValue(): OpenCodeProxyMode = OpenCodeProxyMode.fromStorageValue(proxyMode)

    fun portArgument(): String {
        return when (portModeValue()) {
            OpenCodePortMode.AUTO -> OpenCodeServerProtocol.DYNAMIC_PORT
            OpenCodePortMode.FIXED -> sanitizePort(fixedPort).toString()
        }
    }

    fun hostPortOrNull(): Int? {
        return if (portModeValue() == OpenCodePortMode.FIXED) sanitizePort(fixedPort) else null
    }

    fun executablePath(): String {
        return when (binaryModeValue()) {
            OpenCodeBinaryMode.AUTO -> OpenCodeServerProtocol.DEFAULT_EXECUTABLE
            OpenCodeBinaryMode.CUSTOM -> binaryPath.ifBlank { OpenCodeServerProtocol.DEFAULT_EXECUTABLE }
        }
    }

    fun sbxExecutablePath(): String {
        return when (sbxBinaryModeValue()) {
            OpenCodeBinaryMode.AUTO -> SbxCli.DEFAULT_EXECUTABLE
            OpenCodeBinaryMode.CUSTOM -> sbxBinaryPath.ifBlank { SbxCli.DEFAULT_EXECUTABLE }
        }
    }

    fun sbxMemoryValue(): String = SbxCli.sanitizeMemory(sbxMemory)

    fun sbxCpusValue(): String = SbxCli.sanitizeCpus(sbxCpus)

    fun effectiveCodeNavigationEnabled(): Boolean {
        return openFileLinksInIde && enableCodeNavigation
    }

    companion object {
        const val DEFAULT_FIXED_PORT = 4096
        const val DEFAULT_UI_ZOOM_PERCENT = 100
        const val MIN_UI_ZOOM_PERCENT = 50
        const val MAX_UI_ZOOM_PERCENT = 200
        private const val MAX_OPEN_CODE_LOCAL_STORAGE_SNAPSHOT_CHARS = 2_000_000

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

        fun sanitizeLocalStorageSnapshotsByBackend(source: Map<String, String>?): MutableMap<String, String> {
            val result = HashMap<String, String>()
            source.orEmpty().forEach { (key, value) ->
                val id = key.trim()
                if (id.isEmpty() || id == OpenCodeServerBackend.NATIVE_ID) return@forEach
                val sanitized = sanitizeOpenCodeLocalStorageSnapshot(value)
                if (sanitized != "{}") result[id] = sanitized
            }
            return result
        }

        private fun firstNonEmptySnapshot(source: Collection<String>): String {
            return source.map(::sanitizeOpenCodeLocalStorageSnapshot).firstOrNull { it != "{}" } ?: "{}"
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

enum class OpenCodeRuntimeMode {
    HOST,
    DOCKER_SANDBOX,
    ;

    companion object {
        fun fromStorageValue(value: String?): OpenCodeRuntimeMode {
            return entries.firstOrNull { it.name == value } ?: HOST
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

enum class OpenCodeProxyMode {
    IDE,
    ENVIRONMENT,
    NONE,
    ;

    companion object {
        fun fromStorageValue(value: String?): OpenCodeProxyMode {
            return entries.firstOrNull { it.name == value } ?: IDE
        }
    }
}
