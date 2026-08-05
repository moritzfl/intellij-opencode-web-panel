package de.moritzf.opencodewebpanel.features

import com.google.gson.JsonParser
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEvent
import de.moritzf.opencodewebpanel.server.OpenCodeProtocolResult
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class OpenCodePermissionAutoResponderTest {

    private data class Reply(
        val directory: String,
        val sessionID: String,
        val requestID: String,
        val response: OpenCodeServerProtocol.PermissionResponse,
    )

    @Test
    fun repliesOnceOnlyForEnabledSession() {
        val fixture = fixture()
        fixture.responder.setEnabled("ses_a", true)
        fixture.drain()

        fixture.responder.eventReceived(permissionEvent("/tmp/project", "ses_b", "per_b"))
        fixture.responder.eventReceived(permissionEvent("/tmp/project", "ses_a", "per_a"))
        fixture.drain()

        assertEquals(
            listOf(Reply("/tmp/project", "ses_a", "per_a", OpenCodeServerProtocol.PermissionResponse.ONCE)),
            fixture.replies,
        )
    }

    @Test
    fun enablingSessionAnswersOnlyItsAlreadyPendingRequests() {
        val fixture = fixture(
            pending = listOf(
                OpenCodeServerProtocol.PendingRequestSummary("per_a", "ses_a"),
                OpenCodeServerProtocol.PendingRequestSummary("per_b", "ses_b"),
            ),
        )

        fixture.responder.setEnabled("ses_a", true)
        fixture.drain()

        assertEquals(listOf("per_a"), fixture.replies.map(Reply::requestID))
    }

    @Test
    fun sessionTogglesRemainIndependent() {
        val fixture = fixture()
        fixture.responder.setEnabled("ses_a", true)
        fixture.responder.setEnabled("ses_b", true)
        fixture.drain()
        fixture.responder.setEnabled("ses_a", false)

        assertFalse(fixture.responder.isEnabled("ses_a"))
        assertTrue(fixture.responder.isEnabled("ses_b"))

        fixture.responder.eventReceived(permissionEvent("/tmp/project", "ses_a", "per_a"))
        fixture.responder.eventReceived(permissionEvent("/tmp/project", "ses_b", "per_b"))
        fixture.drain()

        assertEquals(listOf("per_b"), fixture.replies.map(Reply::requestID))
    }

    @Test
    fun disablingSessionCancelsQueuedReply() {
        val fixture = fixture()
        fixture.responder.setEnabled("ses_a", true)
        fixture.drain()
        fixture.responder.eventReceived(permissionEvent("/tmp/project", "ses_a", "per_a"))
        fixture.responder.setEnabled("ses_a", false)

        fixture.drain()

        assertTrue(fixture.replies.isEmpty())
    }

    @Test
    fun ignoresOtherDirectoriesAndMalformedRequests() {
        val fixture = fixture()
        fixture.responder.setEnabled("ses_a", true)
        fixture.drain()

        fixture.responder.eventReceived(permissionEvent("/tmp/other", "ses_a", "per_other"))
        fixture.responder.eventReceived(permissionEvent("/tmp/project", "bad", "per_bad"))
        fixture.responder.eventReceived(permissionEvent("/tmp/project", "ses_a", "bad"))
        fixture.drain()

        assertTrue(fixture.replies.isEmpty())
    }

    @Test
    fun reconnectReseedsAllEnabledSessions() {
        val fixture = fixture(
            pending = listOf(
                OpenCodeServerProtocol.PendingRequestSummary("per_a", "ses_a"),
                OpenCodeServerProtocol.PendingRequestSummary("per_b", "ses_b"),
                OpenCodeServerProtocol.PendingRequestSummary("per_c", "ses_c"),
            ),
        )
        fixture.responder.setEnabled("ses_a", true)
        fixture.responder.setEnabled("ses_b", true)
        fixture.drain()
        fixture.replies.clear()

        fixture.responder.connected()
        fixture.drain()

        assertEquals(listOf("per_a", "per_b"), fixture.replies.map(Reply::requestID))
    }

    private class Fixture(
        val responder: OpenCodePermissionAutoResponder,
        val tasks: ArrayDeque<Runnable>,
        val replies: MutableList<Reply>,
    ) {
        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private fun fixture(
        pending: List<OpenCodeServerProtocol.PendingRequestSummary> = emptyList(),
    ): Fixture {
        val tasks = ArrayDeque<Runnable>()
        val replies = mutableListOf<Reply>()
        val responder = OpenCodePermissionAutoResponder(
            projectDirectory = { "/tmp/project" },
            serverUrl = { "http://127.0.0.1:4096" },
            serverPassword = { "password" },
            executeAsync = tasks::addLast,
            loadPending = { _, _, _ -> OpenCodeProtocolResult.Success(pending) },
            reply = { _, _, directory, sessionID, requestID, response ->
                replies += Reply(directory, sessionID, requestID, response)
                true
            },
        )
        return Fixture(responder, tasks, replies)
    }

    private fun permissionEvent(directory: String, sessionID: String, requestID: String): OpenCodeGlobalEvent {
        return OpenCodeGlobalEvent(
            directory,
            "permission.asked",
            "evt_1",
            JsonParser.parseString("""{"id":"$requestID","sessionID":"$sessionID"}""").asJsonObject,
        )
    }
}
