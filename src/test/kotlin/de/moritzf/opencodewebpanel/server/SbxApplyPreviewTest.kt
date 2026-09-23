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
        val preview = SbxApplyPreview.build("/tmp/p", base.copy(useSandbox = false), base, false, false, "")
        assertEquals(SbxApplyEffect.RESTART, preview.effect)
        assertEquals("Docker Sandbox (sbx)", preview.runtimeLabel)
    }

    @Test
    fun hostModeIgnoresSandboxOnlyOptions() {
        val host = base.copy(useSandbox = false)
        val preview = SbxApplyPreview.build(
            "/tmp/p", host,
            host.copy(kits = emptyList(), shareHostOpencodeConfig = true, extraMounts = listOf(SbxExtraMount("/x", "/x"))),
            false, false, "",
        )
        assertEquals(SbxApplyEffect.NONE, preview.effect)
        assertTrue(preview.changes.isEmpty())
    }

    @Test
    fun createTimeChangesOnlyRecreateAnExistingVm() {
        val changed = base.copy(kits = emptyList(), shareHostOpencodeConfig = true)
        val withoutVm = SbxApplyPreview.build("/tmp/p", base.copy(kits = listOf("./a")), changed, false, false, "", hasVm = false)
        assertEquals(SbxApplyEffect.NONE, withoutVm.effect)
        assertTrue(withoutVm.message().contains("applies when the sandbox is created"))
        val withVm = SbxApplyPreview.build("/tmp/p", base.copy(kits = listOf("./a")), changed, false, false, "", hasVm = true)
        assertEquals(SbxApplyEffect.RECREATE, withVm.effect)
    }

    @Test
    fun mountRemovalReadOnlyAndAliasChangesAreReported() {
        val mount = SbxExtraMount("/data", "/home/agent/data")
        val old = base.copy(extraMounts = listOf(mount))
        assertEquals(SbxApplyEffect.RECREATE, SbxApplyPreview.build("/tmp/p", old, base, false, false, "").effect)
        assertTrue(SbxApplyPreview.build("/tmp/p", old, base, false, false, "").message().contains("Extra mount removed"))
        assertEquals(
            SbxApplyEffect.RECREATE,
            SbxApplyPreview.build("/tmp/p", old, base.copy(extraMounts = listOf(mount.copy(readOnly = true))), false, false, "").effect,
        )
        assertEquals(
            SbxApplyEffect.RESTART,
            SbxApplyPreview.build("/tmp/p", old, base.copy(extraMounts = listOf(mount.copy(sandboxPath = "/home/agent/d"))), false, false, "").effect,
        )
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
