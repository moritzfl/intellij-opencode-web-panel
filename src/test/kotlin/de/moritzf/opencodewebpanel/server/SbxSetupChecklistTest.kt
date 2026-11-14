package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SbxSetupChecklistTest {
    @Test
    fun appChecklistMarksMissingPolicy() {
        val text =
            SbxSetupChecklist.format(
                SbxSetupChecklist.appSteps(sbxFound = true, policyReady = false),
            )
        assertTrue(text.contains("✓ Detect sbx"))
        assertTrue(text.contains("[ ] Network policy"))
        assertTrue(text.contains("Set up default network policy below"))
        assertFalse(
            "An adopted policy was never initialized by the plugin",
            SbxSetupChecklist.format(SbxSetupChecklist.appSteps(true, policyReady = true))
                .contains("succeeded"),
        )
    }

    @Test
    fun projectChecklistRequiresOwnedRunningSandbox() {
        val incomplete =
            SbxSetupChecklist.format(
                SbxSetupChecklist.projectSteps(useSandbox = true, owned = false, running = false),
            )
        assertTrue(incomplete.contains("✓ Sandbox runtime"))
        assertTrue(incomplete.contains("[ ] Owned VM"))
        assertFalse(incomplete.contains("✓ OpenCode serve"))
        val ready =
            SbxSetupChecklist.format(
                SbxSetupChecklist.projectSteps(useSandbox = true, owned = true, running = true),
            )
        assertTrue(ready.contains("✓ Owned VM"))
        assertTrue(ready.contains("✓ OpenCode serve"))
    }
}
