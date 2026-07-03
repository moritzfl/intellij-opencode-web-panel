package de.moritzf.opencodewebpanel.toolWindow

import com.google.gson.JsonObject
import com.intellij.util.messages.Topic

/**
 * One parsed event from the OpenCode `/global/event` SSE stream. The wire shape is
 * `{"directory": "...", "payload": {"id": "...", "type": "...", "properties": {...}}}`;
 * events without a directory or payload type are dropped before publication.
 */
data class OpenCodeGlobalEvent(
    val directory: String,
    val type: String,
    val recordId: String,
    val properties: JsonObject,
)

/**
 * Application-level stream of OpenCode server events, read from `/global/event` on the JVM
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
    fun connected() {}

    fun eventReceived(event: OpenCodeGlobalEvent)

    companion object {
        val TOPIC: Topic<OpenCodeGlobalEventListener> = Topic.create(
            "OpenCode Web Panel global events",
            OpenCodeGlobalEventListener::class.java,
        )
    }
}
