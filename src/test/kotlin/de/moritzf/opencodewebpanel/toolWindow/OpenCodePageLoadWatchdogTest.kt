package de.moritzf.opencodewebpanel.toolWindow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodePageLoadWatchdogTest {
    @Test
    fun doesNotRetryWhileTheFirstLoadIsStillInsideTheBudget() {
        assertFalse(
            OpenCodePageLoadWatchdog.shouldRetry(
                succeeded = false,
                retryCount = 0,
                elapsedMillis = 8_000,
                timeoutMillis = 20_000,
            ),
        )
    }

    @Test
    fun retriesAfterTheBudgetIfTheDocumentNeverFinished() {
        assertTrue(
            OpenCodePageLoadWatchdog.shouldRetry(
                succeeded = false,
                retryCount = 0,
                elapsedMillis = 20_000,
            ),
        )
    }

    @Test
    fun doesNotRetryASuccessfulLoad() {
        assertFalse(
            OpenCodePageLoadWatchdog.shouldRetry(
                succeeded = true,
                retryCount = 0,
                elapsedMillis = 60_000,
            ),
        )
    }

    @Test
    fun stopsAfterTheRetryCap() {
        assertFalse(
            OpenCodePageLoadWatchdog.shouldRetry(
                succeeded = false,
                retryCount = OpenCodePageLoadWatchdog.MAX_RETRIES,
                elapsedMillis = 60_000,
            ),
        )
    }
}
