package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SbxSetupDiagnosticsTest {
    private val spec = SbxLaunchSpec(
        canonicalDirectory = "/workspace", name = "sandbox", memory = "4g", cpus = "2",
        kits = emptyList(), extraMounts = emptyList(), shareHostOpencodeConfig = true,
        openCodeVersion = SbxOpenCodeVersion.V2, enableIntellijMcp = false,
    )
    private val record = SbxSandboxRecord("owned-id", "sandbox", "opencode", "/workspace")

    @Test
    fun reportsBlockedCatalogSeparatelyFromReachableInstaller() {
        val probes = mutableListOf<List<String>>()
        val steps = SbxSetupDiagnostics.check("sbx", spec, record, SbxCommandRunner { command, env, timeout, _ ->
            assertTrue(env.isEmpty())
            assertTrue(timeout <= 15_000L)
            if (command[1] == "ls") inventory() else {
                probes += command
                assertEquals(listOf("sbx", "exec", "sandbox", "curl"), command.take(4))
                assertTrue(command.contains("--max-time"))
                SbxCommandResult(0, if (command.last().contains("models.opencode.ai")) "\nOCWP_HTTP=403\n" else "curl: note on stderr\nOCWP_HTTP=200\n")
            }
        })
        assertEquals(3, probes.size)
        assertEquals(3, steps.size)
        assertTrue(steps[0].done)
        assertTrue(steps[1].done)
        assertFalse(steps.last().done)
        assertTrue(steps.last().detail.contains("HTTP 403"))
    }

    @Test
    fun stoppedForeignAndUnknownInventoriesNeverExec() {
        for (listed in listOf(inventory(status = "stopped"), inventory(id = "foreign-id"), SbxCommandResult(1, "offline"), SbxCommandResult(0, "{}"))) {
            val calls = mutableListOf<String>()
            val steps = SbxSetupDiagnostics.check("sbx", spec, record, SbxCommandRunner { command, _, _, _ ->
                calls += command[1]
                listed
            })
            assertEquals(listOf("ls"), calls)
            assertFalse(steps.last().done)
        }
        val missing = SbxSetupDiagnostics.check("sbx", spec, null, SbxCommandRunner { _, _, _, _ ->
            error("No record must not invoke sbx")
        })
        assertFalse(missing.last().done)
    }

    @Test
    fun v1WithoutConfigShareOnlyChecksCatalogAndReportsTimeout() {
        val steps = SbxSetupDiagnostics.check("sbx", spec.copy(openCodeVersion = SbxOpenCodeVersion.V1, shareHostOpencodeConfig = false), record,
            SbxCommandRunner { command, _, _, _ ->
                if (command[1] == "ls") inventory() else {
                    assertEquals("https://models.opencode.ai/api.json", command.last())
                    SbxCommandResult(28, "curl: Operation timed out\nOCWP_HTTP=000\n")
                }
            },
        )
        assertEquals(1, steps.size)
        assertFalse(steps.single().done)
        assertTrue(steps.single().detail.contains("exit 28"))
    }

    private fun inventory(status: String = "running", id: String = "owned-id") = SbxCommandResult(
        0, """{"sandboxes":[{"id":"$id","name":"sandbox","status":"$status","workspaces":["/workspace"]}]}""",
    )
}
