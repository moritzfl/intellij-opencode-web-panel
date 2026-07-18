package de.moritzf.opencodewebpanel.features

import com.google.gson.JsonObject
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEvent

internal data class OpenCodeNotificationServerIdentity(
    val generation: Long,
    val serverUrl: String,
    val notificationEpoch: Long,
)

/** Drops queued or slow notification events when their originating server is no longer current. */
internal class OpenCodeNotificationEventDispatcher(
    private val enabled: () -> Boolean,
    private val serverIdentity: () -> OpenCodeNotificationServerIdentity?,
    private val process: (
        event: OpenCodeGlobalEvent,
        identity: OpenCodeNotificationServerIdentity,
    ) -> OpenCodeNotificationEventProcessor.Outcome?,
    private val dispatch: (
        outcome: OpenCodeNotificationEventProcessor.Outcome,
        identity: OpenCodeNotificationServerIdentity,
    ) -> Unit,
    private val executeAsync: ((() -> Unit) -> Unit),
) {
    fun eventReceived(event: OpenCodeGlobalEvent) {
        if (event.type !in OpenCodeNotificationEventProcessor.RELEVANT_EVENT_TYPES || !enabled()) return
        val identity = serverIdentity() ?: return
        executeAsync {
            if (!stillCurrent(identity)) return@executeAsync
            val outcome = process(event, identity) ?: return@executeAsync
            if (!stillCurrent(identity)) return@executeAsync
            dispatch(outcome, identity)
        }
    }

    private fun stillCurrent(identity: OpenCodeNotificationServerIdentity): Boolean {
        return enabled() && serverIdentity() == identity
    }
}

internal data class OpenCodePendingNotificationRequest(
    val directory: String,
    val type: String,
    val id: String,
    val sessionID: String,
)

internal data class OpenCodePendingNotificationLoad(
    val requests: List<OpenCodePendingNotificationRequest>,
    /** False when either endpoint failed, so absence cannot dismiss existing notifications. */
    val authoritative: Boolean,
)

/** Reconciles durable permission/question state after the global SSE stream reconnects. */
internal class OpenCodePendingNotificationReconciler(
    private val enabled: () -> Boolean,
    private val serverIdentity: () -> OpenCodeNotificationServerIdentity?,
    private val directories: () -> List<String>,
    private val load: (
        identity: OpenCodeNotificationServerIdentity,
        directory: String,
    ) -> OpenCodePendingNotificationLoad,
    private val activeRequestKeys: () -> Set<String>,
    private val process: (
        event: OpenCodeGlobalEvent,
        identity: OpenCodeNotificationServerIdentity,
    ) -> OpenCodeNotificationEventProcessor.Outcome?,
    private val dispatch: (
        outcome: OpenCodeNotificationEventProcessor.Outcome,
        identity: OpenCodeNotificationServerIdentity,
    ) -> Unit,
    private val executeAsync: ((() -> Unit) -> Unit),
) {
    fun reconcile() {
        if (!enabled()) return
        val identity = serverIdentity() ?: return
        executeAsync {
            if (!stillCurrent(identity)) return@executeAsync
            var allAuthoritative = true
            val pending = mutableListOf<OpenCodePendingNotificationRequest>()
            for (directory in directories()) {
                if (!stillCurrent(identity)) return@executeAsync
                val result = load(identity, directory)
                allAuthoritative = allAuthoritative && result.authoritative
                pending.addAll(result.requests)
            }
            if (!stillCurrent(identity)) return@executeAsync
            if (allAuthoritative) {
                val pendingKeys = pending.mapTo(mutableSetOf()) { "request:${it.id}" }
                (activeRequestKeys() - pendingKeys).forEach { key ->
                    dispatch(OpenCodeNotificationEventProcessor.Outcome.Dismiss(key), identity)
                }
            }
            for (request in pending.distinctBy { it.id }) {
                if (!stillCurrent(identity)) return@executeAsync
                val properties = JsonObject().apply {
                    addProperty("id", request.id)
                    addProperty("sessionID", request.sessionID)
                }
                val outcome = process(
                    OpenCodeGlobalEvent(
                        directory = request.directory,
                        type = request.type,
                        recordId = request.id,
                        properties = properties,
                    ),
                    identity,
                ) ?: continue
                if (!stillCurrent(identity)) return@executeAsync
                dispatch(outcome, identity)
            }
        }
    }

    private fun stillCurrent(identity: OpenCodeNotificationServerIdentity): Boolean {
        return enabled() && serverIdentity() == identity
    }
}

/** Tracks notifications under every request/session dismissal key they belong to. */
internal class OpenCodeActiveNotificationRegistry<T> {
    private val itemsByKey = mutableMapOf<String, MutableList<T>>()
    private val trackedItems = linkedSetOf<T>()

    @Synchronized
    fun track(item: T) {
        trackedItems.add(item)
    }

    @Synchronized
    fun register(key: String, item: T) {
        trackedItems.add(item)
        itemsByKey.getOrPut(key) { mutableListOf() }.add(item)
    }

    @Synchronized
    fun removeByKey(key: String): List<T> = itemsByKey.remove(key)?.toList().orEmpty()

    @Synchronized
    fun containsKey(key: String): Boolean = itemsByKey.containsKey(key)

    @Synchronized
    fun keys(prefix: String): Set<String> = itemsByKey.keys.filterTo(mutableSetOf()) { it.startsWith(prefix) }

    @Synchronized
    fun removeItem(item: T) {
        trackedItems.remove(item)
        val iterator = itemsByKey.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.remove(item)
            if (entry.value.isEmpty()) iterator.remove()
        }
    }

    @Synchronized
    fun clear(): List<T> {
        val items = trackedItems.toList()
        trackedItems.clear()
        itemsByKey.clear()
        return items
    }
}

internal class OpenCodeNotificationInvalidator<T>(
    private val registry: OpenCodeActiveNotificationRegistry<T>,
    private val resetReducedState: () -> Unit,
    private val expire: (List<T>) -> Unit,
) {
    fun invalidate() {
        resetReducedState()
        expire(registry.clear())
    }
}
