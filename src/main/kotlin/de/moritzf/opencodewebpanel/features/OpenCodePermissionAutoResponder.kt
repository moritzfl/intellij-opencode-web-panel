package de.moritzf.opencodewebpanel.features

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEvent
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEventListener
import de.moritzf.opencodewebpanel.server.OpenCodeProtocolResult
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.stringMember
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transient bridge for OpenCode's broken web auto-accept control.
 *
 * OpenCode's SPA still keys auto-accept as `base64(directory)/session` (and the classic settings
 * toggle reads `params.dir`), but 1.18's default routes are directoryless
 * (`/server/<key>/session/<id>`). That leaves the built-in toggle disabled or scoped to a directory
 * the new UI no longer exposes. This class answers `permission.asked` from the JVM event stream
 * instead, scoped to the panel's project directory.
 *
 * Opt-in covers one displayed session and, via lineage, its subagent children.
 *
 * Nothing is persisted. State dies with the tool-window content. Explicit deny rules never emit an
 * ask event, so they remain effective.
 */
internal class OpenCodePermissionAutoResponder(
    private val projectDirectory: () -> String?,
    private val serverUrl: () -> String?,
    private val serverPassword: () -> String?,
    private val executeAsync: (Runnable) -> Unit = { task ->
        ApplicationManager.getApplication().executeOnPooledThread(task)
    },
    private val scheduleAsync: (delayMillis: Long, task: Runnable) -> Unit = { delay, task ->
        AppExecutorUtil.getAppScheduledExecutorService().schedule(task, delay, TimeUnit.MILLISECONDS)
    },
    private val loadPending: (
        serverUrl: String,
        authHeader: String,
        directory: String,
    ) -> OpenCodeProtocolResult<List<OpenCodeServerProtocol.PendingRequestSummary>> = { url, auth, directory ->
        OpenCodeServerProtocol.fetchPendingRequestsResult(
            url,
            auth,
            OpenCodeServerProtocol.PERMISSION_LIST_PATH,
            directory,
        )
    },
    private val loadSession: (
        serverUrl: String,
        authHeader: String,
        directory: String,
        sessionID: String,
    ) -> OpenCodeServerProtocol.SessionInfo? = { url, auth, directory, sessionID ->
        OpenCodeServerProtocol.fetchSessionInfo(url, auth, directory, sessionID)
    },
    private val reply: (
        serverUrl: String,
        authHeader: String,
        directory: String,
        sessionID: String,
        requestID: String,
        response: OpenCodeServerProtocol.PermissionResponse,
    ) -> Boolean = { url, auth, directory, sessionID, requestID, response ->
        OpenCodeServerProtocol.replyToPermission(
            url,
            auth,
            directory,
            sessionID,
            requestID,
            response,
        )
    },
) : OpenCodeGlobalEventListener {

    private companion object {
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MILLIS = 500L
    }

    private val log = Logger.getInstance(OpenCodePermissionAutoResponder::class.java)
    private val disposed = AtomicBoolean()
    /** Per-session overrides; `false` blocks an inherited `true` from a parent session. */
    private val sessionAutoAccept = ConcurrentHashMap<String, Boolean>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    /** sessionID → parentID for sessions whose lineage has been resolved. */
    private val parentBySession = ConcurrentHashMap<String, String>()
    /** Sessions fetched at least once (roots have no entry in [parentBySession]). */
    private val resolvedSessions = ConcurrentHashMap.newKeySet<String>()
    /** Sessions whose full chain to a root has been resolved. */
    private val preparedLineages = ConcurrentHashMap.newKeySet<String>()
    private val lineageInFlight = ConcurrentHashMap.newKeySet<String>()
    private val prepareFailures = ConcurrentHashMap<String, Int>()

    fun isSessionEnabled(sessionID: String): Boolean = sessionAutoAccept[sessionID] == true

    /** True when [sessionID] itself or one of its ancestors is enabled. */
    fun isEffectivelyEnabled(sessionID: String): Boolean {
        if (!OpenCodeServerProtocol.isSessionId(sessionID)) return false
        return effectiveOverride(sessionID) == true
    }

    fun isLineagePrepared(sessionID: String): Boolean = preparedLineages.contains(sessionID)

    /** Resolves parent links without blocking action updates, so a child route reflects parent state. */
    fun prepareSession(sessionID: String) {
        if (disposed.get() || !OpenCodeServerProtocol.isSessionId(sessionID) || preparedLineages.contains(sessionID) ||
            (prepareFailures[sessionID] ?: 0) > MAX_RETRIES ||
            !lineageInFlight.add(sessionID)
        ) {
            return
        }
        val directory = projectDirectory()?.takeIf(String::isNotBlank)
        if (directory == null) {
            lineageInFlight.remove(sessionID)
            return
        }
        executeAsync(Runnable {
            try {
                if (isCurrentDirectory(directory) && resolveLineage(directory, sessionID)) {
                    prepareFailures.remove(sessionID)
                    preparedLineages.add(sessionID)
                } else if (!disposed.get()) {
                    val failures = prepareFailures.merge(sessionID, 1, Int::plus) ?: 1
                    if (failures <= MAX_RETRIES) {
                        scheduleAsync(RETRY_DELAY_MILLIS * failures, Runnable { prepareSession(sessionID) })
                    }
                }
            } finally {
                lineageInFlight.remove(sessionID)
            }
        })
    }

    /** A child `false` overrides an enabled ancestor without changing that ancestor or its siblings. */
    fun setEffectivelyEnabled(sessionID: String, value: Boolean) {
        setSessionEnabled(sessionID, value)
    }

    fun setSessionEnabled(sessionID: String, value: Boolean) {
        if (disposed.get() || !OpenCodeServerProtocol.isSessionId(sessionID)) return
        if (sessionAutoAccept.put(sessionID, value) == value) return
        if (!value) {
            inFlight.removeIf { key -> key.startsWith("$sessionID:") }
            return
        }
        seedPendingRequests(onlySessionID = sessionID)
    }

    override fun connected() {
        if (!disposed.get() && hasEnabledSession()) seedPendingRequests()
    }

    override fun eventReceived(event: OpenCodeGlobalEvent) {
        if (disposed.get() || event.type != "permission.asked") return
        val directory = projectDirectory() ?: return
        if (!OpenCodeServerProtocol.isSameFilesystemPath(event.directory, directory)) return
        val request = OpenCodeServerProtocol.PendingRequestSummary(
            id = event.properties.stringMember("id")?.takeIf(OpenCodeServerProtocol::isPermissionId) ?: return,
            sessionID = event.properties.stringMember("sessionID")?.takeIf(OpenCodeServerProtocol::isSessionId) ?: return,
        )
        considerRequest(event.directory, request, attempt = 0)
    }

    private fun considerRequest(
        directory: String,
        request: OpenCodeServerProtocol.PendingRequestSummary,
        attempt: Int,
    ) {
        if (effectiveOverride(request.sessionID) == true) {
            enqueueReply(directory, request, attempt = 0)
            return
        }
        if (!hasEnabledSession()) return
        // Parent may not be cached yet (subagent). Resolve lineage, then retry.
        executeAsync(Runnable {
            if (!isCurrentDirectory(directory)) return@Runnable
            if (resolveLineage(directory, request.sessionID)) {
                preparedLineages.add(request.sessionID)
            } else if (attempt < MAX_RETRIES && isCurrentDirectory(directory)) {
                scheduleRetry(attempt) { considerRequest(directory, request, attempt + 1) }
                return@Runnable
            }
            if (!isCurrentDirectory(directory)) return@Runnable
            if (effectiveOverride(request.sessionID) == true) {
                enqueueReply(directory, request, attempt = 0)
            }
        })
    }

    private fun seedPendingRequests(onlySessionID: String? = null, attempt: Int = 0) {
        val directory = projectDirectory()?.takeIf(String::isNotBlank) ?: return
        executeAsync(Runnable {
            if (!hasEnabledTarget(onlySessionID) || !isCurrentDirectory(directory)) return@Runnable
            val url = serverUrl() ?: return@Runnable
            val password = serverPassword() ?: return@Runnable
            val authHeader = OpenCodeServerProtocol.buildBasicAuthHeader(password)
            val requests = (loadPending(url, authHeader, directory) as? OpenCodeProtocolResult.Success)?.value
            if (requests == null) {
                if (attempt < MAX_RETRIES && hasEnabledTarget(onlySessionID)) {
                    scheduleRetry(attempt) { seedPendingRequests(onlySessionID, attempt + 1) }
                }
                return@Runnable
            }
            if (!isCurrentDirectory(directory)) return@Runnable
            // Resolve unknown lineages so a parent enable covers already-pending children.
            var unresolvedLineage = false
            if (hasEnabledSession()) {
                requests.map { it.sessionID }.distinct().forEach { sessionID ->
                    if (effectiveOverride(sessionID) != true && resolveLineage(directory, sessionID)) {
                        preparedLineages.add(sessionID)
                    } else if (effectiveOverride(sessionID) == null && !preparedLineages.contains(sessionID)) {
                        unresolvedLineage = true
                    }
                }
            }
            if (!isCurrentDirectory(directory)) return@Runnable
            requests.asSequence()
                .filter { onlySessionID == null || effectiveOverride(it.sessionID) == true }
                .filter { effectiveOverride(it.sessionID) == true }
                .forEach { enqueueReply(directory, it, attempt = 0) }
            if (unresolvedLineage && attempt < MAX_RETRIES && hasEnabledTarget(onlySessionID)) {
                scheduleRetry(attempt) { seedPendingRequests(onlySessionID, attempt + 1) }
            }
        })
    }

    private fun enqueueReply(
        directory: String,
        request: OpenCodeServerProtocol.PendingRequestSummary,
        attempt: Int,
    ) {
        val inFlightKey = "${request.sessionID}:${request.id}"
        if (!OpenCodeServerProtocol.isPermissionId(request.id) ||
            !OpenCodeServerProtocol.isSessionId(request.sessionID) ||
            !isCurrentForRequest(directory, request.sessionID) ||
            !inFlight.add(inFlightKey)
        ) {
            return
        }
        executeAsync(Runnable {
            var shouldRetry = false
            try {
                if (!isCurrentForRequest(directory, request.sessionID)) return@Runnable
                val url = serverUrl() ?: return@Runnable
                val password = serverPassword() ?: return@Runnable
                if (!isCurrentForRequest(directory, request.sessionID)) return@Runnable
                val accepted = reply(
                    url,
                    OpenCodeServerProtocol.buildBasicAuthHeader(password),
                    directory,
                    request.sessionID,
                    request.id,
                    OpenCodeServerProtocol.PermissionResponse.ONCE,
                )
                if (!accepted) {
                    shouldRetry = attempt < MAX_RETRIES
                    if (!shouldRetry) {
                        log.info("Auto-accept permission reply failed for ${request.id} in ${request.sessionID}")
                    }
                }
            } finally {
                inFlight.remove(inFlightKey)
            }
            if (shouldRetry && isCurrentForRequest(directory, request.sessionID)) {
                scheduleRetry(attempt) { enqueueReply(directory, request, attempt + 1) }
            }
        })
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        sessionAutoAccept.clear()
        inFlight.clear()
        parentBySession.clear()
        resolvedSessions.clear()
        preparedLineages.clear()
        lineageInFlight.clear()
        prepareFailures.clear()
    }

    private fun scheduleRetry(attempt: Int, action: () -> Unit) {
        scheduleAsync(RETRY_DELAY_MILLIS * (attempt + 1), Runnable {
            if (!disposed.get()) action()
        })
    }

    /** Returns true only when the complete chain reaches a root or a previously resolved root. */
    private fun resolveLineage(directory: String, sessionID: String): Boolean {
        var current: String? = sessionID
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            if (resolvedSessions.contains(current)) {
                current = parentBySession[current]
                continue
            }
            val url = serverUrl() ?: return false
            val password = serverPassword() ?: return false
            val info = loadSession(
                url,
                OpenCodeServerProtocol.buildBasicAuthHeader(password),
                directory,
                current,
            )
            if (info == null) {
                // Don't mark resolved on failure — a later attempt may succeed.
                return false
            }
            rememberSession(info.id, info.parentID)
            current = info.parentID
        }
        return true
    }

    private fun rememberSession(sessionID: String, parentID: String?) {
        if (parentID != null && OpenCodeServerProtocol.isSessionId(parentID)) {
            parentBySession[sessionID] = parentID
        } else {
            parentBySession.remove(sessionID)
        }
        // Publish resolved last: readers that observe it must also observe the parent link.
        resolvedSessions.add(sessionID)
    }

    private fun effectiveOverride(sessionID: String): Boolean? {
        var current: String? = sessionID
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            sessionAutoAccept[current]?.let { return it }
            current = parentBySession[current]
        }
        return null
    }

    private fun hasEnabledTarget(onlySessionID: String?): Boolean {
        return if (onlySessionID == null) hasEnabledSession() else sessionAutoAccept[onlySessionID] == true
    }

    private fun hasEnabledSession(): Boolean = sessionAutoAccept.containsValue(true)

    private fun isCurrentDirectory(directory: String): Boolean {
        return !disposed.get() && OpenCodeServerProtocol.isSameFilesystemPath(projectDirectory(), directory)
    }

    private fun isCurrentForRequest(directory: String, sessionID: String): Boolean {
        if (!isCurrentDirectory(directory)) return false
        return effectiveOverride(sessionID) == true
    }
}
