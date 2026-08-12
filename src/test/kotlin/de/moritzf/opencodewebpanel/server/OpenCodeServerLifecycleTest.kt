package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeServerLifecycleTest {

    @Test
    fun retryIsVisibleForFailedAndStoppedServers() {
        assertTrue(isOpenCodeServerRetryVisible(OpenCodeServerLifecycleState.FAILED))
        assertTrue(isOpenCodeServerRetryVisible(OpenCodeServerLifecycleState.STOPPED))
        assertFalse(isOpenCodeServerRetryVisible(OpenCodeServerLifecycleState.STARTING))
        assertFalse(isOpenCodeServerRetryVisible(OpenCodeServerLifecycleState.RESTARTING))
        assertFalse(isOpenCodeServerRetryVisible(OpenCodeServerLifecycleState.RUNNING))
    }

    @Test
    fun retryLabelDistinguishesStartFromRetry() {
        assertEquals("Start", openCodeServerRetryLabel(OpenCodeServerLifecycleState.STOPPED))
        assertEquals("Retry", openCodeServerRetryLabel(OpenCodeServerLifecycleState.FAILED))
        assertEquals("Retry", openCodeServerRetryLabel(OpenCodeServerLifecycleState.RUNNING))
    }

    @Test
    fun reloadActionIsDisabledOnlyWhenServerIsStopped() {
        assertTrue(isOpenCodePageReloadEnabled(OpenCodeServerLifecycleState.STARTING))
        assertTrue(isOpenCodePageReloadEnabled(OpenCodeServerLifecycleState.RUNNING))
        assertTrue(isOpenCodePageReloadEnabled(OpenCodeServerLifecycleState.FAILED))
        assertTrue(isOpenCodePageReloadEnabled(OpenCodeServerLifecycleState.RESTARTING))
        assertFalse(isOpenCodePageReloadEnabled(OpenCodeServerLifecycleState.STOPPED))
    }

    @Test
    fun stopActionIsEnabledForRunningOrStartingServer() {
        assertTrue(isOpenCodeServerStopEnabled(OpenCodeServerLifecycleState.STARTING))
        assertTrue(isOpenCodeServerStopEnabled(OpenCodeServerLifecycleState.RUNNING))
        assertTrue(isOpenCodeServerStopEnabled(OpenCodeServerLifecycleState.RESTARTING))
        assertFalse(isOpenCodeServerStopEnabled(OpenCodeServerLifecycleState.FAILED))
        assertFalse(isOpenCodeServerStopEnabled(OpenCodeServerLifecycleState.STOPPED))
    }
}
