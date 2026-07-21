package de.moritzf.opencodewebpanel.features

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEvent
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEventListener
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager
import de.moritzf.opencodewebpanel.server.objectMember
import de.moritzf.opencodewebpanel.server.stringMember
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import org.jetbrains.annotations.TestOnly

/**
 * Plays OpenCode's configured notification sounds for agent-idle, permission, and error events.
 *
 * Uses the JVM `/global/event` stream (same source as system notifications) and the mirrored
 * `settings.v3` sound preferences, so cues work even when the embedded page's HTMLAudioElement
 * path stays silent.
 */
internal object OpenCodeSoundService {
    private val lock = Any()
    private var busConnection: com.intellij.openapi.Disposable? = null
    private val eventExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor(
        "OpenCode Sound Events",
        1,
    )
    private data class IdleKey(val directory: String, val sessionID: String)
    private val idleSessions = mutableSetOf<IdleKey>()

    /**
     * Subscribes to the application event bus. Safe to call repeatedly: re-subscribes after the
     * parent [SharedOpenCodeServerManager] is disposed (dynamic plugin reload / IDE restart).
     */
    fun ensureInstalled() {
        synchronized(lock) {
            if (busConnection != null) return
            val parent = SharedOpenCodeServerManager.getInstance()
            val connection = ApplicationManager.getApplication().messageBus.connect(parent)
            connection.subscribe(
                OpenCodeGlobalEventListener.TOPIC,
                object : OpenCodeGlobalEventListener {
                    override fun eventReceived(event: OpenCodeGlobalEvent) {
                        eventExecutor.execute { handleEvent(event) }
                    }
                },
            )
            Disposer.register(connection) {
                synchronized(lock) {
                    if (busConnection === connection) busConnection = null
                }
            }
            busConnection = connection
            thisLogger().info("OpenCode sound service subscribed to global events")
        }
    }

    @TestOnly
    internal fun resetForTests() {
        synchronized(idleSessions) {
            idleSessions.clear()
        }
    }

    internal fun handleEvent(
        event: OpenCodeGlobalEvent,
        settings: OpenCodeSoundSettings = currentSettings(),
        fetchSession: (directory: String, sessionID: String) -> OpenCodeServerProtocol.SessionInfo? =
            ::fetchSessionInfo,
        play: (String?) -> Unit = OpenCodeSoundPlayer::playById,
    ) {
        var type = event.type
        if (type == "session.status") {
            val statusType = event.properties.objectMember("status")?.stringMember("type")
            if (statusType == "busy" || statusType == "retry") {
                event.properties.stringMember("sessionID")
                    ?.takeIf(OpenCodeServerProtocol::isSessionId)
                    ?.let { markBusy(event.directory, it) }
                return
            }
            if (statusType != "idle") return
            type = "session.idle"
        }
        when (type) {
            "session.idle" -> {
                if (!settings.agentEnabled) return
                val sessionID = event.properties.stringMember("sessionID") ?: return
                if (!OpenCodeServerProtocol.isSessionId(sessionID)) return
                val session = fetchSession(event.directory, sessionID)
                // Skip child/subagent sessions and unresolved lookups, mirroring OpenCode's own
                // `if (!session || session.parentID) return`; the session fetch is reliable.
                if (session == null || session.parentID != null) return
                if (!markIdle(event.directory, sessionID)) return
                play(settings.agent)
            }
            "session.error" -> {
                if (!settings.errorsEnabled) return
                val sessionID = event.properties.stringMember("sessionID")
                if (!sessionID.isNullOrBlank()) {
                    if (!OpenCodeServerProtocol.isSessionId(sessionID)) return
                    val session = fetchSession(event.directory, sessionID)
                    if (session?.parentID != null) return
                }
                play(settings.errors)
            }
            "permission.asked" -> {
                if (!settings.permissionsEnabled) return
                play(settings.permissions)
            }
            else -> return
        }
    }

    private fun currentSettings(): OpenCodeSoundSettings {
        return parseOpenCodeSoundSettings(OpenCodeSettingsState.getInstance().openCodeLocalStorageSnapshot)
    }

    private fun fetchSessionInfo(directory: String, sessionID: String): OpenCodeServerProtocol.SessionInfo? {
        val serverManager = SharedOpenCodeServerManager.getInstance()
        val serverUrl = serverManager.getServerUrl() ?: return null
        val password = serverManager.getServerPassword() ?: return null
        return OpenCodeServerProtocol.fetchSessionInfo(
            serverUrl,
            OpenCodeServerProtocol.buildBasicAuthHeader(password),
            directory,
            sessionID,
        )
    }

    private fun markIdle(directory: String, sessionID: String): Boolean {
        val key = IdleKey(directory, sessionID)
        synchronized(idleSessions) {
            return idleSessions.add(key)
        }
    }

    private fun markBusy(directory: String, sessionID: String) {
        synchronized(idleSessions) {
            idleSessions.remove(IdleKey(directory, sessionID))
        }
    }
}
