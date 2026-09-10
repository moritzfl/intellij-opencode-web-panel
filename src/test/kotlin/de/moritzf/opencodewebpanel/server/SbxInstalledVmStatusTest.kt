package de.moritzf.opencodewebpanel.server

import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SbxInstalledVmStatusTest {
    @Test
    fun pendingWhenCreateSnapshotDiffers() {
        val spec = SbxLaunchSpec.fromSettings(OpenCodeSettingsState(), "/tmp/p").copy(memory = "8g", useSandbox = true)
        val record = SbxSandboxRecord(
            "id", "ide-ocwp-x", "opencode", "/tmp/p",
            createSnapshot = SbxCli.createSnapshot("4g", "2", true, emptyList()),
        )
        val statuses = SbxInstalledVmStatus.settings(spec, record)
        assertTrue(statuses.single { it.label == "Memory" }.pending)
        assertFalse(statuses.single { it.label == "CPUs" }.pending)
        val hint = SbxInstalledVmStatus.hint(statuses)
        assertTrue(hint!!.contains("Memory"))
        assertTrue(hint.contains("Reset Sandbox"))
    }

    @Test
    fun adoptedHintIgnoresSnapshot() {
        val spec = SbxLaunchSpec.fromSettings(OpenCodeSettingsState(), "/tmp/p")
        assertEquals(
            "Adopted sandbox: create-time settings are unknown until Reset Sandbox.",
            SbxInstalledVmStatus.hint(SbxInstalledVmStatus.settings(spec, null), adopted = true),
        )
        assertNull(SbxInstalledVmStatus.hint(SbxInstalledVmStatus.settings(spec, null)))
    }
}
