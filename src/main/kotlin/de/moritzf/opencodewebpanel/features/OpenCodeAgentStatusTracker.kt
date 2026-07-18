package de.moritzf.opencodewebpanel.features

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEvent
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEventListener
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
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
                when (statusType) {
                    "busy", "retry" -> busySessions.add(sessionID)
                    "idle" -> busySessions.remove(sessionID)
                    else -> return false
                }
            }
            // Deprecated predecessor of session.status; current servers emit both.
            "session.idle" -> {
                val sessionID = properties.stringMember("sessionID")?.takeIf { it.isNotBlank() } ?: return false
                busySessions.remove(sessionID)
            }
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
    private val projectDirectory: () -> String?,
    private val enabled: () -> Boolean,
    private val onStateChanged: (state: String, presentationRevision: Long) -> Unit,
    private val serverUrl: () -> String?,
    private val serverPassword: () -> String?,
    private val serverGeneration: () -> Long,
    private val loadSnapshot: (String, String, String) -> OpenCodeAgentStatusSnapshot = ::loadAgentStatusSnapshot,
    private val executeAsync: ((() -> Unit) -> Unit) = { task ->
        ApplicationManager.getApplication().executeOnPooledThread(task)
    },
) : OpenCodeGlobalEventListener {

    private val lock = Any()
    private val state = OpenCodeAgentStatusState()
    private var lastReportedState = OpenCodeAgentStatusState.IDLE

    // Bumped on every state-affecting event so an in-flight REST seed can detect that its
    // snapshot went stale (an event raced past it) and fetch a fresh one instead of
    // overwriting newer event-derived state with older REST data.
    private var stateRevision = 0L
    private var seedEpoch = 0L
    private var presentationRevision = 0L

    private data class Transition(val state: String, val revision: Long)

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
        onStateChanged(transition.state, transition.revision)
    }

    /**
     * Re-seeds the tracked state from the REST API on a pooled thread. Called on stream
     * (re)connect and when a panel starts caring about the status (page load, badge toggle),
     * because events that occurred before then never reached this tracker.
     */
    fun seed() {
        val epoch = synchronized(lock) { ++seedEpoch }
        seed(attempt = 1, epoch = epoch)
    }

    private fun seed(attempt: Int, epoch: Long) {
        if (!enabled()) return
        val directory = projectDirectory() ?: return
        val serverUrl = serverUrl() ?: return
        val password = serverPassword() ?: return
        val generation = serverGeneration()
        val authHeader = OpenCodeServerProtocol.buildBasicAuthHeader(password)
        val revisionAtRequest = synchronized(lock) { stateRevision }
        executeAsync {
            val snapshot = loadSnapshot(serverUrl, authHeader, directory)
            if (!seedIdentityIsCurrent(epoch, directory, serverUrl, generation)) return@executeAsync
            var retry = false
            val transition = synchronized(lock) {
                if (seedEpoch != epoch) {
                    null
                } else if (stateRevision != revisionAtRequest) {
                    // Never overwrite event-derived state with an older snapshot. Retry a bounded
                    // number of times; if events keep racing, retain the live state.
                    retry = attempt < MAX_SEED_ATTEMPTS
                    null
                } else {
                    state.seed(snapshot.busySessionIds, snapshot.pendingRequestIds)
                    stateRevision++
                    reportableTransition()
                }
            }
            if (retry) {
                seed(attempt + 1, epoch)
                return@executeAsync
            }
            if (seedIdentityIsCurrent(epoch, directory, serverUrl, generation)) {
                transition?.let { onStateChanged(it.state, it.revision) }
            }
        }
    }

    private fun seedIdentityIsCurrent(epoch: Long, directory: String, url: String, generation: Long): Boolean {
        if (!enabled() || serverGeneration() != generation || serverUrl() != url) return false
        if (!OpenCodeServerProtocol.isSameFilesystemPath(projectDirectory(), directory)) return false
        return synchronized(lock) { seedEpoch == epoch }
    }

    /** Clears the tracked state without reporting, e.g. on server stop or badge disable. */
    fun reset() {
        synchronized(lock) {
            seedEpoch++
            stateRevision++
            state.clear()
            lastReportedState = OpenCodeAgentStatusState.IDLE
            presentationRevision++
        }
    }

    internal fun isCurrentPresentation(state: String, revision: Long): Boolean = synchronized(lock) {
        presentationRevision == revision && lastReportedState == state && this.state.current() == state
    }

    private fun reportableTransition(): Transition? {
        val current = state.current()
        if (current == lastReportedState) return null
        lastReportedState = current
        return Transition(current, ++presentationRevision)
    }
}

internal data class OpenCodeAgentStatusSnapshot(
    val busySessionIds: Set<String>?,
    val pendingRequestIds: List<String>?,
)

private fun loadAgentStatusSnapshot(serverUrl: String, authHeader: String, directory: String): OpenCodeAgentStatusSnapshot {
    val permissions = OpenCodeServerProtocol.fetchPendingRequestIds(
        serverUrl, authHeader, OpenCodeServerProtocol.PERMISSION_LIST_PATH, directory,
    )
    val questions = OpenCodeServerProtocol.fetchPendingRequestIds(
        serverUrl, authHeader, OpenCodeServerProtocol.QUESTION_LIST_PATH, directory,
    )
    return OpenCodeAgentStatusSnapshot(
        busySessionIds = OpenCodeServerProtocol.fetchBusySessionIds(serverUrl, authHeader, directory),
        // The combined pending snapshot is authoritative only when both endpoint reads succeeded.
        pendingRequestIds = if (permissions != null && questions != null) permissions + questions else null,
    )
}
