package de.moritzf.opencodewebpanel.toolWindow

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeNotificationEventProcessorTest {

    private val sessions = mutableMapOf<String, OpenCodeServerProtocol.SessionInfo>()
    private var now = 1_000_000L
    private val fetchedSessions = mutableListOf<String>()
    private val processor = OpenCodeNotificationEventProcessor(
        fetchSession = { _, sessionID ->
            fetchedSessions.add(sessionID)
            sessions[sessionID]
        },
        nowMillis = { now },
    )

    private fun event(type: String, propertiesJson: String = "{}", recordId: String = ""): OpenCodeGlobalEvent {
        val properties = JsonParser.parseString(propertiesJson).asJsonObject as JsonObject
        return OpenCodeGlobalEvent("/tmp/project", type, recordId, properties)
    }

    private fun notify(outcome: OpenCodeNotificationEventProcessor.Outcome?): OpenCodeServerProtocol.SystemNotificationPayload {
        return (outcome as OpenCodeNotificationEventProcessor.Outcome.Notify).payload
    }

    @Test
    fun answeredRequestsDismissByRequestKey() {
        for (type in listOf("permission.replied", "question.replied", "question.rejected")) {
            val outcome = processor.process(event(type, """{"requestID":"per_1"}"""))
            assertEquals(
                OpenCodeNotificationEventProcessor.Outcome.Dismiss("request:per_1"),
                outcome,
            )
        }
        assertNull(processor.process(event("permission.replied", "{}")))
        assertNull(processor.process(event("permission.replied", """{"requestID":"per/../evil"}""")))
    }

    @Test
    fun busySessionsDismissBySessionKey() {
        val outcome = processor.process(
            event("session.status", """{"sessionID":"ses_1","status":{"type":"busy"}}"""),
        )
        assertEquals(OpenCodeNotificationEventProcessor.Outcome.Dismiss("session:ses_1"), outcome)

        val retry = processor.process(
            event("session.status", """{"sessionID":"ses_1","status":{"type":"retry"}}"""),
        )
        assertEquals(OpenCodeNotificationEventProcessor.Outcome.Dismiss("session:ses_1"), retry)
    }

    @Test
    fun idleSessionNotifiesWithSessionTitle() {
        sessions["ses_1"] = OpenCodeServerProtocol.SessionInfo("Fix the build", parentID = null)

        val payload = notify(
            processor.process(
                event("session.status", """{"sessionID":"ses_1","status":{"type":"idle"}}""", recordId = "evt_1"),
            ),
        )

        assertEquals("Response ready", payload.title)
        assertEquals("Fix the build", payload.body)
        assertEquals("session", payload.kind)
        assertEquals("ses_1", payload.sessionID)
        assertEquals("evt_1", payload.id)
        assertEquals(OpenCodeServerProtocol.buildSessionRoute("/tmp/project", "ses_1"), payload.route)
    }

    @Test
    fun pairedIdleEventsMergeWithinWindowButNotBeyond() {
        sessions["ses_1"] = OpenCodeServerProtocol.SessionInfo("Fix the build", parentID = null)

        assertTrue(processor.process(event("session.idle", """{"sessionID":"ses_1"}""")) is OpenCodeNotificationEventProcessor.Outcome.Notify)
        // The paired session.status idle event arrives moments later and must merge.
        now += 100
        assertNull(processor.process(event("session.status", """{"sessionID":"ses_1","status":{"type":"idle"}}""")))
        // A genuine re-idle after the merge window must notify again.
        now += 5_000
        assertTrue(processor.process(event("session.idle", """{"sessionID":"ses_1"}""")) is OpenCodeNotificationEventProcessor.Outcome.Notify)
    }

    @Test
    fun idleNotificationsSkipUnknownAndChildSessions() {
        assertNull(processor.process(event("session.idle", """{"sessionID":"ses_unknown"}""")))

        now += 10_000
        sessions["ses_child"] = OpenCodeServerProtocol.SessionInfo("Subtask", parentID = "ses_parent")
        assertNull(processor.process(event("session.idle", """{"sessionID":"ses_child"}""")))
    }

    @Test
    fun sessionErrorFallsBackToErrorTextAndSkipsChildSessions() {
        val payload = notify(
            processor.process(event("session.error", """{"sessionID":"ses_x","error":"boom"}""")),
        )
        assertEquals("Session error", payload.title)
        assertEquals("boom", payload.body)

        val defaultBody = notify(processor.process(event("session.error", "{}")))
        assertEquals("An error occurred", defaultBody.body)
        assertEquals(OpenCodeServerProtocol.buildSessionRoute("/tmp/project", null), defaultBody.route)

        sessions["ses_child"] = OpenCodeServerProtocol.SessionInfo("Subtask", parentID = "ses_parent")
        assertNull(processor.process(event("session.error", """{"sessionID":"ses_child"}""")))
    }

    @Test
    fun permissionAndQuestionRequestsCarryRequestId() {
        sessions["ses_1"] = OpenCodeServerProtocol.SessionInfo("Fix the build", parentID = null)

        val permission = notify(
            processor.process(event("permission.asked", """{"id":"per_1","sessionID":"ses_1"}""")),
        )
        assertEquals("Permission required", permission.title)
        assertEquals("Fix the build in project needs permission", permission.body)
        assertEquals("permission", permission.kind)
        assertEquals("per_1", permission.requestID)
        assertTrue(OpenCodeServerProtocol.isPermissionNotification(permission))

        val question = notify(
            processor.process(event("question.asked", """{"id":"que_1","sessionID":"ses_unknown"}""")),
        )
        assertEquals("Question", question.title)
        assertEquals("New session in project has a question", question.body)
        assertEquals("question", question.kind)
    }

    @Test
    fun irrelevantEventsAreIgnoredWithoutSessionLookups() {
        assertNull(processor.process(event("message.part.updated", """{"sessionID":"ses_1"}""")))
        assertNull(processor.process(event("session.status", """{"sessionID":"ses_1"}""")))
        assertTrue(fetchedSessions.isEmpty())
    }

    @Test
    fun fallbackIdIsStablePerEventContent() {
        sessions["ses_1"] = OpenCodeServerProtocol.SessionInfo("Fix the build", parentID = null)
        val first = notify(processor.process(event("session.idle", """{"sessionID":"ses_1"}""")))
        now += 10_000
        val second = notify(processor.process(event("session.idle", """{"sessionID":"ses_1"}""")))
        assertEquals(first.id, second.id)
        assertTrue(first.id.contains("session.idle"))
    }
}
