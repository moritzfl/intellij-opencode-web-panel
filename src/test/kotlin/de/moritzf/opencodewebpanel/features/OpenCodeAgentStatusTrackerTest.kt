package de.moritzf.opencodewebpanel.features

import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
}
