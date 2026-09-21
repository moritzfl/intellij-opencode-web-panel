package de.moritzf.opencodewebpanel.server

import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SbxApplyPreviewTest {
    private val base = SbxLaunchSpec.fromSettings(OpenCodeSettingsState(), "/tmp/p").copy(useSandbox = true)

    @Test
    fun kitAppendIsLiveAndRemovalRecreates() {
        val appended = SbxApplyPreview.build("/tmp/p", base.copy(kits = listOf("./a")), base.copy(kits = listOf("./a", "./b")), false, false, "")
        assertEquals(SbxApplyEffect.LIVE, appended.effect)
        assertTrue(appended.message().contains("Append sandbox kit"))
        val removed = SbxApplyPreview.build("/tmp/p", base.copy(kits = listOf("./a", "./b")), base.copy(kits = listOf("./a")), false, false, "")
        assertEquals(SbxApplyEffect.RECREATE, removed.effect)
        assertTrue(removed.message().contains("Cancel leaves settings"))
    }

    @Test
    fun sandboxPortChangeIsLiveHostPortChangeRestarts() {
        val sandbox = SbxApplyPreview.build("/tmp/p", base, base.copy(hostPort = 4096), false, true, "")
        assertEquals(SbxApplyEffect.LIVE, sandbox.effect)
        val host = SbxApplyPreview.build("/tmp/p", base.copy(useSandbox = false), base.copy(useSandbox = false), false, true, "")
        assertEquals(SbxApplyEffect.RESTART, host.effect)
    }

    @Test
    fun memoryChangeIsDeferredUntilReset() {
        val preview = SbxApplyPreview.build("/tmp/p", base, base.copy(memory = "8g"), false, false, "sessions kept")
        assertEquals(SbxApplyEffect.NONE, preview.effect)
        assertTrue(preview.message().contains("applies at Reset"))
        assertTrue(preview.message().contains("sessions kept"))
        assertEquals("Apply OpenCode settings", preview.confirmTitle())
    }

    @Test
    fun directoryChangeAlwaysRestarts() {
        val preview = SbxApplyPreview.build("/tmp/b", base, base, true, false, "")
        assertEquals(SbxApplyEffect.RESTART, preview.effect)
        assertTrue(preview.message().contains("OpenCode directory"))
    }

    @Test
    fun enablingSandboxFromMissingSpecRestarts() {
        val preview = SbxApplyPreview.build("/tmp/p", null, base, false, false, "")
        assertEquals(SbxApplyEffect.RESTART, preview.effect)
        assertEquals("Docker Sandbox (sbx)", preview.runtimeLabel)
    }

    @Test
    fun changingOpenCodeVersionRestartsInBothDirections() {
        val on = SbxApplyPreview.build("/tmp/p", base, base.copy(openCodeVersion = SbxOpenCodeVersion.V2), false, false, "")
        assertEquals(SbxApplyEffect.RESTART, on.effect)
        assertTrue(on.message().contains("OpenCode version 2.x"))
        val off = SbxApplyPreview.build(
            "/tmp/p",
            base.copy(openCodeVersion = SbxOpenCodeVersion.V2),
            base,
            false,
            false,
            "",
        )
        assertEquals(SbxApplyEffect.RESTART, off.effect)
        assertTrue(off.message().contains("OpenCode version 1.x"))
    }

    @Test
    fun emptyPreviewHasNoProcessChanges() {
        val preview = SbxApplyPreview.build("/tmp/p", base, base, false, false, "")
        assertEquals(SbxApplyEffect.NONE, preview.effect)
        assertTrue(preview.message().contains("No process changes"))
        assertFalse(preview.message().contains("Cancel leaves"))
    }
}
