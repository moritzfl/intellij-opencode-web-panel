package de.moritzf.opencodewebpanel.settings

import de.moritzf.opencodewebpanel.configuration.OpenCodeRestartScope
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeServerRestartScopeTest {
    @Test
    fun scopeFollowsWhatTheChangeReaches() {
        assertNull(appRestartScope(false, false, false))
        assertEquals(OpenCodeRestartScope.ALL, appRestartScope(true, false, false))
        assertEquals(OpenCodeRestartScope.ALL, appRestartScope(false, true, true))
        assertEquals(OpenCodeRestartScope.NATIVE, appRestartScope(false, true, false))
        assertEquals(OpenCodeRestartScope.SBX, appRestartScope(false, false, true))
    }

    @Test
    fun scopeCoversTheMatchingRuntime() {
        assertTrue(OpenCodeRestartScope.NATIVE.covers("native:ide-ocwp-1"))
        assertFalse(OpenCodeRestartScope.NATIVE.covers("sbx:ide-ocwp-1"))
        assertTrue(OpenCodeRestartScope.SBX.covers("sbx:ide-ocwp-1"))
        assertTrue(OpenCodeRestartScope.ALL.covers("sbx:ide-ocwp-1"))
    }

    @Test
    fun onlyActiveStatesAreInterrupted() {
        val interruptible = OpenCodeServerLifecycleState.entries.filter { it.isInterruptible() }
        assertEquals(
            listOf(
                OpenCodeServerLifecycleState.STARTING,
                OpenCodeServerLifecycleState.RUNNING,
                OpenCodeServerLifecycleState.RESTARTING,
            ),
            interruptible,
        )
    }
}
