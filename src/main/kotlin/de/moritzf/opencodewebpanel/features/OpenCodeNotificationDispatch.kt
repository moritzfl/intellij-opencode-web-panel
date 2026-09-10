package de.moritzf.opencodewebpanel.features

import com.google.gson.JsonObject
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEvent
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol

internal data class OpenCodeNotificationServerIdentity(
    val generation: Long,
    val serverUrl: String,
    val notificationEpoch: Long,
    val directory: String = "",
)

/** Drops queued or slow notification events when their originating server is no longer current. */
internal class OpenCodeNotificationEventDispatcher(
    private val enabled: () -> Boolean,
    private val serverIdentity: (directory: String) -> OpenCodeNotificationServerIdentity?,
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
        val identity = serverIdentity(event.directory) ?: return
        executeAsync {
            if (!stillCurrent(identity, event.directory)) return@executeAsync
            val outcome = process(event, identity) ?: return@executeAsync
            if (!stillCurrent(identity, event.directory)) return@executeAsync
            dispatch(outcome, identity)
        }
    }

    private fun stillCurrent(identity: OpenCodeNotificationServerIdentity, directory: String): Boolean {
        return enabled() && serverIdentity(directory) == identity
    }
}

/** Applies ordered event outcomes on the UI thread after one final identity check. */
internal class OpenCodeNotificationOutcomeDispatcher(
    private val enabled: () -> Boolean,
    private val serverIdentity: (directory: String) -> OpenCodeNotificationServerIdentity?,
    private val notify: (
        payload: OpenCodeServerProtocol.SystemNotificationPayload,
        identity: OpenCodeNotificationServerIdentity,
    ) -> Unit,
    private val dismiss: (key: String) -> Unit,
    private val activeRequestKeys: () -> Set<String> = { emptySet() },
    private val executeOnUi: ((() -> Unit) -> Unit),
) {
    fun dispatch(
        outcome: OpenCodeNotificationEventProcessor.Outcome,
        identity: OpenCodeNotificationServerIdentity,
    ) {
        executeOnUi {
            if (!enabled() || serverIdentity(directoryFor(outcome, identity)) != identity) return@executeOnUi
            when (outcome) {
                is OpenCodeNotificationEventProcessor.Outcome.Notify -> notify(outcome.payload, identity)
                is OpenCodeNotificationEventProcessor.Outcome.Dismiss -> dismiss(outcome.key)
            }
        }
    }

    fun reconcileRequestKeys(pendingKeys: Set<String>, identity: OpenCodeNotificationServerIdentity) {
        executeOnUi {
            if (!enabled() || serverIdentity(identity.directory) != identity) return@executeOnUi
            (activeRequestKeys() - pendingKeys).forEach(dismiss)
        }
    }

    private fun directoryFor(
        outcome: OpenCodeNotificationEventProcessor.Outcome,
        identity: OpenCodeNotificationServerIdentity,
    ): String {
        return when (outcome) {
            is OpenCodeNotificationEventProcessor.Outcome.Notify -> outcome.payload.directory
            is OpenCodeNotificationEventProcessor.Outcome.Dismiss -> identity.directory
        }
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
    private val serverIdentity: (directory: String) -> OpenCodeNotificationServerIdentity?,
    private val directories: () -> List<String>,
    private val load: (
        identity: OpenCodeNotificationServerIdentity,
        directory: String,
    ) -> OpenCodePendingNotificationLoad,
    private val reconcileActiveRequestKeys: (
        pendingKeys: Set<String>,
        identity: OpenCodeNotificationServerIdentity,
    ) -> Unit,
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
        executeAsync {
            var allAuthoritative = true
            val pending = mutableListOf<OpenCodePendingNotificationRequest>()
            var keyIdentity: OpenCodeNotificationServerIdentity? = null
            for (directory in directories()) {
                val identity = serverIdentity(directory)
                if (identity == null) {
                    allAuthoritative = false
                    continue
                }
                if (!stillCurrent(identity, directory)) return@executeAsync
                val result = load(identity, directory)
                if (!stillCurrent(identity, directory)) return@executeAsync
                allAuthoritative = allAuthoritative && result.authoritative
                pending.addAll(result.requests)
                if (keyIdentity == null) keyIdentity = identity
            }
            if (allAuthoritative) {
                val identity = keyIdentity
                if (identity != null && stillCurrent(identity, identity.directory)) {
                    val pendingKeys = pending.mapTo(mutableSetOf()) { "request:${it.id}" }
                    reconcileActiveRequestKeys(pendingKeys, identity)
                }
            }
            for (request in pending.distinctBy { it.id }) {
                val identity = serverIdentity(request.directory) ?: continue
                if (!stillCurrent(identity, request.directory)) return@executeAsync
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
                if (!stillCurrent(identity, request.directory)) return@executeAsync
                dispatch(outcome, identity)
            }
        }
    }

    private fun stillCurrent(identity: OpenCodeNotificationServerIdentity, directory: String): Boolean {
        return enabled() && serverIdentity(directory) == identity
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
