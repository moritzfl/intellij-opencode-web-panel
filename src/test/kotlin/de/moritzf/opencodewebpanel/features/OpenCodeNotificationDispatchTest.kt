package de.moritzf.opencodewebpanel.features

import com.google.gson.JsonObject
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEvent
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
            process = {
                processed.add(it)
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
            process = {
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
            process = { OpenCodeNotificationEventProcessor.Outcome.Dismiss("session:ses_1") },
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
            process = { OpenCodeNotificationEventProcessor.Outcome.Dismiss("session:ses_1") },
            dispatch = { _, captured -> dispatched.add(captured) },
            executeAsync = { it() },
        )

        dispatcher.eventReceived(event())

        assertEquals(listOf(identity), dispatched)
    }

    @Test
    fun lifecycleInvalidationClearsEveryKeyAndExpiresEachItemOnce() {
        val registry = OpenCodeActiveNotificationRegistry<String>()
        registry.register("request:per_1", "permission")
        registry.register("session:ses_1", "permission")
        registry.register("session:ses_1", "response")
        var resetCount = 0
        val expired = mutableListOf<String>()
        val invalidator = OpenCodeNotificationInvalidator(
            registry = registry,
            resetReducedState = { resetCount++ },
            expire = expired::addAll,
        )

        invalidator.invalidate()

        assertEquals(1, resetCount)
        assertEquals(listOf("permission", "response"), expired)
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
            activeRequestKeys = { setOf("request:per_1", "request:stale") },
            process = { OpenCodeNotificationEventProcessor.Outcome.Dismiss("processed:${it.recordId}") },
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
            activeRequestKeys = { setOf("request:stale") },
            process = { OpenCodeNotificationEventProcessor.Outcome.Dismiss("processed:${it.recordId}") },
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
            activeRequestKeys = { setOf("request:stale") },
            process = { OpenCodeNotificationEventProcessor.Outcome.Dismiss("processed") },
            dispatch = { outcome, _ -> outcomes.add(outcome) },
            executeAsync = { it() },
        )

        reconciler.reconcile()

        assertTrue(outcomes.isEmpty())
    }
}
