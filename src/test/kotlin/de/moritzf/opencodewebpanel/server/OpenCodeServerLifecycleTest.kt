package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun newPanelCreatedDuringRestartParksOnTheIdleCard() {
        assertEquals("idle", parkedEmbeddedCenterCard(OpenCodeServerLifecycleState.RESTARTING))
        assertEquals("idle", parkedEmbeddedCenterCard(OpenCodeServerLifecycleState.STOPPED))
        assertEquals("error", parkedEmbeddedCenterCard(OpenCodeServerLifecycleState.FAILED))
        assertNull(parkedEmbeddedCenterCard(OpenCodeServerLifecycleState.RUNNING))
        assertNull(parkedEmbeddedCenterCard(OpenCodeServerLifecycleState.STARTING))
    }

    @Test
    fun stripStaysVisibleWhileThePageIsOpening() {
        assertTrue(isOpenCodeLifecycleStripVisible(OpenCodeServerLifecycleState.RUNNING, pageOpening = true))
        assertFalse(isOpenCodeLifecycleStripVisible(OpenCodeServerLifecycleState.RUNNING, pageOpening = false))
        assertTrue(isOpenCodeLifecycleStripVisible(OpenCodeServerLifecycleState.STARTING, pageOpening = true))
        assertFalse(isOpenCodeLifecycleStripVisible(OpenCodeServerLifecycleState.STOPPED))
        assertFalse(isOpenCodeLifecycleStripVisible(OpenCodeServerLifecycleState.RESTARTING))
        assertTrue(isOpenCodeLifecycleStripVisible(OpenCodeServerLifecycleState.FAILED))
    }

    @Test
    fun pageOpeningStripStaysHiddenAfterThePageHasPainted() {
        assertTrue(shouldShowPageOpeningStatus(pageLoadInProgress = true, openCodePagePainted = false))
        assertFalse(shouldShowPageOpeningStatus(pageLoadInProgress = true, openCodePagePainted = true))
        assertFalse(shouldShowPageOpeningStatus(pageLoadInProgress = false, openCodePagePainted = false))
        assertFalse(shouldShowPageOpeningStatus(pageLoadInProgress = false, openCodePagePainted = true))
    }

    @Test
    fun pageOpeningStatusUsesTheSameDotStyle() {
        val html = formatOpenCodePageOpeningStatusText()
        assertTrue(html.contains("&#9679;"))
        assertTrue(html.contains("#FFC107"))
        assertTrue(html.contains("Opening the OpenCode page"))
    }

    @Test
    fun startupErrorCardIsShownForFailedStarts() {
        assertTrue(shouldShowStartupError(OpenCodeServerLifecycleState.FAILED))
        assertFalse(shouldShowStartupError(OpenCodeServerLifecycleState.STOPPED))
        assertFalse(shouldShowStartupError(OpenCodeServerLifecycleState.RUNNING))
        assertFalse(shouldShowStartupError(OpenCodeServerLifecycleState.STARTING))
        assertFalse(shouldShowStartupError(OpenCodeServerLifecycleState.RESTARTING))
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
