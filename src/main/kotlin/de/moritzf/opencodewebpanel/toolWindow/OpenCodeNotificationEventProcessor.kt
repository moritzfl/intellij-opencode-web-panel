package de.moritzf.opencodewebpanel.toolWindow

import com.google.gson.JsonObject

/**
 * Kotlin-side successor of the injected notification bridge's event reduction: turns one
 * OpenCode global event into either an IDE notification to show or a dismissal key for
 * notifications that became obsolete (request answered anywhere, session picked up work).
 *
 * Whether the notification is actually shown stays with [OpenCodeSystemNotifications],
 * which also applies the focus check — dismissals, by contrast, are produced regardless of
 * focus. [process] may look up the session via [fetchSession]; call it off the EDT.
 */
internal class OpenCodeNotificationEventProcessor(
    private val fetchSession: (directory: String, sessionID: String) -> OpenCodeServerProtocol.SessionInfo?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val IDLE_MERGE_WINDOW_MILLIS = 5_000L

        /** Event types that can affect notifications; everything else is filtered cheaply. */
        val RELEVANT_EVENT_TYPES: Set<String> = setOf(
            "session.status", "session.idle", "session.error",
            "permission.asked", "question.asked",
            "permission.replied", "question.replied", "question.rejected",
        )
    }

    sealed interface Outcome {
        /** Dismiss the notifications registered under [key] (`request:<id>` / `session:<id>`). */
        data class Dismiss(val key: String) : Outcome

        data class Notify(val payload: OpenCodeServerProtocol.SystemNotificationPayload) : Outcome
    }

    // Merges the paired session.idle/session.status events of one state change without
    // suppressing a genuine re-idle shortly after; keyed by directory + session.
    private val recentIdleAtMillis = mutableMapOf<String, Long>()

    fun process(event: OpenCodeGlobalEvent): Outcome? {
        val directory = event.directory
        val properties = event.properties
        var type = event.type

        // An answered request makes its IDE notification obsolete everywhere.
        if (type == "permission.replied" || type == "question.replied" || type == "question.rejected") {
            val requestID = properties.stringMember("requestID") ?: return null
            if (!OpenCodeServerProtocol.isOpenCodeRecordId(requestID)) return null
            return Outcome.Dismiss("request:$requestID")
        }
        // session.status is the successor of the deprecated session.idle event; current
        // servers emit both for the same state change.
        if (type == "session.status") {
            val statusType = properties.get("status")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.stringMember("type")
            if (statusType == "busy" || statusType == "retry") {
                // The session picked up work again (a prompt from any OpenCode client), so
                // stale "response ready" / "session error" notifications no longer apply.
                val sessionID = properties.stringMember("sessionID") ?: return null
                if (!OpenCodeServerProtocol.isOpenCodeRecordId(sessionID)) return null
                return Outcome.Dismiss("session:$sessionID")
            }
            if (statusType != "idle") return null
            type = "session.idle"
        }
        if (type != "session.idle" && type != "session.error" &&
            type != "permission.asked" && type != "question.asked"
        ) {
            return null
        }

        val sessionID = properties.stringMember("sessionID").orEmpty()
        if (type == "session.idle" && !markIdle(directory, sessionID)) return null

        val session = if (sessionID.isNotBlank()) fetchSession(directory, sessionID) else null
        if (type == "session.idle" && (session == null || session.parentID != null)) return null
        if (type == "session.error" && session?.parentID != null) return null

        val sessionTitle = session?.title?.takeIf { it.isNotBlank() }
        val title: String
        val body: String
        when (type) {
            "session.idle" -> {
                title = "Response ready"
                body = sessionTitle ?: sessionID
            }
            "session.error" -> {
                title = "Session error"
                body = sessionTitle
                    ?: properties.stringMember("error")
                    ?: "An error occurred"
            }
            "permission.asked" -> {
                title = "Permission required"
                body = (sessionTitle ?: "New session") +
                    " in " + OpenCodeServerProtocol.projectDisplayName(directory) + " needs permission"
            }
            else -> {
                title = "Question"
                body = (sessionTitle ?: "New session") +
                    " in " + OpenCodeServerProtocol.projectDisplayName(directory) + " has a question"
            }
        }
        if (body.isBlank()) return null

        val kind = when (type) {
            "permission.asked" -> "permission"
            "question.asked" -> "question"
            else -> "session"
        }
        val requestID = if (kind == "permission" || kind == "question") {
            properties.stringMember("id").orEmpty()
        } else {
            ""
        }
        val id = event.recordId.ifBlank { listOf(type, directory, sessionID, body).joinToString("|") }
        return Outcome.Notify(
            OpenCodeServerProtocol.SystemNotificationPayload(
                id = id,
                directory = directory,
                route = OpenCodeServerProtocol.buildSessionRoute(directory, sessionID.takeIf { it.isNotBlank() }),
                title = title,
                body = body,
                kind = kind,
                sessionID = sessionID,
                requestID = requestID,
            ),
        )
    }

    private fun markIdle(directory: String, sessionID: String): Boolean {
        val idleKey = directory + "\n" + sessionID
        val now = nowMillis()
        synchronized(recentIdleAtMillis) {
            recentIdleAtMillis.entries.removeIf { now - it.value >= IDLE_MERGE_WINDOW_MILLIS }
            if (recentIdleAtMillis.containsKey(idleKey)) return false
            recentIdleAtMillis[idleKey] = now
        }
        return true
    }

    private fun JsonObject.stringMember(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
