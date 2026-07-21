package de.moritzf.opencodewebpanel.features

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEvent
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeSoundServiceTest {

    private val played = mutableListOf<String?>()
    private val sessions = mutableMapOf<String, OpenCodeServerProtocol.SessionInfo>()
    private val defaults = OpenCodeSoundSettings()

    init {
        OpenCodeSoundService.resetForTests()
        played.clear()
        sessions.clear()
    }

    private fun event(type: String, propertiesJson: String = "{}"): OpenCodeGlobalEvent {
        val properties = JsonParser.parseString(propertiesJson).asJsonObject as JsonObject
        return OpenCodeGlobalEvent("/tmp/project", type, "", properties)
    }

    private fun handle(event: OpenCodeGlobalEvent, settings: OpenCodeSoundSettings = defaults) {
        OpenCodeSoundService.handleEvent(
            event = event,
            settings = settings,
            fetchSession = { _, sessionID -> sessions[sessionID] },
            play = { played.add(it) },
        )
    }

    @Test
    fun idleStatusPlaysAgentSoundOnceUntilSessionBecomesBusyAgain() {
        sessions["ses_1"] = OpenCodeServerProtocol.SessionInfo("Done", parentID = null)
        handle(event("session.status", """{"sessionID":"ses_1","status":{"type":"idle"}}"""))
        handle(event("session.idle", """{"sessionID":"ses_1"}"""))
        handle(event("session.idle", """{"sessionID":"ses_1"}"""))
        assertEquals(listOf(OpenCodeSoundSettings.DEFAULT_AGENT), played)

        handle(event("session.status", """{"sessionID":"ses_1","status":{"type":"busy"}}"""))
        handle(event("session.idle", """{"sessionID":"ses_1"}"""))
        assertEquals(
            listOf(OpenCodeSoundSettings.DEFAULT_AGENT, OpenCodeSoundSettings.DEFAULT_AGENT),
            played,
        )
    }

    @Test
    fun skipsChildSessionsAndUnresolvedSessionsForAgent() {
        sessions["ses_child"] = OpenCodeServerProtocol.SessionInfo("sub", parentID = "ses_parent")
        handle(event("session.idle", """{"sessionID":"ses_child"}"""))
        handle(event("session.idle", """{"sessionID":"ses_missing"}"""))
        assertTrue(played.isEmpty())
    }

    @Test
    fun respectsAgentDisabled() {
        sessions["ses_1"] = OpenCodeServerProtocol.SessionInfo("Done", parentID = null)
        handle(
            event("session.idle", """{"sessionID":"ses_1"}"""),
            settings = defaults.copy(agentEnabled = false),
        )
        assertTrue(played.isEmpty())
    }

    @Test
    fun permissionAskedPlaysPermissionsSound() {
        handle(event("permission.asked", """{"id":"per_1","sessionID":"ses_1"}"""))
        assertEquals(listOf(OpenCodeSoundSettings.DEFAULT_PERMISSIONS), played)
    }

    @Test
    fun sessionErrorPlaysErrorsSoundAndSkipsChildren() {
        sessions["ses_1"] = OpenCodeServerProtocol.SessionInfo("Main", parentID = null)
        sessions["ses_child"] = OpenCodeServerProtocol.SessionInfo("sub", parentID = "ses_1")
        handle(event("session.error", """{"sessionID":"ses_child"}"""))
        assertTrue(played.isEmpty())
        handle(event("session.error", """{"sessionID":"ses_1"}"""))
        assertEquals(listOf(OpenCodeSoundSettings.DEFAULT_ERRORS), played)
    }

    @Test
    fun usesCustomSoundIdsFromSettings() {
        sessions["ses_1"] = OpenCodeServerProtocol.SessionInfo("Done", parentID = null)
        val custom = defaults.copy(agent = "alert-05", permissions = "yup-01", errors = "nope-12")
        handle(event("session.idle", """{"sessionID":"ses_1"}"""), settings = custom)
        handle(event("permission.asked", """{"id":"per_1"}"""), settings = custom)
        handle(event("session.error", """{"sessionID":"ses_1"}"""), settings = custom)
        assertEquals(listOf("alert-05", "yup-01", "nope-12"), played)
    }

    @Test
    fun ignoresBusyStatus() {
        handle(event("session.status", """{"sessionID":"ses_1","status":{"type":"busy"}}"""))
        assertTrue(played.isEmpty())
    }
}
