package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Test

class SbxSetupChecklistTest {
    @Test
    fun projectChecklistShowsWhatIsStillPending() {
        assertEquals(
            "○ Sandbox: created when OpenCode starts\n○ OpenCode: not running",
            SbxSetupChecklist.format(
                SbxSetupChecklist.projectSteps(owned = false, running = false),
                openMark = SbxSetupChecklist.PENDING,
            ),
        )
        assertEquals(
            "✓ Sandbox: created\n✓ OpenCode: running",
            SbxSetupChecklist.format(SbxSetupChecklist.projectSteps(owned = true, running = true)),
        )
    }

    @Test
    fun failedChecksAreCrossedOut() {
        assertEquals(
            "✓ Server: up\n✗ Network: blocked",
            SbxSetupChecklist.format(
                listOf(
                    SbxSetupStep("Server", true, "up"),
                    SbxSetupStep("Network", false, "blocked"),
                ),
            ),
        )
    }
}
