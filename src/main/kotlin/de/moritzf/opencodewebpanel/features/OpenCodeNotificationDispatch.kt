package de.moritzf.opencodewebpanel.features

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
    private val process: (OpenCodeGlobalEvent) -> OpenCodeNotificationEventProcessor.Outcome?,
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
            val outcome = process(event) ?: return@executeAsync
            if (!stillCurrent(identity)) return@executeAsync
            dispatch(outcome, identity)
        }
    }

    private fun stillCurrent(identity: OpenCodeNotificationServerIdentity): Boolean {
        return enabled() && serverIdentity() == identity
    }
}

/** Tracks notifications under every request/session dismissal key they belong to. */
internal class OpenCodeActiveNotificationRegistry<T> {
    private val itemsByKey = mutableMapOf<String, MutableList<T>>()

    @Synchronized
    fun register(key: String, item: T) {
        itemsByKey.getOrPut(key) { mutableListOf() }.add(item)
    }

    @Synchronized
    fun removeByKey(key: String): List<T> = itemsByKey.remove(key)?.toList().orEmpty()

    @Synchronized
    fun remove(key: String, item: T) {
        val remaining = itemsByKey[key] ?: return
        remaining.remove(item)
        if (remaining.isEmpty()) itemsByKey.remove(key)
    }

    @Synchronized
    fun clear(): List<T> {
        val items = itemsByKey.values.flatten().distinct()
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
