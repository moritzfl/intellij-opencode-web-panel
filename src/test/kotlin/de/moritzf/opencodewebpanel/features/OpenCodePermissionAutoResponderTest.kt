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
        fixture.responder.setSessionEnabled("ses_a", true)
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

        fixture.responder.setSessionEnabled("ses_a", true)
        fixture.drain()

        assertEquals(listOf("per_a"), fixture.replies.map(Reply::requestID))
    }

    @Test
    fun sessionTogglesRemainIndependent() {
        val fixture = fixture()
        fixture.responder.setSessionEnabled("ses_a", true)
        fixture.responder.setSessionEnabled("ses_b", true)
        fixture.drain()
        fixture.responder.setSessionEnabled("ses_a", false)

        assertFalse(fixture.responder.isSessionEnabled("ses_a"))
        assertTrue(fixture.responder.isSessionEnabled("ses_b"))

        fixture.responder.eventReceived(permissionEvent("/tmp/project", "ses_a", "per_a"))
        fixture.responder.eventReceived(permissionEvent("/tmp/project", "ses_b", "per_b"))
        fixture.drain()

        assertEquals(listOf("per_b"), fixture.replies.map(Reply::requestID))
    }

    @Test
    fun disablingSessionCancelsQueuedReply() {
        val fixture = fixture()
        fixture.responder.setSessionEnabled("ses_a", true)
        fixture.drain()
        fixture.responder.eventReceived(permissionEvent("/tmp/project", "ses_a", "per_a"))
        fixture.responder.setSessionEnabled("ses_a", false)

        fixture.drain()

        assertTrue(fixture.replies.isEmpty())
    }

    @Test
    fun ignoresOtherDirectoriesAndMalformedRequests() {
        val fixture = fixture()
        fixture.responder.setSessionEnabled("ses_a", true)
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
        fixture.responder.setSessionEnabled("ses_a", true)
        fixture.responder.setSessionEnabled("ses_b", true)
        fixture.drain()
        fixture.replies.clear()

        fixture.responder.connected()
        fixture.drain()

        assertEquals(listOf("per_a", "per_b"), fixture.replies.map(Reply::requestID))
    }

    @Test
    fun parentEnableCoversChildSessionPermissions() {
        val fixture = fixture(
            parents = mapOf("ses_child" to "ses_parent"),
        )
        fixture.responder.setSessionEnabled("ses_parent", true)
        fixture.drain()

        fixture.responder.eventReceived(permissionEvent("/tmp/project", "ses_child", "per_child"))
        fixture.drain()

        assertEquals(listOf("per_child"), fixture.replies.map(Reply::requestID))
        assertTrue(fixture.responder.isEffectivelyEnabled("ses_child"))
        assertFalse(fixture.responder.isSessionEnabled("ses_child"))
    }

    @Test
    fun preparingChildReflectsEnabledParentBeforePermissionArrives() {
        val fixture = fixture(
            parents = mapOf("ses_child" to "ses_parent"),
        )
        fixture.responder.setSessionEnabled("ses_parent", true)
        fixture.drain()

        fixture.responder.prepareSession("ses_child")
        fixture.drain()

        assertTrue(fixture.responder.isEffectivelyEnabled("ses_child"))
    }

    @Test
    fun partialLineageStillReflectsKnownEnabledParent() {
        val fixture = fixture(
            parents = mapOf(
                "ses_child" to "ses_parent",
                "ses_parent" to "ses_grandparent",
            ),
            failedSessions = setOf("ses_parent"),
        )
        fixture.responder.setSessionEnabled("ses_parent", true)
        fixture.drain()

        fixture.responder.prepareSession("ses_child")
        fixture.drain()

        assertTrue(fixture.responder.isEffectivelyEnabled("ses_child"))
        assertFalse(fixture.responder.isLineagePrepared("ses_child"))
    }

    @Test
    fun disablingInheritedChildToggleExcludesOnlyThatChild() {
        val fixture = fixture(
            parents = mapOf(
                "ses_child" to "ses_parent",
                "ses_sibling" to "ses_parent",
            ),
        )
        fixture.responder.setSessionEnabled("ses_parent", true)
        fixture.drain()
        fixture.responder.prepareSession("ses_child")
        fixture.responder.prepareSession("ses_sibling")
        fixture.drain()

        fixture.responder.setEffectivelyEnabled("ses_child", false)

        assertTrue(fixture.responder.isSessionEnabled("ses_parent"))
        assertFalse(fixture.responder.isEffectivelyEnabled("ses_child"))
        assertTrue(fixture.responder.isEffectivelyEnabled("ses_sibling"))
    }

    @Test
    fun childDisableOverridesOverlappingParentAndChildEnables() {
        val fixture = fixture(
            parents = mapOf("ses_child" to "ses_parent"),
        )
        fixture.responder.setSessionEnabled("ses_parent", true)
        fixture.responder.setSessionEnabled("ses_child", true)
        fixture.drain()
        fixture.responder.prepareSession("ses_child")
        fixture.drain()

        fixture.responder.setEffectivelyEnabled("ses_child", false)

        assertTrue(fixture.responder.isSessionEnabled("ses_parent"))
        assertFalse(fixture.responder.isSessionEnabled("ses_child"))
        assertFalse(fixture.responder.isEffectivelyEnabled("ses_child"))
    }

    @Test
    fun enablingParentAnswersPendingChildRequests() {
        val fixture = fixture(
            pending = listOf(
                OpenCodeServerProtocol.PendingRequestSummary("per_child", "ses_child"),
                OpenCodeServerProtocol.PendingRequestSummary("per_other", "ses_other"),
            ),
            parents = mapOf("ses_child" to "ses_parent"),
        )

        fixture.responder.setSessionEnabled("ses_parent", true)
        fixture.drain()

        assertEquals(listOf("per_child"), fixture.replies.map(Reply::requestID))
    }

    @Test
    fun enabledParentDoesNotCoverAnotherParentsChild() {
        val fixture = fixture(
            parents = mapOf(
                "ses_child_a" to "ses_parent_a",
                "ses_child_b" to "ses_parent_b",
            ),
        )
        fixture.responder.setSessionEnabled("ses_parent_a", true)
        fixture.drain()

        fixture.responder.eventReceived(permissionEvent("/tmp/project", "ses_child_b", "per_child_b"))
        fixture.drain()

        assertTrue(fixture.replies.isEmpty())
    }

    @Test
    fun disablingParentStopsChildReplies() {
        val fixture = fixture(
            parents = mapOf("ses_child" to "ses_parent"),
        )
        fixture.responder.setSessionEnabled("ses_parent", true)
        fixture.drain()
        fixture.responder.setSessionEnabled("ses_parent", false)

        fixture.responder.eventReceived(permissionEvent("/tmp/project", "ses_child", "per_child"))
        fixture.drain()

        assertTrue(fixture.replies.isEmpty())
    }

    @Test
    fun retriesTransientPendingListFailure() {
        val fixture = fixture(
            pending = listOf(OpenCodeServerProtocol.PendingRequestSummary("per_a", "ses_a")),
            pendingFailures = 1,
        )

        fixture.responder.setSessionEnabled("ses_a", true)
        fixture.drain()

        assertEquals(listOf("per_a"), fixture.replies.map(Reply::requestID))
    }

    @Test
    fun retriesTransientLineageFailure() {
        val fixture = fixture(
            parents = mapOf("ses_child" to "ses_parent"),
            sessionFailures = 1,
        )
        fixture.responder.setSessionEnabled("ses_parent", true)
        fixture.drain()

        fixture.responder.eventReceived(permissionEvent("/tmp/project", "ses_child", "per_child"))
        fixture.drain()

        assertEquals(listOf("per_child"), fixture.replies.map(Reply::requestID))
    }

    @Test
    fun enablingParentRetriesPendingChildLineageFailure() {
        val fixture = fixture(
            pending = listOf(OpenCodeServerProtocol.PendingRequestSummary("per_child", "ses_child")),
            parents = mapOf("ses_child" to "ses_parent"),
            sessionFailures = 1,
        )

        fixture.responder.setSessionEnabled("ses_parent", true)
        fixture.drain()

        assertEquals(listOf("per_child"), fixture.replies.map(Reply::requestID))
    }

    @Test
    fun retriesTransientReplyFailure() {
        val fixture = fixture(replyFailures = 1)
        fixture.responder.setSessionEnabled("ses_a", true)
        fixture.drain()

        fixture.responder.eventReceived(permissionEvent("/tmp/project", "ses_a", "per_a"))
        fixture.drain()

        assertEquals(listOf("per_a", "per_a"), fixture.replies.map(Reply::requestID))
    }

    @Test
    fun disposeCancelsQueuedReply() {
        val fixture = fixture()
        fixture.responder.setSessionEnabled("ses_a", true)
        fixture.drain()
        fixture.responder.eventReceived(permissionEvent("/tmp/project", "ses_a", "per_a"))

        fixture.responder.dispose()
        fixture.drain()

        assertTrue(fixture.replies.isEmpty())
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
        parents: Map<String, String?> = emptyMap(),
        pendingFailures: Int = 0,
        sessionFailures: Int = 0,
        replyFailures: Int = 0,
        failedSessions: Set<String> = emptySet(),
    ): Fixture {
        val tasks = ArrayDeque<Runnable>()
        val replies = mutableListOf<Reply>()
        var remainingPendingFailures = pendingFailures
        var remainingSessionFailures = sessionFailures
        var remainingReplyFailures = replyFailures
        val responder = OpenCodePermissionAutoResponder(
            projectDirectory = { "/tmp/project" },
            serverUrl = { "http://127.0.0.1:4096" },
            serverPassword = { "password" },
            executeAsync = tasks::addLast,
            scheduleAsync = { _, task -> tasks.addLast(task) },
            loadPending = { _, _, _ ->
                if (remainingPendingFailures-- > 0) {
                    OpenCodeProtocolResult.Failure(OpenCodeProtocolResult.Failure.Kind.IO)
                } else {
                    OpenCodeProtocolResult.Success(pending)
                }
            },
            loadSession = { _, _, _, sessionID ->
                if (sessionID in failedSessions || remainingSessionFailures-- > 0) {
                    null
                } else if (!parents.containsKey(sessionID) && sessionID !in parents.values.filterNotNull()) {
                    // Unknown session: still resolve as a root so walk terminates.
                    OpenCodeServerProtocol.SessionInfo(title = sessionID, parentID = null, id = sessionID)
                } else {
                    OpenCodeServerProtocol.SessionInfo(
                        title = sessionID,
                        parentID = parents[sessionID],
                        id = sessionID,
                    )
                }
            },
            reply = { _, _, directory, sessionID, requestID, response ->
                replies += Reply(directory, sessionID, requestID, response)
                remainingReplyFailures-- <= 0
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
