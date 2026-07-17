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

    private val lock = Any()
    private var dispatcher: ((Batch) -> Boolean)? = null
    private val pending = ArrayDeque<Batch>()
    private var inFlight: Batch? = null
    private var nextBatchID = 0L

    internal fun setDispatcher(dispatcher: ((Batch) -> Boolean)?) {
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
            inFlight = batch
            currentDispatcher to batch
        }
        val submitted = runCatching { claim.first(claim.second) }.getOrDefault(false)
        if (!submitted) {
            synchronized(lock) {
                if (inFlight?.id == claim.second.id) requeueInFlightLocked()
            }
        }
        return submitted
    }

    /** Completes an accepted batch, or requeues a rejected one for a later page-ready retry. */
    internal fun acknowledge(batchID: String, accepted: Boolean): Boolean {
        val matched = synchronized(lock) {
            val batch = inFlight?.takeIf { it.id == batchID } ?: return false
            inFlight = null
            if (!accepted) pending.addFirst(batch)
            true
        }
        if (accepted) dispatchPending()
        return matched
    }

    internal fun retryInFlight(batchID: String): Boolean = synchronized(lock) {
        if (inFlight?.id != batchID) return false
        requeueInFlightLocked()
        true
    }

    internal fun requeueInFlight() {
        synchronized(lock) { requeueInFlightLocked() }
    }

    internal fun queuedCount(): Int = synchronized(lock) { pending.size + if (inFlight != null) 1 else 0 }

    private fun requeueInFlightLocked() {
        inFlight?.let(pending::addFirst)
        inFlight = null
    }

    companion object {
        fun getInstance(project: Project): OpenCodeChatInputService {
            return project.getService(OpenCodeChatInputService::class.java)
        }
    }
}
