package de.moritzf.opencodewebpanel.toolWindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodePageLifecycleTest {
    private var now = 1L
    private val page = OpenCodePageLifecycle { now }

    @Test
    fun addressChangeDismissesOpeningWithoutAcknowledgingRenderer() {
        page.begin("http://localhost:4096/")
        val token = page.armWatchdog()
        page.addressChanged()
        page.dismissOpening()
        assertFalse(page.loadSucceeded)
        assertFalse(page.painted)
        assertFalse(page.loadInProgress)
        now += 30_000
        assertEquals(OpenCodePageLifecycle.Timeout.NONE, page.timeout(token))
    }

    @Test
    fun retriesExhaustAndNewExplicitLoadGetsFreshBudget() {
        page.begin("target")
        repeat(2) {
            val token = page.armWatchdog()
            now += 20_000
            assertEquals(OpenCodePageLifecycle.Timeout.RETRY, page.timeout(token))
            page.begin("target", resetRetryBudget = false)
        }
        now += 20_000
        assertEquals(OpenCodePageLifecycle.Timeout.GAVE_UP, page.timeout(page.armWatchdog()))
        assertTrue(page.gaveUp)
        assertFalse(page.loadInProgress)
        page.begin("new")
        assertFalse(page.gaveUp)
        assertEquals(0, page.retryCount)
        assertEquals(OpenCodePageLifecycle.Timeout.NONE, page.timeout(page.armWatchdog()))
    }

    @Test
    fun completionAndNewDocumentsInvalidateDelayedRetries() {
        val revision = page.documentStarted()
        page.begin("target")
        val pending = page.invalidatePendingLoad()
        assertTrue(page.acceptsPendingLoad(pending, revision))
        page.visible()
        assertFalse(page.acceptsPendingLoad(pending, revision))
        page.documentStarted()
        assertFalse(page.acceptsPendingLoad(pending, revision))
        val token = page.armWatchdog()
        page.visible()
        now += 20_000
        assertEquals(OpenCodePageLifecycle.Timeout.NONE, page.timeout(token))
    }

    @Test
    fun stopFailureAndDisposeInvalidatePendingWorkAndClearPresentation() {
        page.begin("target")
        val pending = page.invalidatePendingLoad()
        val watchdog = page.armWatchdog()
        page.reset()
        assertFalse(page.acceptsPendingLoad(pending))
        now += 20_000
        assertEquals(OpenCodePageLifecycle.Timeout.NONE, page.timeout(watchdog))
        assertFalse(page.loadInProgress)
        assertFalse(page.painted)
        assertFalse(page.gaveUp)
        assertNull(page.targetUrl)
    }
}
