package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SbxDiagnosticsTest {
    @Test
    fun formatsOwnedPersistAndForeignStates() {
        val persistHost = SbxCli.sandboxPersistDataHome("ide-ocwp-x")
        val owned =
            SbxDiagnosticsSnapshot.from(
                "ide-ocwp-x",
                SbxSandboxRecord(
                    "abc123456789",
                    "ide-ocwp-x",
                    "opencode",
                    "/tmp/p",
                    createSnapshot = SbxCli.createSnapshot("4g", "2", true, listOf(persistHost)),
                ),
                foreign = false,
                serverUrl = "http://127.0.0.1:4096",
                version = "1.18.25",
            )
        assertEquals(SbxOwnership.OWNED, owned.ownership)
        assertTrue(owned.persistActive)
        assertEquals(
            "Sandbox: ide-ocwp-x (abc12345), created by the plugin\n" +
                "Server: http://127.0.0.1:4096\n" +
                "OpenCode: 1.18.25\n" +
                "Conversations: kept on this computer: $persistHost",
            owned.format(),
        )

        val adopted =
            SbxDiagnosticsSnapshot.from(
                "ide-ocwp-x",
                SbxSandboxRecord("id", "custom", "opencode", "/tmp/p", adopted = true),
                foreign = false,
                serverUrl = null,
                version = null,
            )
        assertEquals(SbxOwnership.ADOPTED, adopted.ownership)
        assertFalse(adopted.persistActive)
        assertTrue(adopted.format().contains("Conversations: inside the VM"))

        val foreign = SbxDiagnosticsSnapshot.from("ide-ocwp-x", null, true, null, null)
        assertEquals(SbxOwnership.FOREIGN, foreign.ownership)
        val none = SbxDiagnosticsSnapshot.from("ide-ocwp-x", null, false, null, null)
        assertEquals(SbxOwnership.NONE, none.ownership)
        val persistOff =
            SbxDiagnosticsSnapshot.from(
                "ide-ocwp-x",
                null,
                false,
                null,
                null,
                persistEnabled = false,
            )
        assertTrue(persistOff.format().endsWith("Conversations: inside the VM only"))
    }
}
