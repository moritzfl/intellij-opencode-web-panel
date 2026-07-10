package de.moritzf.opencodewebpanel.toolWindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeInjectedFeaturePolicyTest {

    @Test
    fun disableReloadsInsteadOfInjectingDisableScript() {
        val decision = OpenCodeInjectedFeaturePolicy.decide(
            enabled = false,
            enabledInSettings = false,
            onOpenCodePage = true,
            script = "/* should not inject a disable script */",
        )

        assertEquals(OpenCodeInjectedFeaturePolicy.Action.RELOAD, decision.action)
        assertNull(decision.script)
        assertTrue(decision.clearScheduled)
        assertEquals(false, decision.markScheduled)
    }

    @Test
    fun enableInjectsScriptWhenOnOpenCodePage() {
        val decision = OpenCodeInjectedFeaturePolicy.decide(
            enabled = true,
            enabledInSettings = true,
            onOpenCodePage = true,
            script = "window.__opencodeFeature = true;",
        )

        assertEquals(OpenCodeInjectedFeaturePolicy.Action.INJECT, decision.action)
        assertEquals("window.__opencodeFeature = true;", decision.script)
        assertTrue(decision.markScheduled)
    }

    @Test
    fun enableWithoutScriptIsNoOp() {
        val decision = OpenCodeInjectedFeaturePolicy.decide(
            enabled = true,
            enabledInSettings = true,
            onOpenCodePage = true,
            script = null,
        )

        assertEquals(OpenCodeInjectedFeaturePolicy.Action.NONE, decision.action)
        assertTrue(decision.clearScheduled)
        assertEquals(false, decision.markScheduled)
    }

    @Test
    fun offOpenCodePageIsNoOpEvenWhenDisabling() {
        val decision = OpenCodeInjectedFeaturePolicy.decide(
            enabled = false,
            enabledInSettings = false,
            onOpenCodePage = false,
            script = null,
        )

        assertEquals(OpenCodeInjectedFeaturePolicy.Action.NONE, decision.action)
        assertEquals(false, decision.clearScheduled)
    }

    @Test
    fun settingsGateOffForcesReloadEvenIfToggleReportsEnabled() {
        val decision = OpenCodeInjectedFeaturePolicy.decide(
            enabled = true,
            enabledInSettings = false,
            onOpenCodePage = true,
            script = "should-not-run",
        )

        assertEquals(OpenCodeInjectedFeaturePolicy.Action.RELOAD, decision.action)
        assertNull(decision.script)
    }
}
