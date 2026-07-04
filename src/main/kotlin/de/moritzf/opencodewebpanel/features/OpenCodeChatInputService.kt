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
    private val lock = Any()
    private var dispatcher: ((List<String>) -> Boolean)? = null
    private val pending = mutableListOf<String>()

    fun setDispatcher(dispatcher: ((List<String>) -> Boolean)?) {
        synchronized(lock) {
            this.dispatcher = dispatcher
        }
    }

    /** Dispatches immediately when the panel is ready, otherwise queues. Returns true if dispatched. */
    fun send(texts: List<String>): Boolean {
        if (texts.isEmpty()) return true
        val currentDispatcher = synchronized(lock) { dispatcher }
        if (currentDispatcher != null && currentDispatcher(texts)) return true
        synchronized(lock) {
            pending.addAll(texts)
        }
        return false
    }

    fun hasPending(): Boolean = synchronized(lock) { pending.isNotEmpty() }

    fun takePending(): List<String> = synchronized(lock) { pending.toList().also { pending.clear() } }

    companion object {
        fun getInstance(project: Project): OpenCodeChatInputService {
            return project.getService(OpenCodeChatInputService::class.java)
        }
    }
}
