package de.moritzf.opencodewebpanel.features

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeAgentStatusTrackerTest {

    private fun properties(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun sessionStatusEventsToggleBusyState() {
        val state = OpenCodeAgentStatusState()
        assertEquals(OpenCodeAgentStatusState.IDLE, state.current())

        assertTrue(state.applyEvent("session.status", properties("""{"sessionID":"ses_1","status":{"type":"busy"}}""")))
        assertEquals(OpenCodeAgentStatusState.BUSY, state.current())

        assertTrue(state.applyEvent("session.status", properties("""{"sessionID":"ses_2","status":{"type":"retry"}}""")))
        assertTrue(state.applyEvent("session.status", properties("""{"sessionID":"ses_1","status":{"type":"idle"}}""")))
        assertEquals(OpenCodeAgentStatusState.BUSY, state.current())

        assertTrue(state.applyEvent("session.idle", properties("""{"sessionID":"ses_2"}""")))
        assertEquals(OpenCodeAgentStatusState.IDLE, state.current())
    }

    @Test
    fun pendingRequestsOutrankBusySessions() {
        val state = OpenCodeAgentStatusState()
        state.applyEvent("session.status", properties("""{"sessionID":"ses_1","status":{"type":"busy"}}"""))
        state.applyEvent("permission.asked", properties("""{"id":"per_1","sessionID":"ses_1"}"""))
        assertEquals(OpenCodeAgentStatusState.ATTENTION, state.current())

        state.applyEvent("permission.replied", properties("""{"requestID":"per_1"}"""))
        assertEquals(OpenCodeAgentStatusState.BUSY, state.current())

        state.applyEvent("question.asked", properties("""{"id":"que_1"}"""))
        assertEquals(OpenCodeAgentStatusState.ATTENTION, state.current())
        state.applyEvent("question.rejected", properties("""{"requestID":"que_1"}"""))
        assertEquals(OpenCodeAgentStatusState.BUSY, state.current())
    }

    @Test
    fun irrelevantOrMalformedEventsDoNotAffectState() {
        val state = OpenCodeAgentStatusState()
        assertFalse(state.applyEvent("message.updated", properties("""{"sessionID":"ses_1"}""")))
        assertFalse(state.applyEvent("session.status", properties("""{"status":{"type":"busy"}}""")))
        assertFalse(state.applyEvent("session.status", properties("""{"sessionID":"ses_1"}""")))
        assertFalse(state.applyEvent("session.status", properties("""{"sessionID":"ses_1","status":{"type":"queued"}}""")))
        assertFalse(state.applyEvent("session.idle", properties("{}")))
        assertFalse(state.applyEvent("permission.asked", properties("""{"sessionID":"ses_1"}""")))
        assertFalse(state.applyEvent("permission.replied", properties("{}")))
        assertEquals(OpenCodeAgentStatusState.IDLE, state.current())
    }

    @Test
    fun seedReplacesStateAndKeepsItOnNulls() {
        val state = OpenCodeAgentStatusState()
        state.applyEvent("session.status", properties("""{"sessionID":"ses_stale","status":{"type":"busy"}}"""))
        state.applyEvent("permission.asked", properties("""{"id":"per_stale"}"""))

        state.seed(busySessionIds = setOf("ses_1"), pendingRequestIds = emptyList())
        assertEquals(OpenCodeAgentStatusState.BUSY, state.current())

        // A failed REST fetch (null) must not wipe state built from live events.
        state.seed(busySessionIds = null, pendingRequestIds = null)
        assertEquals(OpenCodeAgentStatusState.BUSY, state.current())

        state.seed(busySessionIds = emptySet(), pendingRequestIds = listOf("per_1"))
        assertEquals(OpenCodeAgentStatusState.ATTENTION, state.current())

        state.clear()
        assertEquals(OpenCodeAgentStatusState.IDLE, state.current())
    }

    @Test
    fun latestSeedWinsWhenOlderProjectCompletesLast() {
        val tasks = mutableListOf<() -> Unit>()
        val transitions = mutableListOf<String>()
        var directory = "/project-a"
        val tracker = OpenCodeAgentStatusTracker(
            projectDirectory = { directory },
            enabled = { true },
            onStateChanged = { state, _ -> transitions.add(state) },
            serverUrl = { "http://127.0.0.1:4096" },
            serverPassword = { "pw" },
            serverGeneration = { 1L },
            loadSnapshot = { _, _, requestedDirectory ->
                if (requestedDirectory == "/project-a") {
                    OpenCodeAgentStatusSnapshot(setOf("ses_a"), emptyList())
                } else {
                    OpenCodeAgentStatusSnapshot(emptySet(), listOf("per_b"))
                }
            },
            executeAsync = tasks::add,
        )

        tracker.seed()
        directory = "/project-b"
        tracker.reset()
        tracker.seed()

        tasks[1]()
        tasks[0]()

        assertEquals(listOf(OpenCodeAgentStatusState.ATTENTION), transitions)
    }

    @Test
    fun partialPendingSnapshotDoesNotClearEventDerivedAttention() {
        val tasks = mutableListOf<() -> Unit>()
        val transitions = mutableListOf<String>()
        val directory = "/project"
        val tracker = OpenCodeAgentStatusTracker(
            projectDirectory = { directory },
            enabled = { true },
            onStateChanged = { state, _ -> transitions.add(state) },
            serverUrl = { "http://127.0.0.1:4096" },
            serverPassword = { "pw" },
            serverGeneration = { 1L },
            loadSnapshot = { _, _, _ -> OpenCodeAgentStatusSnapshot(emptySet(), null) },
            executeAsync = tasks::add,
        )
        tracker.eventReceived(
            OpenCodeGlobalEvent(directory, "permission.asked", "evt_1", properties("""{"id":"per_1"}""")),
        )

        tracker.seed()
        tasks.single()()
        tracker.eventReceived(
            OpenCodeGlobalEvent(directory, "permission.replied", "evt_2", properties("""{"requestID":"per_1"}""")),
        )

        assertEquals(
            listOf(OpenCodeAgentStatusState.ATTENTION, OpenCodeAgentStatusState.IDLE),
            transitions,
        )
    }

    @Test
    fun stalePresentationCallbackCannotOverrideNewerIdleState() {
        val tasks = mutableListOf<() -> Unit>()
        val callbacks = mutableListOf<Pair<String, Long>>()
        val directory = "/project"
        val tracker = OpenCodeAgentStatusTracker(
            projectDirectory = { directory },
            enabled = { true },
            onStateChanged = { state, revision -> callbacks.add(state to revision) },
            serverUrl = { "http://127.0.0.1:4096" },
            serverPassword = { "pw" },
            serverGeneration = { 1L },
            loadSnapshot = { _, _, _ -> OpenCodeAgentStatusSnapshot(setOf("ses_1"), emptyList()) },
            executeAsync = tasks::add,
        )

        tracker.seed()
        tasks.single()()
        tracker.eventReceived(
            OpenCodeGlobalEvent(directory, "session.status", "evt_1", properties("""{"sessionID":"ses_1","status":{"type":"idle"}}""")),
        )

        var displayed = OpenCodeAgentStatusState.IDLE
        callbacks.asReversed().forEach { (state, revision) ->
            if (tracker.isCurrentPresentation(state, revision)) displayed = state
        }
        assertEquals(OpenCodeAgentStatusState.IDLE, displayed)
        assertEquals(
            listOf(OpenCodeAgentStatusState.BUSY, OpenCodeAgentStatusState.IDLE),
            callbacks.map { it.first },
        )
    }

    @Test
    fun equivalentBusyEventKeepsQueuedBusyPresentationCurrent() {
        val tasks = mutableListOf<() -> Unit>()
        val callbacks = mutableListOf<Pair<String, Long>>()
        val tracker = OpenCodeAgentStatusTracker(
            projectDirectory = { "/project" },
            enabled = { true },
            onStateChanged = { state, revision -> callbacks.add(state to revision) },
            serverUrl = { "http://127.0.0.1:4096" },
            serverPassword = { "pw" },
            serverGeneration = { 1L },
            loadSnapshot = { _, _, _ -> OpenCodeAgentStatusSnapshot(setOf("ses_1"), emptyList()) },
            executeAsync = tasks::add,
        )
        tracker.seed()
        tasks.single()()
        val queued = callbacks.single()

        tracker.eventReceived(
            OpenCodeGlobalEvent(
                "/project",
                "session.status",
                "evt_1",
                properties("""{"sessionID":"ses_2","status":{"type":"busy"}}"""),
            ),
        )

        assertTrue(tracker.isCurrentPresentation(queued.first, queued.second))
    }
}
