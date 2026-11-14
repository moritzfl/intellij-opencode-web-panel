package de.moritzf.opencodewebpanel.server

import de.moritzf.opencodewebpanel.configuration.OpenCodeSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SbxApplyPreviewTest {
    private val base =
        SbxLaunchSpec.defaults("/tmp/p", hostPort = OpenCodeSettingsState().hostPortOrNull())
            .copy(useSandbox = true)

    @Test
    fun kitAppendIsLiveAndRemovalRecreates() {
        val appended =
            SbxApplyPreview.build(
                "/tmp/p",
                base.copy(kits = listOf("./a")),
                base.copy(kits = listOf("./a", "./b")),
                false,
                false,
                "",
            )
        assertEquals(SbxApplyEffect.LIVE, appended.effect)
        assertFalse(appended.reloadPage)
        assertTrue(appended.message().contains("Append sandbox kit"))
        val removed =
            SbxApplyPreview.build(
                "/tmp/p",
                base.copy(kits = listOf("./a", "./b")),
                base.copy(kits = listOf("./a")),
                false,
                false,
                "",
            )
        assertEquals(SbxApplyEffect.RECREATE, removed.effect)
        assertTrue(removed.message().contains("Cancel leaves settings"))
    }

    @Test
    fun sandboxPortChangeIsLiveHostPortChangeRestarts() {
        val sandbox =
            SbxApplyPreview.build("/tmp/p", base, base.copy(hostPort = 4096), false, true, "")
        assertEquals(SbxApplyEffect.LIVE, sandbox.effect)
        assertTrue(
            sandbox.copy(changes = sandbox.changes.map { it.copy(summary = "Port") }).reloadPage,
        )
        val host =
            SbxApplyPreview.build(
                "/tmp/p",
                base.copy(useSandbox = false),
                base.copy(useSandbox = false),
                false,
                true,
                "",
            )
        assertEquals(SbxApplyEffect.RESTART, host.effect)
    }

    @Test
    fun memoryChangeIsDeferredUntilReset() {
        val preview =
            SbxApplyPreview.build(
                "/tmp/p",
                base,
                base.copy(memory = "8g"),
                false,
                false,
                "sessions kept",
            )
        assertEquals(SbxApplyEffect.NONE, preview.effect)
        assertTrue(preview.message().contains("• Memory 4g → 8g (applies at Reset)\n"))
        assertTrue(preview.message().contains("sessions kept"))
    }

    @Test
    fun eachChangeSaysWhatApplyDoesInPlainWords() {
        val message =
            SbxApplyPreview.build(
                    "/tmp/p",
                    base.copy(kits = listOf("./a")),
                    base.copy(hostPort = 4097, workingDirectory = "./app", kits = emptyList()),
                    false,
                    true,
                    "",
                )
                .message()
        assertTrue(message, message.contains("• Server port — applies now"))
        assertTrue(message, message.contains("• OpenCode working directory — restarts OpenCode"))
        assertTrue(message, message.contains("• Kits removed or reordered — recreates the sandbox"))
        assertFalse(message, message.contains("(settings)"))
    }

    @Test
    fun directoryChangeAlwaysRestarts() {
        val preview = SbxApplyPreview.build("/tmp/b", base, base, true, false, "")
        assertEquals(SbxApplyEffect.RESTART, preview.effect)
        assertTrue(preview.message().contains("OpenCode directory"))
    }

    @Test
    fun changingTheSandboxWorkingDirectoryRestartsWithoutRecreatingTheVm() {
        val preview =
            SbxApplyPreview.build(
                "/tmp/p",
                base,
                base.copy(workingDirectory = "./app"),
                false,
                false,
                "sessions kept",
            )
        assertEquals(SbxApplyEffect.RESTART, preview.effect)
        assertTrue(preview.message().contains("OpenCode working directory"))
        assertFalse(preview.message().contains("recreates the sandbox"))
    }

    @Test
    fun enablingSandboxFromMissingSpecRestarts() {
        val preview =
            SbxApplyPreview.build("/tmp/p", base.copy(useSandbox = false), base, false, false, "")
        assertEquals(SbxApplyEffect.RESTART, preview.effect)
        assertEquals("Docker Sandbox (sbx)", preview.runtimeLabel)
    }

    @Test
    fun hostModeIgnoresSandboxOnlyOptions() {
        val host = base.copy(useSandbox = false)
        val preview =
            SbxApplyPreview.build(
                "/tmp/p",
                host,
                host.copy(
                    kits = emptyList(),
                    shareHostOpencodeConfig = true,
                    extraMounts = listOf(SbxExtraMount("/x", "/x")),
                ),
                false,
                false,
                "",
            )
        assertEquals(SbxApplyEffect.NONE, preview.effect)
        assertTrue(preview.changes.isEmpty())
    }

    @Test
    fun createTimeChangesOnlyRecreateAnExistingVm() {
        val changed = base.copy(kits = emptyList(), shareHostOpencodeConfig = true)
        val withoutVm =
            SbxApplyPreview.build(
                "/tmp/p",
                base.copy(kits = listOf("./a")),
                changed,
                false,
                false,
                "",
                hasVm = false,
            )
        assertEquals(SbxApplyEffect.NONE, withoutVm.effect)
        assertTrue(withoutVm.message().contains("applies when the sandbox is created"))
        val withVm =
            SbxApplyPreview.build(
                "/tmp/p",
                base.copy(kits = listOf("./a")),
                changed,
                false,
                false,
                "",
                hasVm = true,
            )
        assertEquals(SbxApplyEffect.RECREATE, withVm.effect)
    }

    @Test
    fun mountRemovalReadOnlyAndAliasChangesAreReported() {
        val mount = SbxExtraMount("/data", "/home/agent/data")
        val old = base.copy(extraMounts = listOf(mount))
        assertEquals(
            SbxApplyEffect.RECREATE,
            SbxApplyPreview.build("/tmp/p", old, base, false, false, "").effect,
        )
        assertTrue(
            SbxApplyPreview.build("/tmp/p", old, base, false, false, "")
                .message()
                .contains("Extra mount removed"),
        )
        assertEquals(
            SbxApplyEffect.RECREATE,
            SbxApplyPreview.build(
                    "/tmp/p",
                    old,
                    base.copy(extraMounts = listOf(mount.copy(readOnly = true))),
                    false,
                    false,
                    "",
                )
                .effect,
        )
        assertEquals(
            SbxApplyEffect.RESTART,
            SbxApplyPreview.build(
                    "/tmp/p",
                    old,
                    base.copy(extraMounts = listOf(mount.copy(sandboxPath = "/home/agent/d"))),
                    false,
                    false,
                    "",
                )
                .effect,
        )
    }

    @Test
    fun changingOpenCodeVersionNeedsANewVmFromTheOtherKit() {
        val v2 = base.copy(openCodeVersion = SbxOpenCodeVersion.V2)
        val on = SbxApplyPreview.build("/tmp/p", base, v2, false, false, "")
        assertEquals(SbxApplyEffect.RECREATE, on.effect)
        assertTrue(on.message().contains("OpenCode 2.x (Docker's latest OpenCode 2 kit)"))
        val off = SbxApplyPreview.build("/tmp/p", v2, base, false, false, "")
        assertEquals(SbxApplyEffect.RECREATE, off.effect)
        assertTrue(off.message().contains("OpenCode 1.x (Docker's OpenCode 1.x kit)"))
        val noVm = SbxApplyPreview.build("/tmp/p", base, v2, false, false, "", hasVm = false)
        assertEquals(SbxApplyEffect.NONE, noVm.effect)
        assertTrue(noVm.message().contains("applies when the sandbox is created"))
    }

    @Test
    fun changingTheImagePinNeedsANewVm() {
        val latest = base.copy(openCodeVersion = SbxOpenCodeVersion.V2)
        val pinned = latest.copy(openCodeImage = "2.2.7")
        val pin = SbxApplyPreview.build("/tmp/p", latest, pinned, false, false, "")
        assertEquals(SbxApplyEffect.RECREATE, pin.effect)
        assertTrue(pin.message(), pin.message().contains("Docker image latest → 2.2.7"))
        val unpin = SbxApplyPreview.build("/tmp/p", pinned, latest, false, false, "")
        assertTrue(unpin.message().contains("Docker image 2.2.7 → latest"))
        val noVm = SbxApplyPreview.build("/tmp/p", latest, pinned, false, false, "", hasVm = false)
        assertEquals(SbxApplyEffect.NONE, noVm.effect)
        val version = SbxApplyPreview.build("/tmp/p", base, pinned, false, false, "")
        assertTrue(
            "A version switch names the pinned kit once",
            version.message().contains("OpenCode 2.x (Docker's OpenCode 2 kit 2.2.7)"),
        )
        assertFalse(version.message().contains("Docker image latest"))
    }

    @Test
    fun emptyPreviewHasNoProcessChanges() {
        val preview = SbxApplyPreview.build("/tmp/p", base, base, false, false, "")
        assertEquals(SbxApplyEffect.NONE, preview.effect)
        assertTrue(preview.message().contains("Nothing restarts"))
        assertFalse(preview.message().contains("Cancel leaves"))
    }
}
