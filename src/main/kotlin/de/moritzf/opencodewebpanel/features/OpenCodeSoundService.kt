package de.moritzf.opencodewebpanel.features

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEvent
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEventListener
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry
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
 *
 * Busy state is scoped per backend: Host CLI and Docker Sandbox can serve the same directory at
 * different times, and a restart of one backend must not clear or satisfy the other's sessions.
 */
internal object OpenCodeSoundService {
    private val lock = Any()
    private var busConnection: com.intellij.openapi.Disposable? = null
    private val eventExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor(
        "OpenCode Sound Events",
        1,
    )
    private data class SessionKey(val backendId: String, val sessionID: String)
    private val busySessions = mutableSetOf<SessionKey>()
    private val connectedGenerations = mutableMapOf<String, Long>()

    /**
     * Subscribes to the application event bus. Safe to call repeatedly: re-subscribes after the
     * parent [OpenCodeServerBackendRegistry] is disposed (dynamic plugin reload / IDE restart).
     */
    fun ensureInstalled() {
        synchronized(lock) {
            if (busConnection != null) return
            val parent = OpenCodeServerBackendRegistry.getInstance()
            val connection = ApplicationManager.getApplication().messageBus.connect(parent)
            connection.subscribe(
                OpenCodeGlobalEventListener.TOPIC,
                object : OpenCodeGlobalEventListener {
                    override fun connected(backendId: String) {
                        val backend = parent.backend(backendId) ?: return
                        val generation = backend.getServerGeneration()
                        eventExecutor.execute { handleConnected(backendId, generation) }
                    }

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
        synchronized(busySessions) {
            busySessions.clear()
            connectedGenerations.clear()
        }
    }

    /** Preserve busy state across a transient SSE reconnect; only a new server invalidates it. */
    internal fun handleConnected(backendId: String, serverGeneration: Long) {
        synchronized(busySessions) {
            if (connectedGenerations[backendId] == serverGeneration) return
            busySessions.removeAll { it.backendId == backendId }
            connectedGenerations[backendId] = serverGeneration
        }
    }

    internal fun handleEvent(
        event: OpenCodeGlobalEvent,
        settings: OpenCodeSoundSettings = currentSettings(),
        fetchSession: (backendId: String, directory: String, sessionID: String) -> OpenCodeServerProtocol.SessionInfo? =
            ::fetchSessionInfo,
        play: (String?) -> Unit = OpenCodeSoundPlayer::playById,
    ) {
        val backendId = event.backendId
        var type = event.type
        if (type == "session.status") {
            val statusType = event.properties.objectMember("status")?.stringMember("type")
            if (statusType == "busy" || statusType == "retry") {
                event.properties.stringMember("sessionID")
                    ?.takeIf(OpenCodeServerProtocol::isSessionId)
                    ?.let { markBusy(backendId, it) }
                return
            }
            if (statusType != "idle") return
            type = "session.idle"
        }
        when (type) {
            "session.idle" -> {
                val sessionID = event.properties.stringMember("sessionID") ?: return
                if (!OpenCodeServerProtocol.isSessionId(sessionID)) return
                if (!isBusy(backendId, sessionID)) return
                if (!settings.agentEnabled) {
                    markIdle(backendId, sessionID)
                    return
                }
                // Keep busy on a failed lookup so a later idle can retry. A new server generation
                // clears reduced state; a transient SSE reconnect preserves the live transition.
                val session = fetchSession(backendId, event.directory, sessionID) ?: return
                if (!markIdle(backendId, sessionID)) return
                if (session.parentID != null) return
                play(settings.agent)
            }
            "session.error" -> {
                if (!settings.errorsEnabled) return
                val sessionID = event.properties.stringMember("sessionID")
                if (!sessionID.isNullOrBlank()) {
                    if (!OpenCodeServerProtocol.isSessionId(sessionID)) return
                    val session = fetchSession(backendId, event.directory, sessionID)
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
        // Sound preferences are app-level: the native snapshot has always carried them, and
        // per-backend page state (theme, layout) is not sound-relevant.
        return parseOpenCodeSoundSettings(OpenCodeSettingsState.getInstance().openCodeLocalStorageSnapshot)
    }

    private fun fetchSessionInfo(backendId: String, directory: String, sessionID: String): OpenCodeServerProtocol.SessionInfo? {
        val serverManager = OpenCodeServerBackendRegistry.getInstance().backend(backendId) ?: return null
        val serverUrl = serverManager.getServerUrl() ?: return null
        val password = serverManager.getServerPassword() ?: return null
        return OpenCodeServerProtocol.fetchSessionInfo(
            serverUrl,
            OpenCodeServerProtocol.buildBasicAuthHeader(password),
            directory,
            sessionID,
        )
    }

    private fun isBusy(backendId: String, sessionID: String): Boolean {
        synchronized(busySessions) {
            return SessionKey(backendId, sessionID) in busySessions
        }
    }

    private fun markIdle(backendId: String, sessionID: String): Boolean {
        synchronized(busySessions) {
            return busySessions.remove(SessionKey(backendId, sessionID))
        }
    }

    private fun markBusy(backendId: String, sessionID: String) {
        synchronized(busySessions) {
            busySessions.add(SessionKey(backendId, sessionID))
        }
    }
}