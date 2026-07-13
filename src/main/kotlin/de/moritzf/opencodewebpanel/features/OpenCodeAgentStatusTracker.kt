package de.moritzf.opencodewebpanel.features

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEvent
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEventListener
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager
import de.moritzf.opencodewebpanel.server.objectMember
import de.moritzf.opencodewebpanel.server.stringMember

/**
 * Reduces OpenCode global events into the agent status shown on the tool-window badge:
 * [ATTENTION] while a permission or question awaits an answer, [BUSY] while any session
 * works, [IDLE] otherwise. Pure state holder; callers synchronize access.
 */
internal class OpenCodeAgentStatusState {
    companion object {
        const val IDLE = "idle"
        const val BUSY = "busy"
        const val ATTENTION = "attention"
    }

    private val busySessions = mutableSetOf<String>()
    private val attentionRequests = mutableSetOf<String>()

    fun current(): String = when {
        attentionRequests.isNotEmpty() -> ATTENTION
        busySessions.isNotEmpty() -> BUSY
        else -> IDLE
    }

    /** Replaces the tracked state with a REST snapshot; null collections keep the current state. */
    fun seed(busySessionIds: Collection<String>?, pendingRequestIds: Collection<String>?) {
        if (busySessionIds != null) {
            busySessions.clear()
            busySessions.addAll(busySessionIds)
        }
        if (pendingRequestIds != null) {
            attentionRequests.clear()
            attentionRequests.addAll(pendingRequestIds)
        }
    }

    /** Applies one event; returns false when the event cannot affect the status. */
    fun applyEvent(type: String, properties: JsonObject): Boolean {
        when (type) {
            "session.status" -> {
                val sessionID = properties.stringMember("sessionID").orEmpty()
                val statusType = properties.objectMember("status")?.stringMember("type")
                if (sessionID.isBlank() || statusType == null) return false
                if (statusType == "busy" || statusType == "retry") {
                    busySessions.add(sessionID)
                } else {
                    busySessions.remove(sessionID)
                }
            }
            // Deprecated predecessor of session.status; current servers emit both.
            "session.idle" -> busySessions.remove(properties.stringMember("sessionID").orEmpty())
            "permission.asked", "question.asked" -> {
                val requestID = properties.stringMember("id")?.takeIf { it.isNotBlank() } ?: return false
                attentionRequests.add(requestID)
            }
            "permission.replied", "question.replied", "question.rejected" -> {
                val requestID = properties.stringMember("requestID")?.takeIf { it.isNotBlank() } ?: return false
                attentionRequests.remove(requestID)
            }
            else -> return false
        }
        return true
    }

    fun clear() {
        busySessions.clear()
        attentionRequests.clear()
    }
}

/**
 * Kotlin-side successor of the injected agent-status bridge script: subscribes to the JVM
 * `/global/event` stream, reduces the events of one project directory into an agent status,
 * and re-seeds from the REST API after each stream (re)connect. Runs independently of the
 * embedded page, so the badge stays correct while the page is loading or crashed.
 *
 * [onStateChanged] fires only on actual transitions and may run on the stream reader thread
 * or a pooled thread; callers dispatch to the EDT themselves.
 */
internal class OpenCodeAgentStatusTracker(
    private val serverManager: SharedOpenCodeServerManager,
    private val projectDirectory: () -> String?,
    private val enabled: () -> Boolean,
    private val onStateChanged: (String) -> Unit,
) : OpenCodeGlobalEventListener {

    private val lock = Any()
    private val state = OpenCodeAgentStatusState()
    private var lastReportedState = OpenCodeAgentStatusState.IDLE

    // Bumped on every state-affecting event so an in-flight REST seed can detect that its
    // snapshot went stale (an event raced past it) and fetch a fresh one instead of
    // overwriting newer event-derived state with older REST data.
    private var stateRevision = 0L

    private companion object {
        private const val MAX_SEED_ATTEMPTS = 3
    }

    override fun connected() {
        seed()
    }

    override fun eventReceived(event: OpenCodeGlobalEvent) {
        if (!enabled()) return
        val directory = projectDirectory() ?: return
        if (!OpenCodeServerProtocol.isSameFilesystemPath(event.directory, directory)) return
        val transition = synchronized(lock) {
            if (!state.applyEvent(event.type, event.properties)) return
            stateRevision++
            reportableTransition()
        } ?: return
        onStateChanged(transition)
    }

    /**
     * Re-seeds the tracked state from the REST API on a pooled thread. Called on stream
     * (re)connect and when a panel starts caring about the status (page load, badge toggle),
     * because events that occurred before then never reached this tracker.
     */
    fun seed() {
        seed(attempt = 1)
    }

    private fun seed(attempt: Int) {
        if (!enabled()) return
        val directory = projectDirectory() ?: return
        val serverUrl = serverManager.getServerUrl() ?: return
        val password = serverManager.getServerPassword() ?: return
        val authHeader = OpenCodeServerProtocol.buildBasicAuthHeader(password)
        val revisionAtRequest = synchronized(lock) { stateRevision }
        ApplicationManager.getApplication().executeOnPooledThread {
            val busySessionIds = OpenCodeServerProtocol.fetchBusySessionIds(serverUrl, authHeader, directory)
            val permissions = OpenCodeServerProtocol.fetchPendingRequestIds(
                serverUrl, authHeader, OpenCodeServerProtocol.PERMISSION_LIST_PATH, directory,
            )
            val questions = OpenCodeServerProtocol.fetchPendingRequestIds(
                serverUrl, authHeader, OpenCodeServerProtocol.QUESTION_LIST_PATH, directory,
            )
            val pendingRequestIds = if (permissions == null && questions == null) {
                null
            } else {
                permissions.orEmpty() + questions.orEmpty()
            }
            var retry = false
            val transition = synchronized(lock) {
                if (stateRevision != revisionAtRequest && attempt < MAX_SEED_ATTEMPTS) {
                    // Events raced past this snapshot while it was in flight; a fresh fetch
                    // reflects them server-side. On the last attempt the (at most ms-stale)
                    // snapshot is still better than keeping possibly long-stale state.
                    retry = true
                    null
                } else {
                    state.seed(busySessionIds, pendingRequestIds)
                    reportableTransition()
                }
            }
            if (retry) {
                seed(attempt + 1)
                return@executeOnPooledThread
            }
            transition?.let(onStateChanged)
        }
    }

    /** Clears the tracked state without reporting, e.g. on server stop or badge disable. */
    fun reset() {
        synchronized(lock) {
            state.clear()
            lastReportedState = OpenCodeAgentStatusState.IDLE
        }
    }

    private fun reportableTransition(): String? {
        val current = state.current()
        if (current == lastReportedState) return null
        lastReportedState = current
        return current
    }
}
