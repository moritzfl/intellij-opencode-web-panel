package de.moritzf.opencodewebpanel.features

import com.intellij.openapi.application.ApplicationManager
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEvent
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEventListener
import de.moritzf.opencodewebpanel.server.OpenCodeProtocolResult
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.stringMember
import java.util.concurrent.ConcurrentHashMap

/**
 * Transient, session-scoped equivalent of `opencode --auto`: while enabled for a session, every
 * permission request from that session is answered with `once`. Denied permissions never emit
 * an ask event, so explicit deny rules remain effective.
 *
 * Nothing is persisted. The state dies with the tool-window content, making this a small temporary
 * bridge until OpenCode's web UI exposes its own usable auto-accept control.
 */
internal class OpenCodePermissionAutoResponder(
    private val projectDirectory: () -> String?,
    private val serverUrl: () -> String?,
    private val serverPassword: () -> String?,
    private val executeAsync: (Runnable) -> Unit = { task ->
        ApplicationManager.getApplication().executeOnPooledThread(task)
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

    private val enabledSessions = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun isEnabled(sessionID: String): Boolean = enabledSessions.contains(sessionID)

    fun setEnabled(sessionID: String, value: Boolean) {
        if (!OpenCodeServerProtocol.isSessionId(sessionID)) return
        val changed = if (value) enabledSessions.add(sessionID) else enabledSessions.remove(sessionID)
        if (!changed) return
        if (!value) {
            inFlight.removeIf { requestID -> requestID.startsWith("$sessionID:") }
            return
        }
        seedPendingRequests(sessionID)
    }

    override fun connected() {
        if (enabledSessions.isNotEmpty()) seedPendingRequests()
    }

    override fun eventReceived(event: OpenCodeGlobalEvent) {
        if (event.type != "permission.asked") return
        val directory = projectDirectory() ?: return
        if (!OpenCodeServerProtocol.isSameFilesystemPath(event.directory, directory)) return
        val request = OpenCodeServerProtocol.PendingRequestSummary(
            id = event.properties.stringMember("id")?.takeIf(OpenCodeServerProtocol::isPermissionId) ?: return,
            sessionID = event.properties.stringMember("sessionID")?.takeIf(OpenCodeServerProtocol::isSessionId) ?: return,
        )
        if (!enabledSessions.contains(request.sessionID)) return
        enqueueReply(event.directory, request)
    }

    private fun seedPendingRequests(onlySessionID: String? = null) {
        val directory = projectDirectory()?.takeIf(String::isNotBlank) ?: return
        executeAsync(Runnable {
            if (!hasEnabledSession(onlySessionID) || !isCurrentDirectory(directory)) return@Runnable
            val url = serverUrl() ?: return@Runnable
            val password = serverPassword() ?: return@Runnable
            val authHeader = OpenCodeServerProtocol.buildBasicAuthHeader(password)
            val requests = (loadPending(url, authHeader, directory) as? OpenCodeProtocolResult.Success)?.value
                ?: return@Runnable
            if (!isCurrentDirectory(directory)) return@Runnable
            requests.asSequence()
                .filter { onlySessionID == null || it.sessionID == onlySessionID }
                .filter { enabledSessions.contains(it.sessionID) }
                .forEach { enqueueReply(directory, it) }
        })
    }

    private fun enqueueReply(
        directory: String,
        request: OpenCodeServerProtocol.PendingRequestSummary,
    ) {
        val inFlightKey = "${request.sessionID}:${request.id}"
        if (!OpenCodeServerProtocol.isPermissionId(request.id) ||
            !OpenCodeServerProtocol.isSessionId(request.sessionID) ||
            !isCurrent(directory, request.sessionID) ||
            !inFlight.add(inFlightKey)
        ) {
            return
        }
        executeAsync(Runnable {
            try {
                if (!isCurrent(directory, request.sessionID)) return@Runnable
                val url = serverUrl() ?: return@Runnable
                val password = serverPassword() ?: return@Runnable
                if (!isCurrent(directory, request.sessionID)) return@Runnable
                reply(
                    url,
                    OpenCodeServerProtocol.buildBasicAuthHeader(password),
                    directory,
                    request.sessionID,
                    request.id,
                    OpenCodeServerProtocol.PermissionResponse.ONCE,
                )
            } finally {
                inFlight.remove(inFlightKey)
            }
        })
    }

    private fun hasEnabledSession(onlySessionID: String?): Boolean {
        return if (onlySessionID == null) enabledSessions.isNotEmpty() else enabledSessions.contains(onlySessionID)
    }

    private fun isCurrentDirectory(directory: String): Boolean {
        return OpenCodeServerProtocol.isSameFilesystemPath(projectDirectory(), directory)
    }

    private fun isCurrent(directory: String, sessionID: String): Boolean {
        return enabledSessions.contains(sessionID) && isCurrentDirectory(directory)
    }
}
