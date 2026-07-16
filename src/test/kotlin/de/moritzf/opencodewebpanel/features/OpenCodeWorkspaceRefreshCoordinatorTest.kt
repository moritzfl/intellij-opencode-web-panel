package de.moritzf.opencodewebpanel.features

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.moritzf.opencodewebpanel.server.OpenCodeGlobalEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeWorkspaceRefreshCoordinatorTest {

    private fun event(type: String, propertiesJson: String = "{}"): OpenCodeGlobalEvent {
        val properties = JsonParser.parseString(propertiesJson).asJsonObject ?: JsonObject()
        return OpenCodeGlobalEvent(directory = "/tmp/project", type = type, recordId = "evt_1", properties = properties)
    }

    @Test
    fun workingTreeAndBranchEventsTriggerRefresh() {
        assertTrue(OpenCodeWorkspaceRefreshCoordinator.triggersRefresh(event("file.edited", """{"file":"a.kt"}""")))
        assertTrue(
            OpenCodeWorkspaceRefreshCoordinator.triggersRefresh(
                event("file.watcher.updated", """{"file":"a.kt","event":"change"}"""),
            ),
        )
        assertTrue(OpenCodeWorkspaceRefreshCoordinator.triggersRefresh(event("vcs.branch.updated", """{"branch":"main"}""")))
    }

    @Test
    fun sessionIdleTriggersRefreshSoCommitsAreCaught() {
        // A plain same-branch commit emits no working-tree event; the agent's session returning to
        // idle after its `git commit` shell call is the reliable signal.
        assertTrue(OpenCodeWorkspaceRefreshCoordinator.triggersRefresh(event("session.idle", """{"sessionID":"ses_1"}""")))
        assertTrue(
            OpenCodeWorkspaceRefreshCoordinator.triggersRefresh(
                event("session.status", """{"sessionID":"ses_1","status":{"type":"idle"}}"""),
            ),
        )
    }

    @Test
    fun busyAndUnrelatedEventsDoNotTriggerRefresh() {
        assertFalse(
            OpenCodeWorkspaceRefreshCoordinator.triggersRefresh(
                event("session.status", """{"sessionID":"ses_1","status":{"type":"busy"}}"""),
            ),
        )
        assertFalse(
            OpenCodeWorkspaceRefreshCoordinator.triggersRefresh(
                event("session.status", """{"sessionID":"ses_1","status":{"type":"retry"}}"""),
            ),
        )
        // Malformed session.status without a status object must not trigger.
        assertFalse(OpenCodeWorkspaceRefreshCoordinator.triggersRefresh(event("session.status", """{"sessionID":"ses_1"}""")))
        assertFalse(OpenCodeWorkspaceRefreshCoordinator.triggersRefresh(event("message.updated", """{"sessionID":"ses_1"}""")))
        assertFalse(OpenCodeWorkspaceRefreshCoordinator.triggersRefresh(event("permission.asked", """{"id":"per_1"}""")))
    }
}
