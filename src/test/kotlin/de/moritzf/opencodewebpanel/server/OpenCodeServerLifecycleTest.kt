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

    @Test
    fun staleLifecycleEventsAreIgnored() {
        assertTrue(
            shouldApplyPublishedLifecycleState(
                OpenCodeServerLifecycleState.STOPPED,
                OpenCodeServerLifecycleState.STOPPED,
            ),
        )
        assertFalse(
            shouldApplyPublishedLifecycleState(
                OpenCodeServerLifecycleState.STOPPED,
                OpenCodeServerLifecycleState.RUNNING,
            ),
        )
        assertFalse(
            shouldApplyPublishedLifecycleState(
                OpenCodeServerLifecycleState.RUNNING,
                OpenCodeServerLifecycleState.STOPPED,
            ),
        )
    }

    @Test
    fun stopAndRestartHideTheEmbeddedPageWithoutACefNavigation() {
        assertTrue(shouldHideEmbeddedPage(OpenCodeServerLifecycleState.STOPPED))
        assertTrue(shouldHideEmbeddedPage(OpenCodeServerLifecycleState.RESTARTING))
        assertFalse(shouldHideEmbeddedPage(OpenCodeServerLifecycleState.FAILED))
        assertFalse(shouldHideEmbeddedPage(OpenCodeServerLifecycleState.RUNNING))
        assertFalse(shouldHideEmbeddedPage(OpenCodeServerLifecycleState.STARTING))
    }

    @Test
    fun documentLoadTreatsCefStatusZeroAsSuccess() {
        assertTrue(isSuccessfulOpenCodeDocumentLoad(0))
        assertTrue(isSuccessfulOpenCodeDocumentLoad(200))
        assertTrue(isSuccessfulOpenCodeDocumentLoad(304))
        assertFalse(isSuccessfulOpenCodeDocumentLoad(401))
        assertFalse(isSuccessfulOpenCodeDocumentLoad(404))
        assertFalse(isSuccessfulOpenCodeDocumentLoad(500))
    }
}
