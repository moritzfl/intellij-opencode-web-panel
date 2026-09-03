package de.moritzf.opencodewebpanel.toolWindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeRendererWatchdogPolicyTest {
    @Test
    fun hiddenPagesAreNotMonitored() {
        assertFalse(OpenCodeRendererWatchdogPolicy.shouldMonitor(panelShowing = true, pageVisible = false))
        assertFalse(OpenCodeRendererWatchdogPolicy.shouldMonitor(panelShowing = false, pageVisible = true))
        assertTrue(OpenCodeRendererWatchdogPolicy.shouldMonitor(panelShowing = true, pageVisible = true))
    }

    @Test
    fun aBusySessionGetsTheLongTimeout() {
        assertEquals(
            OpenCodeRendererWatchdogPolicy.BUSY_STALL_TIMEOUT_MILLIS,
            OpenCodeRendererWatchdogPolicy.effectiveStallTimeout(agentBusy = true),
        )
        assertEquals(
            OpenCodeRendererWatchdogPolicy.STALL_TIMEOUT_MILLIS,
            OpenCodeRendererWatchdogPolicy.effectiveStallTimeout(agentBusy = false),
        )
    }

    @Test
    fun theFirstStallReloadsInPlace() {
        assertEquals(
            OpenCodeRendererWatchdogPolicy.Action.RELOAD,
            OpenCodeRendererWatchdogPolicy.stalledAction(
                consecutiveStalls = 1,
                recreatesAfterStall = 0,
                nowMillis = 100_000L,
                lastRecoveryAtMillis = 0L,
            ),
        )
    }

    @Test
    fun aPersistentStallEscalatesToRecreate() {
        assertEquals(
            OpenCodeRendererWatchdogPolicy.Action.RECREATE,
            OpenCodeRendererWatchdogPolicy.stalledAction(
                consecutiveStalls = 2,
                recreatesAfterStall = 0,
                nowMillis = 100_000L,
                lastRecoveryAtMillis = 0L,
            ),
        )
    }

    @Test
    fun theCooldownSuppressesRepeatedRecovery() {
        assertEquals(
            OpenCodeRendererWatchdogPolicy.Action.NONE,
            OpenCodeRendererWatchdogPolicy.stalledAction(
                consecutiveStalls = 1,
                recreatesAfterStall = 0,
                nowMillis = 100_000L,
                lastRecoveryAtMillis = 100_000L - OpenCodeRendererWatchdogPolicy.RECOVERY_COOLDOWN_MILLIS + 1,
            ),
        )
    }

    @Test
    fun theRecreateBudgetEndsInGiveUp() {
        assertEquals(
            OpenCodeRendererWatchdogPolicy.Action.GIVE_UP,
            OpenCodeRendererWatchdogPolicy.stalledAction(
                consecutiveStalls = 3,
                recreatesAfterStall = OpenCodeRendererWatchdogPolicy.MAX_STALLED_RECREATES,
                nowMillis = 1_000_000L,
                lastRecoveryAtMillis = 0L,
            ),
        )
    }

    @Test
    fun aSpentRecreateBudgetGivesUpEvenOnTheFirstStallOfANewPanel() {
        assertEquals(
            OpenCodeRendererWatchdogPolicy.Action.GIVE_UP,
            OpenCodeRendererWatchdogPolicy.stalledAction(
                consecutiveStalls = 1,
                recreatesAfterStall = OpenCodeRendererWatchdogPolicy.MAX_STALLED_RECREATES,
                nowMillis = 1_000_000L,
                lastRecoveryAtMillis = 0L,
            ),
        )
    }

    @Test
    fun anInFlightFirstLoadIsNotReadyUntilItPaintsOrThePageLoadWatchdogGivesUp() {
        assertFalse(
            OpenCodeRendererWatchdogPolicy.isPageReadyForRendererWatchdog(
                pageLoadInProgress = true,
                pagePainted = false,
                loadSucceeded = false,
                loadGaveUp = false,
            ),
        )
        assertFalse(
            OpenCodeRendererWatchdogPolicy.isPageReadyForRendererWatchdog(
                pageLoadInProgress = false,
                pagePainted = false,
                loadSucceeded = false,
                loadGaveUp = false,
            ),
        )
        assertTrue(
            OpenCodeRendererWatchdogPolicy.isPageReadyForRendererWatchdog(
                pageLoadInProgress = false,
                pagePainted = true,
                loadSucceeded = true,
                loadGaveUp = false,
            ),
        )
        assertTrue(
            OpenCodeRendererWatchdogPolicy.isPageReadyForRendererWatchdog(
                pageLoadInProgress = false,
                pagePainted = false,
                loadSucceeded = false,
                loadGaveUp = true,
            ),
        )
    }

    @Test
    fun theNotReadyGraceMatchesThePageLoadRetryBudget() {
        assertEquals(
            (OpenCodePageLoadWatchdog.DEFAULT_TIMEOUT_MILLIS +
                OpenCodePageLoadWatchdog.DOCUMENT_START_INSTALL_TIMEOUT_MILLIS) *
                (OpenCodePageLoadWatchdog.MAX_RETRIES + 1L),
            OpenCodeRendererWatchdogPolicy.NOT_READY_GRACE_MILLIS,
        )
    }
}