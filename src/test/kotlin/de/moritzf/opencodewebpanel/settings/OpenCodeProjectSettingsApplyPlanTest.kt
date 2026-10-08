package de.moritzf.opencodewebpanel.settings

import de.moritzf.opencodewebpanel.server.SbxApplyEffect
import de.moritzf.opencodewebpanel.server.SbxLaunchSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenCodeProjectSettingsApplyPlanTest {
    private val base =
        SbxLaunchSpec.fromSettings(OpenCodeSettingsState(), "/workspace").copy(useSandbox = true)
    private val values =
        OpenCodeProjectSettingsValues(
            OpenCodeProjectDirectoryMode.AUTO,
            "",
            "/workspace",
            OpenCodePortMode.AUTO,
            4096,
        )

    private fun plan(
        next: SbxLaunchSpec,
        oldDirectory: String = "/workspace",
        hasVm: Boolean = true,
    ) =
        OpenCodeProjectSettingsApplyPlan.build(
            values,
            next,
            base,
            oldDirectory,
            oldDirectory,
            true,
            hasVm,
            "",
        )

    @Test
    fun restartWaitsForCleanupCompletion() {
        val plan = plan(base.copy(workingDirectory = "./app"))
        val events = mutableListOf<String>()
        var stopped: (() -> Unit)? = null
        plan.applyRuntime(
            false,
            {
                events += "stop"
                stopped = it
            },
            { events += "restart" },
            { fail("reset") },
            { fail("live") },
        )
        assertEquals(listOf("stop"), events)
        stopped!!()
        assertEquals(listOf("stop", "restart"), events)
    }

    @Test
    fun leavingSharedBackendKeepsOtherProjectRunning() {
        var restarts = 0
        plan(base, oldDirectory = "/previous")
            .applyRuntime(
                true,
                { fail("stopped shared backend") },
                { restarts++ },
                { fail("reset") },
                { fail("live") },
            )
        assertEquals(1, restarts)
    }

    @Test
    fun recreateRequiresExistingVmAndRunsAfterStop() {
        val next = base.copy(shareHostOpencodeConfig = true)
        assertFalse(plan(next, hasVm = false).requiresStop)
        val events = mutableListOf<String>()
        plan(next)
            .applyRuntime(
                false,
                {
                    events += "stop"
                    it()
                },
                { fail("restart") },
                { events += "reset" },
                { fail("live") },
            )
        assertEquals(listOf("stop", "reset"), events)
    }

    @Test
    fun livePortRemapAndDeferredMemoryDoNotStop() {
        var live = 0
        plan(base.copy(hostPort = 4097))
            .applyRuntime(
                false,
                { fail("stop") },
                { fail("restart") },
                { fail("reset") },
                { live++ },
            )
        assertEquals(1, live)
        val deferred = plan(base.copy(memory = "8g"))
        assertEquals(SbxApplyEffect.NONE, deferred.preview.effect)
        deferred.applyRuntime(
            false,
            { fail("stop") },
            { fail("restart") },
            { fail("reset") },
            { fail("live") },
        )
    }

    @Test
    fun destinationBaselineDoesNotRecreateTheProjectBeingLeft() {
        val destination =
            base.copy(canonicalDirectory = "/destination", kits = listOf("./other-kit"))
        val plan =
            OpenCodeProjectSettingsApplyPlan.build(
                values.copy(effectiveDirectory = "/destination"),
                destination,
                destination,
                "/workspace",
                "/workspace",
                true,
                true,
                "",
            )
        assertTrue(plan.backendChanged)
        assertFalse(plan.recreate)
        assertEquals(SbxApplyEffect.RESTART, plan.preview.effect)
    }
}
