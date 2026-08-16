package de.moritzf.opencodewebpanel.toolWindow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun retriesTheExactRequestedNavigation() {
        val serverUrl = "http://127.0.0.1:4096"
        val notificationTarget = "$serverUrl/server/c2VydmVy/session/ses_notification?tab=review"

        assertEquals(
            notificationTarget,
            OpenCodePageLoadWatchdog.retryTarget(
                serverUrl,
                requestedUrl = notificationTarget,
                currentUrl = "$serverUrl/server/c2VydmVy/session/ses_startup",
            ),
        )
    }

    @Test
    fun retryTargetFallsBackOnlyToAnOpenCodeUrl() {
        val serverUrl = "http://127.0.0.1:4096"
        val current = "$serverUrl/server/c2VydmVy/session/ses_current"

        assertEquals(current, OpenCodePageLoadWatchdog.retryTarget(serverUrl, null, current))
        assertEquals(
            "$serverUrl/server/aHR0cDovLzEyNy4wLjAuMTo0MDk2/session",
            OpenCodePageLoadWatchdog.retryTarget(serverUrl, "https://example.com", "about:blank"),
        )
    }
}
