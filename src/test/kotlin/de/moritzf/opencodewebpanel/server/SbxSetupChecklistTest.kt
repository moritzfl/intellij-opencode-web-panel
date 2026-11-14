package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SbxSetupChecklistTest {
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
