package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SbxSetupDiagnosticsTest {
    private val record = SbxSandboxRecord("owned-id", "sandbox", "opencode", "/workspace")

    @Test
    fun probesOnlyTheModelCatalogBecauseDockersKitShipsOpenCode() {
        val probes = mutableListOf<List<String>>()
        val steps =
            SbxSetupDiagnostics.check(
                "sbx",
                record,
                SbxCommandRunner { command, env, timeout, _ ->
                    assertTrue(env.isEmpty())
                    assertTrue(timeout <= 15_000L)
                    if (command[1] == "ls") inventory()
                    else {
                        probes += command
                        assertEquals(
                            listOf("sbx", "exec", "-w", "/", "sandbox", "curl"),
                            command.take(6),
                        )
                        assertTrue(command.contains("--max-time"))
                        SbxCommandResult(0, "\nOCWP_HTTP=403\n")
                    }
                },
            )
        assertEquals(listOf("https://models.opencode.ai/api.json"), probes.map { it.last() })
        assertEquals("models.opencode.ai", steps.single().label)
        assertFalse(steps.single().done)
        assertTrue(steps.single().detail.contains("HTTP 403"))
    }

    @Test
    fun stoppedForeignAndUnknownInventoriesNeverExec() {
        for (listed in
            listOf(
                inventory(status = "stopped"),
                inventory(id = "foreign-id"),
                SbxCommandResult(1, "offline"),
                SbxCommandResult(0, "{}"),
            )) {
            val calls = mutableListOf<String>()
            val steps =
                SbxSetupDiagnostics.check(
                    "sbx",
                    record,
                    SbxCommandRunner { command, _, _, _ ->
                        calls += command[1]
                        listed
                    },
                )
            assertEquals(listOf("ls"), calls)
            assertFalse(steps.last().done)
        }
        val missing =
            SbxSetupDiagnostics.check(
                "sbx",
                null,
                SbxCommandRunner { _, _, _, _ -> error("No record must not invoke sbx") },
            )
        assertFalse(missing.last().done)
    }

    @Test
    fun reachableCatalogPassesAndTimeoutIsReported() {
        fun check(result: SbxCommandResult) =
            SbxSetupDiagnostics.check(
                    "sbx",
                    record,
                    SbxCommandRunner { command, _, _, _ ->
                        if (command[1] == "ls") inventory() else result
                    },
                )
                .single()
        assertTrue(check(SbxCommandResult(0, "curl: note on stderr\nOCWP_HTTP=200\n")).done)
        val timeout = check(SbxCommandResult(28, "curl: Operation timed out\nOCWP_HTTP=000\n"))
        assertFalse(timeout.done)
        assertTrue(timeout.detail.contains("exit 28"))
    }

    private fun inventory(status: String = "running", id: String = "owned-id") =
        SbxCommandResult(
            0,
            """{"sandboxes":[{"id":"$id","name":"sandbox","status":"$status","workspaces":["/workspace"]}]}""",
        )
}
