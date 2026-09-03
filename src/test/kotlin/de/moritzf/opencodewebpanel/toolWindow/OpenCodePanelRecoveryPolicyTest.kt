package de.moritzf.opencodewebpanel.toolWindow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodePanelRecoveryPolicyTest {
    @Test
    fun recreatesThePanelOnTheFirstCallbackFailure() {
        assertTrue(OpenCodePanelRecoveryPolicy.shouldRecreatePanel(lastAttemptAtMillis = 0L, nowMillis = 5_000L))
    }

    @Test
    fun doesNotLoopWhileTheThrottleIsStillRunning() {
        assertFalse(
            OpenCodePanelRecoveryPolicy.shouldRecreatePanel(
                lastAttemptAtMillis = 10_000L,
                nowMillis = 10_000L + OpenCodePanelRecoveryPolicy.RETRY_THROTTLE_MILLIS - 1,
            ),
        )
    }

    @Test
    fun recreatesAgainAfterTheThrottle() {
        assertTrue(
            OpenCodePanelRecoveryPolicy.shouldRecreatePanel(
                lastAttemptAtMillis = 10_000L,
                nowMillis = 10_000L + OpenCodePanelRecoveryPolicy.RETRY_THROTTLE_MILLIS,
            ),
        )
    }
}
