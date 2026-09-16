package de.moritzf.opencodewebpanel.server

import com.google.gson.JsonObject
import com.intellij.util.messages.Topic

/**
 * One parsed event from the OpenCode SSE stream. 1.18 `/global/event` is
 * `{"directory": "...", "payload": {"id": "...", "type": "...", "properties": {...}}}`.
 * CLI 2.x `/api/event` is `{id,type,data,location?}` and is normalized here
 * (directory from `location` or the backend cwd; execution.* → `session.status`).
 */
data class OpenCodeGlobalEvent(
    val directory: String,
    val type: String,
    val recordId: String,
    val properties: JsonObject,
    val backendId: String = OpenCodeServerBackend.NATIVE_ID,
)

/**
 * Application-level stream of OpenCode server events, read from `/global/event` or `/api/event` on the JVM
 * by [OpenCodeGlobalEventStream] and published on the application message bus. Both callbacks
 * run on the stream's reader thread; implementations must dispatch to the EDT themselves and
 * return quickly, or they stall event delivery to every other subscriber.
 */
interface OpenCodeGlobalEventListener {
    /**
     * Fired after each successful (re)connect to the event stream. Events that occurred
     * while disconnected are lost, so consumers holding reduced state must re-seed it from
     * the REST API when this fires.
     */
    fun connected(backendId: String) {}

    fun eventReceived(event: OpenCodeGlobalEvent)

    companion object {
        val TOPIC: Topic<OpenCodeGlobalEventListener> = Topic.create(
            "OpenCode Web Panel global events",
            OpenCodeGlobalEventListener::class.java,
        )
    }
}
