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
}
