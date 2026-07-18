package de.moritzf.opencodewebpanel.features

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Project-scoped hand-off point between IDE actions and the embedded OpenCode chat. The tool
 * window content registers a dispatcher while its page is available; texts sent when the panel is
 * not ready are queued and flushed by the content once the OpenCode project page has loaded.
 */
@Service(Service.Level.PROJECT)
class OpenCodeChatInputService {
    internal data class Batch(val id: String, val text: String)
    internal data class Delivery(val attemptID: String, val batch: Batch)

    private val lock = Any()
    private var dispatcher: ((Delivery) -> Boolean)? = null
    private val pending = ArrayDeque<Batch>()
    private var inFlight: Delivery? = null
    private var nextBatchID = 0L
    private var nextAttemptID = 0L

    internal fun setDispatcher(dispatcher: ((Delivery) -> Boolean)?) {
        synchronized(lock) {
            if (dispatcher == null) requeueInFlightLocked()
            this.dispatcher = dispatcher
        }
    }

    /** Queues each text as an independently acknowledged delivery, preserving caller order. */
    fun send(texts: List<String>): Boolean {
        synchronized(lock) {
            texts.filter { it.isNotBlank() }.forEach { text ->
                pending.addLast(Batch("chat-${++nextBatchID}", text))
            }
        }
        return dispatchPending()
    }

    /** Submits at most one batch; the next stays queued until this one is acknowledged. */
    internal fun dispatchPending(): Boolean {
        val claim = synchronized(lock) {
            if (inFlight != null) return true
            val currentDispatcher = dispatcher ?: return false
            val batch = pending.removeFirstOrNull() ?: return true
            val delivery = Delivery("chat-attempt-${++nextAttemptID}", batch)
            inFlight = delivery
            currentDispatcher to delivery
        }
        val submitted = runCatching { claim.first(claim.second) }.getOrDefault(false)
        if (!submitted) {
            synchronized(lock) {
                if (inFlight?.attemptID == claim.second.attemptID) requeueInFlightLocked()
            }
        }
        return submitted
    }

    /** Completes an accepted batch, or requeues a rejected one for a later page-ready retry. */
    internal fun acknowledge(attemptID: String, accepted: Boolean): Boolean {
        val matched = synchronized(lock) {
            val delivery = inFlight?.takeIf { it.attemptID == attemptID } ?: return false
            inFlight = null
            if (!accepted) pending.addFirst(delivery.batch)
            true
        }
        if (accepted) dispatchPending()
        return matched
    }

    internal fun retryInFlight(attemptID: String): Boolean = synchronized(lock) {
        if (inFlight?.attemptID != attemptID) return false
        requeueInFlightLocked()
        true
    }

    internal fun requeueInFlight() {
        synchronized(lock) { requeueInFlightLocked() }
    }

    internal fun queuedCount(): Int = synchronized(lock) { pending.size + if (inFlight != null) 1 else 0 }

    private fun requeueInFlightLocked() {
        inFlight?.batch?.let(pending::addFirst)
        inFlight = null
    }

    companion object {
        fun getInstance(project: Project): OpenCodeChatInputService {
            return project.getService(OpenCodeChatInputService::class.java)
        }
    }
}
