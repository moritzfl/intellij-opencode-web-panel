package de.moritzf.opencodewebpanel.features

import com.google.gson.JsonObject
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEvent
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeNotificationDispatchTest {
    private fun event(): OpenCodeGlobalEvent {
        return OpenCodeGlobalEvent("/project", "session.idle", "evt_1", JsonObject())
    }

    @Test
    fun queuedEventFromOlderServerGenerationIsDroppedBeforeProcessing() {
        val tasks = mutableListOf<() -> Unit>()
        val processed = mutableListOf<OpenCodeGlobalEvent>()
        val dispatched = mutableListOf<OpenCodeNotificationEventProcessor.Outcome>()
        var identity = OpenCodeNotificationServerIdentity(1L, "http://127.0.0.1:4096", 0L)
        val dispatcher = OpenCodeNotificationEventDispatcher(
            enabled = { true },
            serverIdentity = { identity },
            process = { event, _ ->
                processed.add(event)
                OpenCodeNotificationEventProcessor.Outcome.Dismiss("session:ses_1")
            },
            dispatch = { outcome, _ -> dispatched.add(outcome) },
            executeAsync = tasks::add,
        )

        dispatcher.eventReceived(event())
        identity = identity.copy(generation = 2L)
        tasks.single()()

        assertTrue(processed.isEmpty())
        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun generationChangeDuringSessionLookupDropsProcessedOutcome() {
        val dispatched = mutableListOf<OpenCodeNotificationEventProcessor.Outcome>()
        var identity = OpenCodeNotificationServerIdentity(1L, "http://127.0.0.1:4096", 0L)
        val dispatcher = OpenCodeNotificationEventDispatcher(
            enabled = { true },
            serverIdentity = { identity },
            process = { _, _ ->
                identity = identity.copy(generation = 2L)
                OpenCodeNotificationEventProcessor.Outcome.Dismiss("session:ses_1")
            },
            dispatch = { outcome, _ -> dispatched.add(outcome) },
            executeAsync = { it() },
        )

        dispatcher.eventReceived(event())

        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun settingEpochInvalidatesQueuedEventsWithoutServerRestart() {
        val tasks = mutableListOf<() -> Unit>()
        val dispatched = mutableListOf<OpenCodeNotificationEventProcessor.Outcome>()
        var identity = OpenCodeNotificationServerIdentity(1L, "http://127.0.0.1:4096", 0L)
        val dispatcher = OpenCodeNotificationEventDispatcher(
            enabled = { true },
            serverIdentity = { identity },
            process = { _, _ -> OpenCodeNotificationEventProcessor.Outcome.Dismiss("session:ses_1") },
            dispatch = { outcome, _ -> dispatched.add(outcome) },
            executeAsync = tasks::add,
        )

        dispatcher.eventReceived(event())
        identity = identity.copy(notificationEpoch = 1L)
        tasks.single()()

        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun currentEventDispatchesWithCapturedIdentity() {
        val identity = OpenCodeNotificationServerIdentity(1L, "http://127.0.0.1:4096", 0L)
        val dispatched = mutableListOf<OpenCodeNotificationServerIdentity>()
        val dispatcher = OpenCodeNotificationEventDispatcher(
            enabled = { true },
            serverIdentity = { identity },
            process = { _, _ -> OpenCodeNotificationEventProcessor.Outcome.Dismiss("session:ses_1") },
            dispatch = { _, captured -> dispatched.add(captured) },
            executeAsync = { it() },
        )

        dispatcher.eventReceived(event())

        assertEquals(listOf(identity), dispatched)
    }

    @Test
    fun notifyAndDismissOutcomesApplyInUiQueueOrder() {
        val identity = OpenCodeNotificationServerIdentity(1L, "http://127.0.0.1:4096", 0L)
        val uiTasks = mutableListOf<() -> Unit>()
        val active = mutableSetOf<String>()
        val actions = mutableListOf<String>()
        val dispatcher = OpenCodeNotificationOutcomeDispatcher(
            enabled = { true },
            serverIdentity = { identity },
            notify = { payload, _ ->
                actions += "notify"
                active += "request:${payload.requestID}"
            },
            dismiss = { key ->
                actions += "dismiss"
                active -= key
            },
            executeOnUi = uiTasks::add,
        )
        val payload = OpenCodeServerProtocol.SystemNotificationPayload(
            id = "per_1",
            directory = "/project",
            route = "/route",
            title = "Permission required",
            body = "body",
            kind = "permission",
            sessionID = "ses_1",
            requestID = "per_1",
        )

        dispatcher.dispatch(OpenCodeNotificationEventProcessor.Outcome.Notify(payload), identity)
        dispatcher.dispatch(OpenCodeNotificationEventProcessor.Outcome.Dismiss("request:per_1"), identity)
        uiTasks.forEach { it() }

        assertEquals(listOf("notify", "dismiss"), actions)
        assertTrue(active.isEmpty())
    }

    @Test
    fun staleOutcomeIsDroppedAgainOnUiThread() {
        val uiTasks = mutableListOf<() -> Unit>()
        var identity = OpenCodeNotificationServerIdentity(1L, "http://127.0.0.1:4096", 0L)
        val actions = mutableListOf<String>()
        val dispatcher = OpenCodeNotificationOutcomeDispatcher(
            enabled = { true },
            serverIdentity = { identity },
            notify = { _, _ -> actions += "notify" },
            dismiss = { actions += "dismiss" },
            executeOnUi = uiTasks::add,
        )

        dispatcher.dispatch(OpenCodeNotificationEventProcessor.Outcome.Dismiss("session:ses_1"), identity)
        identity = identity.copy(generation = 2L)
        uiTasks.single()()

        assertTrue(actions.isEmpty())
    }

    @Test
    fun reconnectReconciliationSeesEarlierQueuedNotification() {
        val identity = OpenCodeNotificationServerIdentity(1L, "http://127.0.0.1:4096", 0L)
        val uiTasks = mutableListOf<() -> Unit>()
        val active = mutableSetOf<String>()
        val actions = mutableListOf<String>()
        val outcomeDispatcher = OpenCodeNotificationOutcomeDispatcher(
            enabled = { true },
            serverIdentity = { identity },
            notify = { payload, _ ->
                actions += "notify"
                active += "request:${payload.requestID}"
            },
            dismiss = { key ->
                actions += "dismiss"
                active -= key
            },
            activeRequestKeys = { active.toSet() },
            executeOnUi = uiTasks::add,
        )
        val payload = OpenCodeServerProtocol.SystemNotificationPayload(
            id = "per_1",
            directory = "/project",
            route = "/route",
            title = "Permission required",
            body = "body",
            kind = "permission",
            sessionID = "ses_1",
            requestID = "per_1",
        )
        outcomeDispatcher.dispatch(OpenCodeNotificationEventProcessor.Outcome.Notify(payload), identity)
        val reconciler = OpenCodePendingNotificationReconciler(
            enabled = { true },
            serverIdentity = { identity },
            directories = { listOf("/project") },
            load = { _, _ -> OpenCodePendingNotificationLoad(emptyList(), authoritative = true) },
            reconcileActiveRequestKeys = outcomeDispatcher::reconcileRequestKeys,
            process = { _, _ -> null },
            dispatch = outcomeDispatcher::dispatch,
            executeAsync = { it() },
        )

        reconciler.reconcile()
        uiTasks.forEach { it() }

        assertEquals(listOf("notify", "dismiss"), actions)
        assertTrue(active.isEmpty())
    }

    @Test
    fun lifecycleInvalidationClearsEveryKeyAndExpiresEachItemOnce() {
        val registry = OpenCodeActiveNotificationRegistry<String>()
        registry.register("request:per_1", "permission")
        registry.register("session:ses_1", "permission")
        registry.register("session:ses_1", "response")
        registry.track("unkeyed error")
        var resetCount = 0
        val expired = mutableListOf<String>()
        val invalidator = OpenCodeNotificationInvalidator(
            registry = registry,
            resetReducedState = { resetCount++ },
            expire = expired::addAll,
        )

        invalidator.invalidate()

        assertEquals(1, resetCount)
        assertEquals(listOf("permission", "response", "unkeyed error"), expired)
        assertTrue(registry.removeByKey("request:per_1").isEmpty())
        assertTrue(registry.removeByKey("session:ses_1").isEmpty())
    }

    @Test
    fun pendingReconciliationShowsCurrentRequestsAndDismissesStaleKeys() {
        val identity = OpenCodeNotificationServerIdentity(1L, "http://127.0.0.1:4096", 0L)
        val outcomes = mutableListOf<OpenCodeNotificationEventProcessor.Outcome>()
        val reconciler = OpenCodePendingNotificationReconciler(
            enabled = { true },
            serverIdentity = { identity },
            directories = { listOf("/project") },
            load = { _, directory ->
                OpenCodePendingNotificationLoad(
                    requests = listOf(
                        OpenCodePendingNotificationRequest(directory, "permission.asked", "per_1", "ses_1"),
                        OpenCodePendingNotificationRequest(directory, "question.asked", "que_1", "ses_2"),
                    ),
                    authoritative = true,
                )
            },
            reconcileActiveRequestKeys = { pending, _ ->
                (setOf("request:per_1", "request:stale") - pending).forEach { key ->
                    outcomes.add(OpenCodeNotificationEventProcessor.Outcome.Dismiss(key))
                }
            },
            process = { event, _ -> OpenCodeNotificationEventProcessor.Outcome.Dismiss("processed:${event.recordId}") },
            dispatch = { outcome, _ -> outcomes.add(outcome) },
            executeAsync = { it() },
        )

        reconciler.reconcile()

        assertEquals(
            listOf(
                OpenCodeNotificationEventProcessor.Outcome.Dismiss("request:stale"),
                OpenCodeNotificationEventProcessor.Outcome.Dismiss("processed:per_1"),
                OpenCodeNotificationEventProcessor.Outcome.Dismiss("processed:que_1"),
            ),
            outcomes,
        )
    }

    @Test
    fun partialPendingSnapshotNeverDismissesExistingRequests() {
        val identity = OpenCodeNotificationServerIdentity(1L, "http://127.0.0.1:4096", 0L)
        val outcomes = mutableListOf<OpenCodeNotificationEventProcessor.Outcome>()
        val reconciler = OpenCodePendingNotificationReconciler(
            enabled = { true },
            serverIdentity = { identity },
            directories = { listOf("/project") },
            load = { _, directory ->
                OpenCodePendingNotificationLoad(
                    listOf(OpenCodePendingNotificationRequest(directory, "permission.asked", "per_1", "ses_1")),
                    authoritative = false,
                )
            },
            reconcileActiveRequestKeys = { pending, _ ->
                (setOf("request:stale") - pending).forEach { key ->
                    outcomes.add(OpenCodeNotificationEventProcessor.Outcome.Dismiss(key))
                }
            },
            process = { event, _ -> OpenCodeNotificationEventProcessor.Outcome.Dismiss("processed:${event.recordId}") },
            dispatch = { outcome, _ -> outcomes.add(outcome) },
            executeAsync = { it() },
        )

        reconciler.reconcile()

        assertEquals(
            listOf(OpenCodeNotificationEventProcessor.Outcome.Dismiss("processed:per_1")),
            outcomes,
        )
    }

    @Test
    fun serverChangeDuringPendingFetchDropsEntireSnapshot() {
        var identity = OpenCodeNotificationServerIdentity(1L, "http://127.0.0.1:4096", 0L)
        val outcomes = mutableListOf<OpenCodeNotificationEventProcessor.Outcome>()
        val reconciler = OpenCodePendingNotificationReconciler(
            enabled = { true },
            serverIdentity = { identity },
            directories = { listOf("/project") },
            load = { _, directory ->
                identity = identity.copy(generation = 2L)
                OpenCodePendingNotificationLoad(
                    listOf(OpenCodePendingNotificationRequest(directory, "permission.asked", "per_1", "ses_1")),
                    authoritative = true,
                )
            },
            reconcileActiveRequestKeys = { _, _ ->
                outcomes.add(OpenCodeNotificationEventProcessor.Outcome.Dismiss("request:stale"))
            },
            process = { _, _ -> OpenCodeNotificationEventProcessor.Outcome.Dismiss("processed") },
            dispatch = { outcome, _ -> outcomes.add(outcome) },
            executeAsync = { it() },
        )

        reconciler.reconcile()

        assertTrue(outcomes.isEmpty())
    }
}
